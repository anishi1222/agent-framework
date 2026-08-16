// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.bedrock;

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
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.BedrockRuntimeException;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;

final class BedrockSdkTransport implements BedrockTransport {
    private final BedrockChatClientOptions options;

    private final SdkClient client;

    private final boolean ownsClient;

    private final Semaphore permits;

    private final StrictJsonCodec json;

    private final AtomicBoolean closed = new AtomicBoolean();

    BedrockSdkTransport(BedrockChatClientOptions options, SdkClient client, boolean ownsClient) {
        this.options = Objects.requireNonNull(options, "options");
        this.client = Objects.requireNonNull(client, "client");
        this.ownsClient = ownsClient;
        permits = new Semaphore(options.maxConcurrentRequests());
        json = new StrictJsonCodec(
                options.maxRequestBytes(),
                options.maxEventBytes(),
                options.maxNestingDepth(),
                options.maxStringLength(),
                1_000,
                options.maxCollectionEntries());
    }

    static BedrockSdkTransport create(BedrockChatClientOptions options) {
        ClientOverrideConfiguration override = ClientOverrideConfiguration.builder()
                .apiCallTimeout(options.timeout())
                .apiCallAttemptTimeout(options.timeout())
                .retryStrategy(builder -> builder.maxAttempts(options.maxAttempts()))
                .putHeader("User-Agent", "agent-framework-java/bedrock")
                .build();
        var builder = BedrockRuntimeAsyncClient.builder()
                .region(Region.of(options.region()))
                .overrideConfiguration(override)
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .maxConcurrency(options.maxConcurrentRequests())
                        .readTimeout(options.timeout())
                        .writeTimeout(options.timeout())
                        .connectionTimeout(options.timeout()));
        options.endpointOverride().ifPresent(builder::endpointOverride);
        return new BedrockSdkTransport(options, new AwsSdkClient(builder.build()), true);
    }

    BedrockSdkTransport(
            BedrockChatClientOptions options,
            BedrockRuntimeAsyncClient client,
            boolean ownsClient,
            @SuppressWarnings("unused") boolean testing) {
        this(options, new AwsSdkClient(client), ownsClient);
    }

    @Override
    public CompletionStage<ChatResponse> completeAsync(
            ChatClientRequest request, BedrockChatClientOptions ignored, RunCancellation cancellation) {
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
        CompletableFuture<ConverseResponse> call;
        try {
            call = client.converse(BedrockMapper.request(request, options, json));
        } catch (RuntimeException exception) {
            permits.release();
            return CompletableFuture.failedFuture(mapFailure(exception, cancellation));
        }
        RunCancellationRegistration registration = RunCancellations.register(cancellation, () -> {
            call.cancel(true);
            result.completeExceptionally(new RunCancelledException());
        });
        call.whenComplete((response, callFailure) -> {
            try {
                if (callFailure != null) {
                    result.completeExceptionally(mapFailure(callFailure, cancellation));
                } else {
                    enforceDeclaredLength(response);
                    ChatResponse mapped = BedrockMapper.response(response);
                    new BedrockMappedPayloadBudget(options.maxResponseBytes(), options.maxEventBytes())
                            .acceptResponse(mapped);
                    result.complete(mapped);
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
            ChatClientRequest request, BedrockChatClientOptions ignored, RunCancellation cancellation) {
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
        if (current instanceof BedrockRuntimeException service) {
            String code = service.awsErrorDetails() == null
                    ? null
                    : service.awsErrorDetails().errorCode();
            return new BedrockProviderException("service_error", service.statusCode(), service.requestId(), code);
        }
        return new BedrockProviderException(
                "sdk_error", null, null, current.getClass().getSimpleName());
    }

    private static BedrockProviderException failure(String kind) {
        return new BedrockProviderException(kind, null, null, null);
    }

    private void enforceDeclaredLength(SdkResponse response) {
        if (response == null || response.sdkHttpResponse() == null) {
            return;
        }
        response.sdkHttpResponse().firstMatchingHeader("Content-Length").ifPresent(value -> {
            final long declared;
            try {
                declared = Long.parseLong(value);
            } catch (NumberFormatException exception) {
                throw failure("invalid_content_length");
            }
            if (declared < 0) {
                throw failure("invalid_content_length");
            }
            if (declared > options.maxResponseBytes()) {
                throw failure("declared_response_too_large");
            }
        });
    }

    interface SdkClient extends AutoCloseable {
        CompletableFuture<ConverseResponse> converse(ConverseRequest request);

        CompletableFuture<Void> converseStream(ConverseStreamRequest request, ConverseStreamResponseHandler handler);

        @Override
        void close();
    }

    private record AwsSdkClient(BedrockRuntimeAsyncClient delegate) implements SdkClient {
        private AwsSdkClient {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public CompletableFuture<ConverseResponse> converse(ConverseRequest request) {
            return delegate.converse(request);
        }

        @Override
        public CompletableFuture<Void> converseStream(
                ConverseStreamRequest request, ConverseStreamResponseHandler handler) {
            return delegate.converseStream(request, handler);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private final class StreamingOperation {
        private final ChatClientRequest request;

        private final RunCancellation cancellation;

        private final AtomicBoolean terminated = new AtomicBoolean();

        private final AtomicReference<CompletableFuture<Void>> call = new AtomicReference<>();

        private final AtomicReference<RunCancellationRegistration> registration = new AtomicReference<>();

        private final AtomicReference<String> requestId = new AtomicReference<>();

        private final AtomicReference<BedrockMapper.StreamAssembler> assembler = new AtomicReference<>();

        private final BedrockMappedPayloadBudget payloadBudget =
                new BedrockMappedPayloadBudget(options.maxResponseBytes(), options.maxEventBytes());

        private final AtomicBoolean eventStreamComplete = new AtomicBoolean();

        private final AtomicBoolean sdkCallComplete = new AtomicBoolean();

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
            ConverseStreamResponseHandler.Visitor visitor = ConverseStreamResponseHandler.Visitor.builder()
                    .onMessageStart(event -> assembler().onMessageStart(event))
                    .onContentBlockStart(event -> emit(assembler().onStart(event)))
                    .onContentBlockDelta(event -> emit(assembler().onDelta(event)))
                    .onContentBlockStop(event -> emit(assembler().onStop(event)))
                    .onMessageStop(event -> assembler().onMessageStop(event))
                    .onMetadata(event -> assembler().onMetadata(event))
                    .onDefault(event -> {
                        throw failure("unknown_stream_event");
                    })
                    .build();
            ConverseStreamResponseHandler handler = ConverseStreamResponseHandler.builder()
                    .onResponse(this::onResponse)
                    .onError(error -> fail(mapFailure(error, cancellation)))
                    .onComplete(this::onComplete)
                    .subscriber(() -> visitorSubscriber(visitor))
                    .build();
            CompletableFuture<Void> future;
            try {
                future = client.converseStream(BedrockMapper.streamRequest(request, options, json), handler);
            } catch (RuntimeException exception) {
                fail(mapFailure(exception, cancellation));
                return;
            }
            call.set(future);
            future.whenComplete((unused, failure) -> {
                if (failure != null) {
                    fail(mapFailure(failure, cancellation));
                } else {
                    sdkCallComplete.set(true);
                    tryComplete();
                }
            });
        }

        private void onResponse(ConverseStreamResponse response) {
            enforceDeclaredLength(response);
            String id = response.responseMetadata() == null
                    ? null
                    : response.responseMetadata().requestId();
            requestId.set(id);
            assembler.compareAndSet(null, new BedrockMapper.StreamAssembler(id, json));
        }

        private Subscriber<ConverseStreamOutput> visitorSubscriber(ConverseStreamResponseHandler.Visitor visitor) {
            return new Subscriber<>() {
                @Override
                public void onSubscribe(Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(ConverseStreamOutput event) {
                    event.accept(visitor);
                }

                @Override
                public void onError(Throwable throwable) {
                    fail(mapFailure(throwable, cancellation));
                }

                @Override
                public void onComplete() {
                    // The SDK invokes the response handler's onComplete callback after this signal.
                }
            };
        }

        private BedrockMapper.StreamAssembler assembler() {
            BedrockMapper.StreamAssembler current = assembler.get();
            if (current != null) {
                return current;
            }
            BedrockMapper.StreamAssembler created = new BedrockMapper.StreamAssembler(requestId.get(), json);
            assembler.compareAndSet(null, created);
            return assembler.get();
        }

        private void onComplete() {
            eventStreamComplete.set(true);
            tryComplete();
        }

        private void emit(java.util.List<ChatResponseUpdate> updates) {
            for (ChatResponseUpdate update : updates) {
                payloadBudget.acceptEvent(update);
                sink.emit(update);
            }
        }

        private void tryComplete() {
            if (terminated.get() || !eventStreamComplete.get() || !sdkCallComplete.get()) {
                return;
            }
            try {
                ChatResponseUpdate terminalUpdate = assembler().terminal();
                payloadBudget.acceptEvent(terminalUpdate);
                sink.emit(terminalUpdate);
                complete();
            } catch (RuntimeException exception) {
                fail(mapFailure(exception, cancellation));
            }
        }

        private void cancelFromSignal() {
            CompletableFuture<Void> future = call.get();
            if (future != null) {
                future.cancel(true);
            }
            fail(new RunCancelledException());
        }

        private void cancel() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            cancellation.cancel();
            CompletableFuture<Void> future = call.get();
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
