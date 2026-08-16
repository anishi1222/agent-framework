// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.gemini;

import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.errors.ApiException;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.internal.SingleSubscriberPublisher;
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
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

final class GeminiSdkTransport implements GeminiTransport {
    private final GeminiChatClientOptions options;

    private final Client client;

    private final boolean ownsClient;

    private final ExecutorService executor;

    private final boolean ownsExecutor;

    private final Semaphore permits;

    private final AtomicBoolean closed = new AtomicBoolean();

    private GeminiSdkTransport(
            GeminiChatClientOptions options,
            Client client,
            boolean ownsClient,
            ExecutorService executor,
            boolean ownsExecutor,
            Ownership ownership) {
        this.options = options;
        this.client = client;
        this.ownsClient = ownsClient;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        permits = new Semaphore(options.maxConcurrentRequests());
    }

    static GeminiSdkTransport create(GeminiChatClientOptions options) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Dispatcher dispatcher = new Dispatcher(executor);
        dispatcher.setMaxRequests(options.maxConcurrentRequests());
        dispatcher.setMaxRequestsPerHost(options.maxConcurrentRequests());
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(options.timeout())
                .connectTimeout(options.timeout())
                .readTimeout(options.timeout())
                .writeTimeout(options.timeout())
                .addInterceptor(new GeminiLimitsInterceptor(options))
                .build();
        Client.Builder builder = Client.builder()
                .httpOptions(HttpOptions.builder()
                        .baseUrl(options.endpoint().toString())
                        .timeout(Math.toIntExact(options.timeout().toMillis()))
                        .build())
                .clientOptions(ClientOptions.builder()
                        .customHttpClient(httpClient)
                        .maxConnections(options.maxConcurrentRequests())
                        .maxConnectionsPerHost(options.maxConcurrentRequests())
                        .build());
        if (options.authenticationMode() == GeminiAuthenticationMode.API_KEY) {
            builder.apiKey(options.apiKey().value()).vertexAI(false);
        } else {
            builder.vertexAI(true).project(options.project()).location(options.location());
        }
        return new GeminiSdkTransport(options, builder.build(), true, executor, true, Ownership.INTERNAL);
    }

    GeminiSdkTransport(
            GeminiChatClientOptions options,
            Client client,
            boolean ownsClient,
            ExecutorService executor,
            @SuppressWarnings("unused") boolean testing) {
        this(options, client, ownsClient, executor, false, Ownership.INTERNAL);
    }

    GeminiSdkTransport(
            GeminiChatClientOptions options,
            Client client,
            boolean ownsClient,
            ExecutorService executor,
            boolean ownsExecutor,
            @SuppressWarnings("unused") boolean testing) {
        this(options, client, ownsClient, executor, ownsExecutor, Ownership.INTERNAL);
    }

    private enum Ownership {
        INTERNAL
    }

    @Override
    public CompletionStage<ChatResponse> completeAsync(
            ChatClientRequest request, GeminiChatClientOptions ignored, RunCancellation cancellation) {
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
        GeminiMapper.MappedRequest mapped;
        CompletableFuture<GenerateContentResponse> call;
        try {
            mapped = GeminiMapper.request(request, options);
            call = client.async.models.generateContent(mapped.model(), mapped.contents(), mapped.config());
        } catch (RuntimeException exception) {
            permits.release();
            return CompletableFuture.failedFuture(mapFailure(exception, cancellation));
        }
        CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        RunCancellationRegistration registration = RunCancellations.register(cancellation, () -> {
            call.cancel(true);
            result.completeExceptionally(new RunCancelledException());
        });
        call.whenComplete((response, callFailure) -> {
            try {
                if (callFailure != null) {
                    result.completeExceptionally(mapFailure(callFailure, cancellation));
                } else {
                    result.complete(GeminiMapper.response(response));
                }
            } catch (RuntimeException exception) {
                result.completeExceptionally(mapFailure(exception, cancellation));
            } finally {
                registration.close();
                permits.release();
            }
        });
        return result;
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> completeStreaming(
            ChatClientRequest request, GeminiChatClientOptions ignored, RunCancellation cancellation) {
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
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            if (ownsClient) {
                client.close();
            }
        } finally {
            if (ownsExecutor) {
                executor.close();
            }
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
        if (current instanceof ApiException api) {
            return new GeminiProviderException("service_error", api.code(), null);
        }
        return failure("sdk_error");
    }

    private static GeminiProviderException failure(String kind) {
        return new GeminiProviderException(kind, null, null);
    }

    private final class StreamingOperation {
        private final ChatClientRequest request;

        private final RunCancellation cancellation;

        private final AtomicBoolean terminated = new AtomicBoolean();

        private final AtomicReference<CompletableFuture<ResponseStream<GenerateContentResponse>>> call =
                new AtomicReference<>();

        private final AtomicReference<ResponseStream<GenerateContentResponse>> stream = new AtomicReference<>();

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
            GeminiMapper.MappedRequest mapped;
            CompletableFuture<ResponseStream<GenerateContentResponse>> future;
            try {
                mapped = GeminiMapper.request(request, options);
                future = client.async.models.generateContentStream(mapped.model(), mapped.contents(), mapped.config());
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
                        stream.set(response);
                        executor.execute(() -> consume(response));
                    },
                    executor);
        }

        private void consume(ResponseStream<GenerateContentResponse> response) {
            GeminiMapper.StreamAssembler assembler = new GeminiMapper.StreamAssembler();
            try (response) {
                for (GenerateContentResponse chunk : response) {
                    if (terminated.get()) {
                        return;
                    }
                    for (ChatResponseUpdate update : assembler.accept(chunk)) {
                        sink.emit(update);
                    }
                }
                sink.emit(assembler.finish());
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
            ResponseStream<GenerateContentResponse> current = stream.getAndSet(null);
            if (current != null) {
                current.close();
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
