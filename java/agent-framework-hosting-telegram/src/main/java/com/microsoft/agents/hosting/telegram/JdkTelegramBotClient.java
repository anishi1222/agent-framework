// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.internal.StrictJsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends bounded Telegram Bot API requests using a redirect-free JDK {@link HttpClient}.
 *
 * <p>The client supports only {@code sendMessage}. Response bodies are streamed through a byte
 * bound before strict JSON decoding. Caller cancellation closes the response body and cancels the
 * underlying HTTP exchange.
 */
public final class JdkTelegramBotClient implements TelegramBotClient, AutoCloseable {
    private final TelegramBotClientOptions options;

    private final StrictJsonCodec requestCodec;

    private final StrictJsonCodec responseCodec;

    private final ExecutorService executor;

    private final ScheduledThreadPoolExecutor scheduler;

    private final HttpClient httpClient;

    private final Semaphore permits;

    private final Set<Exchange> active = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a redirect-free JDK client.
     *
     * @param options client options
     */
    public JdkTelegramBotClient(TelegramBotClientOptions options) {
        this(options, null);
    }

    JdkTelegramBotClient(TelegramBotClientOptions options, HttpClient httpClient) {
        this.options = Objects.requireNonNull(options, "options");
        if (httpClient != null && httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Caller-supplied HttpClient must disable redirects.");
        }
        requestCodec = new StrictJsonCodec(
                options.maxRequestBytes(),
                options.maxRequestBytes(),
                options.maxWriteNestingDepth(),
                options.maxWriteStringLength(),
                64,
                options.maxWriteCollectionEntries());
        responseCodec = codec(options.maxResponseBytes(), options.maxResponseBytes());
        executor = Executors.newVirtualThreadPerTaskExecutor();
        scheduler = new ScheduledThreadPoolExecutor(
                1,
                Thread.ofPlatform()
                        .daemon(true)
                        .name("agent-framework-telegram-client-timeouts")
                        .factory());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        this.httpClient = httpClient == null
                ? HttpClient.newBuilder()
                        .connectTimeout(options.connectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .executor(executor)
                        .build()
                : httpClient;
        permits = new Semaphore(options.maxConcurrentRequests(), true);
    }

    @Override
    public CompletionStage<TelegramSendMessageResult> sendMessageAsync(
            TelegramSendMessageRequest request, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new TelegramBotException(TelegramBotErrorCode.CLIENT_CLOSED, null, null));
        }
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new TelegramBotException(TelegramBotErrorCode.CANCELLED, null, null));
        }
        if (!permits.tryAcquire()) {
            return CompletableFuture.failedFuture(
                    new TelegramBotException(TelegramBotErrorCode.CONCURRENCY_LIMIT, null, null));
        }

        byte[] body;
        HttpRequest httpRequest;
        try {
            body = requestCodec.write(StateValue.object(Map.of(
                    "chat_id", StateValue.integer(request.chatId()),
                    "text", StateValue.string(request.text()))));
            httpRequest = HttpRequest.newBuilder(sendMessageEndpoint())
                    .timeout(options.requestTimeout())
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "agent-framework-java/hosting-telegram")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
        } catch (SerializationException exception) {
            permits.release();
            return CompletableFuture.failedFuture(
                    new TelegramBotException(TelegramBotErrorCode.REQUEST_TOO_LARGE, null, null));
        } catch (RuntimeException exception) {
            permits.release();
            if (exception instanceof TelegramBotException botFailure) {
                return CompletableFuture.failedFuture(botFailure);
            }
            return CompletableFuture.failedFuture(
                    new TelegramBotException(TelegramBotErrorCode.INVALID_RESPONSE, null, null, exception));
        }

        Exchange exchange = new Exchange(cancellation);
        active.add(exchange);
        if (closed.get()) {
            exchange.fail(TelegramBotErrorCode.CLIENT_CLOSED, null, null);
            return exchange.result();
        }
        exchange.start(httpRequest);
        return exchange.result();
    }

    /** Cancels active requests and releases client-owned executors. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (Exchange exchange : Set.copyOf(active)) {
            exchange.fail(TelegramBotErrorCode.CLIENT_CLOSED, null, null);
        }
        scheduler.shutdownNow();
        executor.shutdownNow();
    }

    private StrictJsonCodec codec(int writeBytes, int readBytes) {
        return new StrictJsonCodec(
                writeBytes,
                readBytes,
                options.maxNestingDepth(),
                options.maxStringLength(),
                64,
                options.maxCollectionEntries());
    }

    private URI sendMessageEndpoint() {
        URI endpoint = options.endpoint().resolve("./bot" + options.botToken() + "/sendMessage");
        if (!Objects.equals(endpoint.getScheme(), options.endpoint().getScheme())
                || !Objects.equals(endpoint.getHost(), options.endpoint().getHost())
                || effectivePort(endpoint) != effectivePort(options.endpoint())
                || endpoint.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Resolved Telegram request URI escaped the configured endpoint.");
        }
        return endpoint;
    }

    private TelegramSendMessageResult decode(HttpResponse<InputStream> response) {
        try (InputStream input = response.body()) {
            byte[] body = readBounded(input);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new TelegramBotException(TelegramBotErrorCode.HTTP_ERROR, response.statusCode(), null);
            }
            StateValue parsed;
            try {
                parsed = responseCodec.parse(body);
            } catch (SerializationException exception) {
                throw new TelegramBotException(TelegramBotErrorCode.INVALID_RESPONSE, response.statusCode(), null);
            }
            if (!(parsed instanceof StateValue.ObjectValue envelope)) {
                throw new TelegramBotException(TelegramBotErrorCode.INVALID_RESPONSE, response.statusCode(), null);
            }
            StateValue okValue = envelope.values().get("ok");
            if (!(okValue instanceof StateValue.BooleanValue ok)) {
                throw new TelegramBotException(TelegramBotErrorCode.INVALID_RESPONSE, response.statusCode(), null);
            }
            if (!ok.value()) {
                throw new TelegramBotException(
                        TelegramBotErrorCode.API_ERROR, response.statusCode(), optionalInteger(envelope, "error_code"));
            }
            StateValue resultValue = envelope.values().get("result");
            if (!(resultValue instanceof StateValue.ObjectValue result)) {
                throw new TelegramBotException(TelegramBotErrorCode.INVALID_RESPONSE, response.statusCode(), null);
            }
            long messageId = requiredPositiveLong(result, "message_id");
            return new TelegramSendMessageResult(messageId);
        } catch (TelegramBotException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new TelegramBotException(TelegramBotErrorCode.TRANSPORT_ERROR, null, null);
        }
    }

    private byte[] readBounded(InputStream input) throws IOException {
        byte[] body = input.readNBytes(options.maxResponseBytes() + 1);
        if (body.length > options.maxResponseBytes()) {
            throw new TelegramBotException(TelegramBotErrorCode.RESPONSE_TOO_LARGE, null, null);
        }
        return body;
    }

    private static Integer optionalInteger(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (!(value instanceof StateValue.NumberValue number) || number.value().scale() > 0) {
            return null;
        }
        try {
            return number.value().intValueExact();
        } catch (ArithmeticException ignored) {
            return null;
        }
    }

    private static long requiredPositiveLong(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value instanceof StateValue.NumberValue number
                && number.value().scale() <= 0
                && number.value().signum() > 0) {
            try {
                return number.value().longValueExact();
            } catch (ArithmeticException ignored) {
                // Fall through to the stable error.
            }
        }
        throw new TelegramBotException(TelegramBotErrorCode.INVALID_RESPONSE, null, null);
    }

    private static int effectivePort(URI value) {
        if (value.getPort() >= 0) {
            return value.getPort();
        }
        return "https".equalsIgnoreCase(value.getScheme()) ? 443 : 80;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private final class Exchange {
        private final RunCancellation cancellation;

        private final CompletableFuture<TelegramSendMessageResult> result = new CompletableFuture<>();

        private final AtomicReference<CompletableFuture<HttpResponse<InputStream>>> call = new AtomicReference<>();

        private final AtomicReference<InputStream> responseBody = new AtomicReference<>();

        private final AtomicReference<RunCancellationRegistration> cancellationRegistration = new AtomicReference<>();

        private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>();

        private final AtomicBoolean terminal = new AtomicBoolean();

        private final AtomicBoolean cleaned = new AtomicBoolean();

        private Exchange(RunCancellation cancellation) {
            this.cancellation = cancellation;
        }

        private CompletionStage<TelegramSendMessageResult> result() {
            return result.minimalCompletionStage();
        }

        private void start(HttpRequest request) {
            RunCancellationRegistration registration =
                    RunCancellations.register(cancellation, () -> fail(TelegramBotErrorCode.CANCELLED, null, null));
            cancellationRegistration.set(registration);
            if (terminal.get()) {
                registration.close();
                cancellationRegistration.compareAndSet(registration, null);
                return;
            }
            ScheduledFuture<?> timeoutTask;
            try {
                timeoutTask = scheduler.schedule(
                        () -> fail(TelegramBotErrorCode.TIMEOUT, null, null),
                        options.requestTimeout().toMillis(),
                        TimeUnit.MILLISECONDS);
            } catch (RuntimeException exception) {
                fail(
                        closed.get() ? TelegramBotErrorCode.CLIENT_CLOSED : TelegramBotErrorCode.TRANSPORT_ERROR,
                        null,
                        null);
                return;
            }
            timeout.set(timeoutTask);
            if (terminal.get() && timeout.compareAndSet(timeoutTask, null)) {
                timeoutTask.cancel(false);
                return;
            }
            CompletableFuture<HttpResponse<InputStream>> future;
            try {
                future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (RuntimeException exception) {
                fail(TelegramBotErrorCode.TRANSPORT_ERROR, null, null);
                return;
            }
            call.set(future);
            if (terminal.get()) {
                future.cancel(true);
                return;
            }
            future.whenComplete((response, failure) -> {
                if (failure != null) {
                    Throwable cause = unwrap(failure);
                    if (cause instanceof HttpTimeoutException) {
                        fail(TelegramBotErrorCode.TIMEOUT, null, null);
                    } else if (cause instanceof CancellationException && cancellation.isCancellationRequested()) {
                        fail(TelegramBotErrorCode.CANCELLED, null, null);
                    } else {
                        fail(TelegramBotErrorCode.TRANSPORT_ERROR, null, null);
                    }
                    return;
                }
                responseBody.set(response.body());
                if (terminal.get()) {
                    close(responseBody.getAndSet(null));
                    return;
                }
                executor.execute(() -> {
                    try {
                        complete(decode(response));
                    } catch (TelegramBotException exception) {
                        fail(exception.code(), exception.httpStatus(), exception.apiErrorCode());
                    }
                });
            });
        }

        private void complete(TelegramSendMessageResult value) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            result.complete(value);
            cleanup();
        }

        private void fail(TelegramBotErrorCode code, Integer httpStatus, Integer apiErrorCode) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            result.completeExceptionally(new TelegramBotException(code, httpStatus, apiErrorCode));
            cleanup();
        }

        private void cleanup() {
            if (!cleaned.compareAndSet(false, true)) {
                return;
            }
            CompletableFuture<?> future = call.getAndSet(null);
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
            close(responseBody.getAndSet(null));
            RunCancellationRegistration registration = cancellationRegistration.getAndSet(null);
            if (registration != null) {
                registration.close();
            }
            ScheduledFuture<?> timeoutTask = timeout.getAndSet(null);
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
            }
            active.remove(this);
            permits.release();
        }
    }

    private static void close(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // Closing is best effort during cancellation and cleanup.
        }
    }
}
