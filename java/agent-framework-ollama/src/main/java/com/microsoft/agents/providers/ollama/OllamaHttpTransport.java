// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.ollama;

import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.internal.SingleSubscriberPublisher;
import com.microsoft.agents.core.internal.StrictJsonCodec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class OllamaHttpTransport implements OllamaTransport {
    private final OllamaChatClientOptions options;

    private final StrictJsonCodec documentCodec;

    private final StrictJsonCodec eventCodec;

    private final HttpClient httpClient;

    private final ExecutorService executor;

    private final boolean ownsExecutor;

    private final Semaphore permits;

    private final AtomicBoolean closed = new AtomicBoolean();

    private OllamaHttpTransport(
            OllamaChatClientOptions options, HttpClient httpClient, ExecutorService executor, boolean ownsExecutor) {
        this.options = options;
        documentCodec = codec(options.maxRequestBytes(), options.maxResponseBytes());
        eventCodec = codec(options.maxEventBytes(), options.maxEventBytes());
        this.httpClient = httpClient;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        permits = new Semaphore(options.maxConcurrentRequests());
    }

    static OllamaHttpTransport create(
            OllamaChatClientOptions options, HttpClient suppliedClient, ExecutorService suppliedExecutor) {
        Objects.requireNonNull(options, "options");
        if (suppliedClient != null && suppliedClient.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Caller-supplied HttpClient must disable redirects.");
        }
        ExecutorService executor =
                suppliedExecutor == null ? Executors.newVirtualThreadPerTaskExecutor() : suppliedExecutor;
        if (suppliedClient != null) {
            return new OllamaHttpTransport(options, suppliedClient, executor, suppliedExecutor == null);
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(options.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(executor)
                .build();
        return new OllamaHttpTransport(options, client, executor, suppliedExecutor == null);
    }

    @Override
    public CompletionStage<ChatResponse> completeAsync(
            ChatClientRequest request, OllamaChatClientOptions ignored, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        if (closed.get()) {
            return CompletableFuture.failedFuture(failure("transport_closed"));
        }
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        if (!permits.tryAcquire()) {
            return CompletableFuture.failedFuture(failure("concurrency_limit"));
        }
        byte[] encoded;
        try {
            encoded = documentCodec.write(OllamaMessageMapper.request(request, options, documentCodec, false));
        } catch (RuntimeException exception) {
            permits.release();
            return CompletableFuture.failedFuture(exception);
        }
        CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        AtomicReference<InputStream> stream = new AtomicReference<>();
        CompletableFuture<HttpResponse<InputStream>> call =
                httpClient.sendAsync(httpRequest(encoded), HttpResponse.BodyHandlers.ofInputStream());
        RunCancellationRegistration registration = RunCancellations.register(cancellation, () -> {
            closeQuietly(stream.get());
            call.cancel(true);
            result.completeExceptionally(new RunCancelledException());
        });
        call.whenCompleteAsync(
                (response, callFailure) -> {
                    try {
                        if (callFailure != null) {
                            result.completeExceptionally(normalize(callFailure, cancellation));
                            return;
                        }
                        stream.set(response.body());
                        byte[] body = readBounded(response.body(), options.maxResponseBytes());
                        String requestId = requestId(response);
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            throw new OllamaProviderException("http_error", response.statusCode(), requestId);
                        }
                        result.complete(
                                OllamaMessageMapper.response(documentCodec.parse(body), requestId, documentCodec));
                    } catch (RuntimeException exception) {
                        result.completeExceptionally(normalize(exception, cancellation));
                    } finally {
                        closeQuietly(stream.getAndSet(null));
                        registration.close();
                        permits.release();
                    }
                },
                executor);
        return result;
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> completeStreaming(
            ChatClientRequest request, OllamaChatClientOptions ignored, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        StreamingOperation operation = new StreamingOperation(request, cancellation);
        SingleSubscriberPublisher<ChatResponseUpdate> publisher = new SingleSubscriberPublisher<>(
                operation::start,
                operation::cancel,
                options.maxBufferedUpdates(),
                limit -> failure("stream_buffer_overflow"));
        operation.sink = publisher;
        return publisher;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && ownsExecutor) {
            executor.close();
        }
    }

    private HttpRequest httpRequest(byte[] body) {
        URI uri = options.endpoint().resolve("api/chat");
        if (!Objects.equals(uri.getHost(), options.endpoint().getHost())
                || !Objects.equals(uri.getScheme(), options.endpoint().getScheme())) {
            throw new IllegalArgumentException("Resolved Ollama URI escaped the configured endpoint.");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(options.timeout())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "agent-framework-java/ollama")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (options.hasBearerToken()) {
            builder.header("Authorization", "Bearer " + options.bearerToken().value());
        }
        return builder.build();
    }

    private StrictJsonCodec codec(int writeBytes, int readBytes) {
        return new StrictJsonCodec(
                writeBytes,
                readBytes,
                options.maxNestingDepth(),
                options.maxStringLength(),
                1_000,
                options.maxCollectionEntries());
    }

    private static byte[] readBounded(InputStream input, int maximum) {
        try {
            byte[] body = input.readNBytes(maximum + 1);
            if (body.length > maximum) {
                throw failure("response_too_large");
            }
            return body;
        } catch (IOException exception) {
            throw failure("response_io");
        }
    }

    private static String requestId(HttpResponse<?> response) {
        return response.headers()
                .firstValue("x-request-id")
                .or(() -> response.headers().firstValue("request-id"))
                .orElse(null);
    }

    private static RuntimeException normalize(Throwable failure, RunCancellation cancellation) {
        if (cancellation.isCancellationRequested()) {
            return new RunCancelledException();
        }
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof com.microsoft.agents.core.AgentFrameworkException framework) {
            return framework;
        }
        return failure("transport_error");
    }

    private static OllamaProviderException failure(String kind) {
        return new OllamaProviderException(kind, null, null);
    }

    private static void closeQuietly(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Best effort.
            }
        }
    }

    private final class StreamingOperation {
        private final ChatClientRequest request;

        private final RunCancellation cancellation;

        private final AtomicBoolean terminated = new AtomicBoolean();

        private final AtomicReference<InputStream> input = new AtomicReference<>();

        private final AtomicReference<CompletableFuture<HttpResponse<InputStream>>> call = new AtomicReference<>();

        private final AtomicReference<RunCancellationRegistration> registration = new AtomicReference<>();

        private boolean permitHeld;

        private SingleSubscriberPublisher<ChatResponseUpdate> sink;

        private StreamingOperation(ChatClientRequest request, RunCancellation cancellation) {
            this.request = request;
            this.cancellation = cancellation;
        }

        private void start() {
            if (closed.get()) {
                fail(failure("transport_closed"));
                return;
            }
            if (cancellation.isCancellationRequested()) {
                fail(new RunCancelledException());
                return;
            }
            if (!permits.tryAcquire()) {
                fail(failure("concurrency_limit"));
                return;
            }
            permitHeld = true;
            registration.set(RunCancellations.register(cancellation, this::cancelFromSignal));
            byte[] body;
            try {
                body = documentCodec.write(OllamaMessageMapper.request(request, options, documentCodec, true));
            } catch (RuntimeException exception) {
                fail(exception);
                return;
            }
            CompletableFuture<HttpResponse<InputStream>> future =
                    httpClient.sendAsync(httpRequest(body), HttpResponse.BodyHandlers.ofInputStream());
            call.set(future);
            future.whenCompleteAsync(
                    (response, callFailure) -> {
                        if (callFailure != null) {
                            fail(normalize(callFailure, cancellation));
                            return;
                        }
                        input.set(response.body());
                        String requestId = requestId(response);
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            fail(new OllamaProviderException("http_error", response.statusCode(), requestId));
                            return;
                        }
                        executor.execute(() -> readEvents(response.body(), requestId));
                    },
                    executor);
        }

        private void readEvents(InputStream stream, String requestId) {
            OllamaMessageMapper.StreamAssembler assembler =
                    new OllamaMessageMapper.StreamAssembler(eventCodec, requestId);
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8), 8 * 1024)) {
                while (!terminated.get()) {
                    String line = readLineBounded(reader, options.maxEventBytes());
                    if (line == null) {
                        sink.emit(assembler.finish());
                        complete();
                        return;
                    }
                    if (line.isBlank()) {
                        continue;
                    }
                    for (ChatResponseUpdate update :
                            assembler.accept(eventCodec.parse(line.getBytes(StandardCharsets.UTF_8)))) {
                        sink.emit(update);
                    }
                }
            } catch (RuntimeException | IOException exception) {
                fail(
                        exception instanceof RuntimeException runtime
                                ? normalize(runtime, cancellation)
                                : failure("stream_io"));
            }
        }

        private String readLineBounded(BufferedReader reader, int maximumCharacters) throws IOException {
            StringBuilder line = new StringBuilder(Math.min(256, maximumCharacters));
            int character;
            while ((character = reader.read()) != -1) {
                if (character == '\n') {
                    break;
                }
                if (character != '\r') {
                    if (line.length() >= maximumCharacters) {
                        throw failure("event_too_large");
                    }
                    line.append((char) character);
                }
            }
            return character == -1 && line.isEmpty() ? null : line.toString();
        }

        private void cancelFromSignal() {
            CompletableFuture<?> future = call.get();
            if (future != null) {
                future.cancel(true);
            }
            closeQuietly(input.get());
            fail(new RunCancelledException());
        }

        private void cancel() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            cancellation.cancel();
            CompletableFuture<?> future = call.get();
            if (future != null) {
                future.cancel(true);
            }
            cleanup();
        }

        private void complete() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            sink.complete();
            cleanup();
        }

        private void fail(RuntimeException exception) {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            sink.fail(exception);
            cleanup();
        }

        private void cleanup() {
            closeQuietly(input.getAndSet(null));
            RunCancellationRegistration current = registration.getAndSet(null);
            if (current != null) {
                current.close();
            }
            if (permitHeld) {
                permitHeld = false;
                permits.release();
            }
        }
    }
}
