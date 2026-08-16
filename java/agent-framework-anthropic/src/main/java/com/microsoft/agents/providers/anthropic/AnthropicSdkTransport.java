// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.anthropic;

import com.anthropic.client.AnthropicClientAsync;
import com.anthropic.client.AnthropicClientAsyncImpl;
import com.anthropic.core.ClientOptions;
import com.anthropic.core.http.HttpResponseFor;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.internal.SingleSubscriberPublisher;
import com.microsoft.agents.core.internal.StrictJsonCodec;
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

final class AnthropicSdkTransport implements AnthropicTransport {
    private final AnthropicChatClientOptions options;

    private final AnthropicClientAsync client;

    private final boolean ownsClient;

    private final ExecutorService executor;

    private final Semaphore permits;

    private final StrictJsonCodec eventJson;

    private final AtomicBoolean closed = new AtomicBoolean();

    private AnthropicSdkTransport(
            AnthropicChatClientOptions options,
            AnthropicClientAsync client,
            boolean ownsClient,
            ExecutorService executor) {
        this.options = options;
        this.client = client;
        this.ownsClient = ownsClient;
        this.executor = executor;
        permits = new Semaphore(options.maxConcurrentRequests());
        eventJson = new StrictJsonCodec(
                options.maxEventBytes(),
                options.maxEventBytes(),
                options.maxNestingDepth(),
                options.maxStringLength(),
                1_000,
                options.maxCollectionEntries());
    }

    static AnthropicSdkTransport create(AnthropicChatClientOptions options, ExecutorService suppliedExecutor) {
        Objects.requireNonNull(options, "options");
        if (!options.hasApiKey()) {
            throw new IllegalArgumentException("Default Anthropic transport requires an API key.");
        }
        ExecutorService executor =
                suppliedExecutor == null ? Executors.newVirtualThreadPerTaskExecutor() : suppliedExecutor;
        boolean ownsExecutor = suppliedExecutor == null;
        ExecutorService sdkStreamExecutor = Executors.newVirtualThreadPerTaskExecutor();
        AnthropicJdkHttpClient httpClient = new AnthropicJdkHttpClient(options, executor, ownsExecutor);
        ClientOptions clientOptions = ClientOptions.builder()
                .httpClient(httpClient)
                .baseUrl(options.endpoint().toString())
                .putHeader("x-api-key", options.apiKey().value())
                .putHeader("anthropic-version", "2023-06-01")
                .timeout(options.timeout())
                .maxRetries(options.maxRetries())
                .logLevel(com.anthropic.core.LogLevel.OFF)
                .streamHandlerExecutor(sdkStreamExecutor)
                .responseValidation(true)
                .build();
        return new AnthropicSdkTransport(options, new AnthropicClientAsyncImpl(clientOptions), true, executor);
    }

    AnthropicSdkTransport(
            AnthropicChatClientOptions options,
            AnthropicClientAsync client,
            boolean ownsClient,
            ExecutorService executor,
            @SuppressWarnings("unused") boolean testing) {
        this(options, client, ownsClient, executor);
    }

    @Override
    public CompletionStage<ChatResponse> completeAsync(
            ChatClientRequest request, AnthropicChatClientOptions ignored, RunCancellation cancellation) {
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
        CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        CompletableFuture<HttpResponseFor<Message>> call;
        try {
            call = client.withRawResponse().messages().create(AnthropicMapper.request(request, options));
        } catch (RuntimeException exception) {
            permits.release();
            return CompletableFuture.failedFuture(mapFailure(exception, cancellation));
        }
        RunCancellationRegistration registration = RunCancellations.register(cancellation, () -> {
            call.cancel(true);
            result.completeExceptionally(new RunCancelledException());
        });
        call.whenCompleteAsync(
                (raw, callFailure) -> {
                    try {
                        if (callFailure != null) {
                            result.completeExceptionally(mapFailure(callFailure, cancellation));
                            return;
                        }
                        try (raw) {
                            result.complete(AnthropicMapper.response(
                                    raw.parse(), raw.requestId().orElse(null)));
                        }
                    } catch (RuntimeException exception) {
                        result.completeExceptionally(mapFailure(exception, cancellation));
                    } finally {
                        registration.close();
                        permits.release();
                    }
                },
                executor);
        return result;
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> completeStreaming(
            ChatClientRequest request, AnthropicChatClientOptions ignored, RunCancellation cancellation) {
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
        if (closed.compareAndSet(false, true) && ownsClient) {
            client.close();
        }
    }

    private static RuntimeException mapFailure(Throwable failure, RunCancellation cancellation) {
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
        if (current instanceof AnthropicServiceException service) {
            String requestId =
                    service.headers().values("request-id").stream().findFirst().orElse(null);
            String code = service.errorType().map(Object::toString).orElse(null);
            return new AnthropicProviderException("service_error", service.statusCode(), requestId, code);
        }
        return failure("sdk_" + current.getClass().getSimpleName());
    }

    private static AnthropicProviderException failure(String kind) {
        return new AnthropicProviderException(kind, null, null, null);
    }

    private final class StreamingOperation {
        private final ChatClientRequest request;

        private final RunCancellation cancellation;

        private final AtomicBoolean terminated = new AtomicBoolean();

        private final AtomicReference<CompletableFuture<?>> call = new AtomicReference<>();

        private final AtomicReference<HttpResponseFor<StreamResponse<RawMessageStreamEvent>>> raw =
                new AtomicReference<>();

        private final AtomicReference<StreamResponse<RawMessageStreamEvent>> stream = new AtomicReference<>();

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
            CompletableFuture<HttpResponseFor<StreamResponse<RawMessageStreamEvent>>> future;
            try {
                future = client.withRawResponse().messages().createStreaming(AnthropicMapper.request(request, options));
            } catch (RuntimeException exception) {
                fail(mapFailure(exception, cancellation));
                return;
            }
            call.set(future);
            future.whenCompleteAsync(
                    (response, callFailure) -> {
                        if (callFailure != null) {
                            fail(mapFailure(callFailure, cancellation));
                            return;
                        }
                        raw.set(response);
                        String requestId = response.requestId().orElse(null);
                        try {
                            StreamResponse<RawMessageStreamEvent> parsed = response.parse();
                            stream.set(parsed);
                            executor.execute(() -> consume(parsed, requestId));
                        } catch (RuntimeException exception) {
                            fail(mapFailure(exception, cancellation));
                        }
                    },
                    executor);
        }

        private void consume(StreamResponse<RawMessageStreamEvent> response, String requestId) {
            AnthropicMapper.StreamAssembler assembler = new AnthropicMapper.StreamAssembler(eventJson, requestId);
            try (response;
                    var events = response.stream()) {
                events.forEach(event -> {
                    if (terminated.get()) {
                        return;
                    }
                    for (ChatResponseUpdate update : assembler.accept(event)) {
                        sink.emit(update);
                    }
                });
                assembler.requireTerminal();
                complete();
            } catch (RuntimeException exception) {
                fail(mapFailure(exception, cancellation));
            }
        }

        private void cancelFromSignal() {
            CompletableFuture<?> future = call.get();
            if (future != null) {
                future.cancel(true);
            }
            closeStream();
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
            closeStream();
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

        private void closeStream() {
            StreamResponse<RawMessageStreamEvent> currentStream = stream.getAndSet(null);
            if (currentStream != null) {
                currentStream.close();
            }
            HttpResponseFor<StreamResponse<RawMessageStreamEvent>> currentRaw = raw.getAndSet(null);
            if (currentRaw != null) {
                currentRaw.close();
            }
        }

        private void cleanup() {
            closeStream();
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
