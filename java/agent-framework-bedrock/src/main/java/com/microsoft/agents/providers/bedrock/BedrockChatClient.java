// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.bedrock;

import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.internal.SingleSubscriberPublisher;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implements provider-neutral chat through AWS SDK v2 Bedrock Runtime Converse APIs.
 */
public final class BedrockChatClient implements ChatClient {
    private final BedrockChatClientOptions options;

    private final BedrockTransport transport;

    private final boolean ownsTransport;

    private final AtomicBoolean closed = new AtomicBoolean();

    private final Set<RunCancellation> active = ConcurrentHashMap.newKeySet();

    private BedrockChatClient(BedrockChatClientOptions options, BedrockTransport transport, boolean ownsTransport) {
        this.options = options;
        this.transport = transport;
        this.ownsTransport = ownsTransport;
    }

    /** Creates a client builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns immutable options. */
    public BedrockChatClientOptions options() {
        return options;
    }

    /** Returns implemented capability flags. */
    public BedrockCapabilities capabilities() {
        return BedrockCapabilities.current();
    }

    @Override
    public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        if (closed.get()) {
            return CompletableFuture.failedFuture(failure("client_closed"));
        }
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        try {
            BedrockMapper.validate(request, options);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        active.add(cancellation);
        CompletionStage<ChatResponse> stage;
        try {
            stage = Objects.requireNonNull(
                    transport.completeAsync(request, options, cancellation),
                    "BedrockTransport.completeAsync returned null.");
        } catch (RuntimeException exception) {
            active.remove(cancellation);
            return CompletableFuture.failedFuture(exception);
        }
        stage.whenComplete((response, failure) -> active.remove(cancellation));
        return stage;
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> completeStreaming(
            ChatClientRequest request, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        if (closed.get()) {
            return failedPublisher(failure("client_closed"));
        }
        try {
            BedrockMapper.validate(request, options);
        } catch (RuntimeException exception) {
            return failedPublisher(exception);
        }
        StreamingRun run = new StreamingRun(request, cancellation);
        SingleSubscriberPublisher<ChatResponseUpdate> publisher = new SingleSubscriberPublisher<>(
                run::start,
                run::cancelFromSubscriber,
                options.maxBufferedUpdates(),
                limit -> failure("stream_buffer_overflow"));
        run.sink = publisher;
        return publisher;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        active.forEach(RunCancellation::cancel);
        active.clear();
        if (ownsTransport) {
            transport.close();
        }
    }

    private static BedrockProviderException failure(String kind) {
        return new BedrockProviderException(kind, null, null, null);
    }

    private static Flow.Publisher<ChatResponseUpdate> failedPublisher(RuntimeException failure) {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                private boolean done;

                @Override
                public void request(long count) {
                    if (!done) {
                        done = true;
                        subscriber.onError(failure);
                    }
                }

                @Override
                public void cancel() {
                    done = true;
                }
            });
        };
    }

    private static void closeRegistration(RunCancellationRegistration registration) {
        if (registration != null) {
            registration.close();
        }
    }

    /** Builds immutable {@link BedrockChatClient} instances. */
    public static final class Builder {
        private BedrockChatClientOptions options;

        private BedrockTransport transport;

        private boolean closeTransport;

        private Builder() {}

        /** Sets required immutable options. */
        public Builder options(BedrockChatClientOptions options) {
            this.options = Objects.requireNonNull(options, "options");
            return this;
        }

        /** Injects a caller-owned framework transport. */
        public Builder transport(BedrockTransport transport) {
            return transport(transport, false);
        }

        /** Injects a transport and optionally transfers ownership. */
        public Builder transport(BedrockTransport transport, boolean closeTransport) {
            this.transport = Objects.requireNonNull(transport, "transport");
            this.closeTransport = closeTransport;
            return this;
        }

        /** Creates a configured client using the SDK default credential chain when no transport is injected. */
        public BedrockChatClient build() {
            BedrockChatClientOptions builtOptions = Objects.requireNonNull(options, "options");
            if (transport != null) {
                return new BedrockChatClient(builtOptions, transport, closeTransport);
            }
            return new BedrockChatClient(builtOptions, BedrockSdkTransport.create(builtOptions), true);
        }
    }

    private final class StreamingRun implements Flow.Subscriber<ChatResponseUpdate> {
        private final ChatClientRequest request;

        private final RunCancellation cancellation;

        private final AtomicReference<Flow.Subscription> upstream = new AtomicReference<>();

        private final AtomicReference<RunCancellationRegistration> registration = new AtomicReference<>();

        private final AtomicBoolean terminated = new AtomicBoolean();

        private ChatResponseUpdate terminal;

        private SingleSubscriberPublisher<ChatResponseUpdate> sink;

        private StreamingRun(ChatClientRequest request, RunCancellation cancellation) {
            this.request = request;
            this.cancellation = cancellation;
        }

        private void start() {
            if (closed.get()) {
                fail(failure("client_closed"));
                return;
            }
            active.add(cancellation);
            registration.set(RunCancellations.register(cancellation, this::cancelFromSignal));
            try {
                Flow.Publisher<ChatResponseUpdate> publisher = Objects.requireNonNull(
                        transport.completeStreaming(request, options, cancellation),
                        "BedrockTransport.completeStreaming returned null.");
                publisher.subscribe(this);
            } catch (RuntimeException exception) {
                fail(exception);
            }
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (!upstream.compareAndSet(null, Objects.requireNonNull(subscription, "subscription"))) {
                subscription.cancel();
                return;
            }
            if (closed.get() || cancellation.isCancellationRequested()) {
                subscription.cancel();
                fail(cancellation.isCancellationRequested() ? new RunCancelledException() : failure("client_closed"));
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ChatResponseUpdate update) {
            Objects.requireNonNull(update, "update");
            if (terminal != null) {
                cancelUpstream();
                fail(failure("update_after_terminal"));
            } else if (update.finishReason() == null) {
                sink.emit(update);
            } else {
                terminal = update;
            }
        }

        @Override
        public void onError(Throwable failure) {
            fail(failure instanceof RuntimeException runtime ? runtime : BedrockChatClient.failure("stream_error"));
        }

        @Override
        public void onComplete() {
            if (terminal == null) {
                fail(failure("missing_terminal"));
                return;
            }
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            sink.emit(terminal);
            sink.complete();
            cleanup();
        }

        private void cancelFromSignal() {
            cancelUpstream();
            fail(new RunCancelledException());
        }

        private void cancelFromSubscriber() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            cancellation.cancel();
            cancelUpstream();
            cleanup();
        }

        private void cancelUpstream() {
            Flow.Subscription subscription = upstream.get();
            if (subscription != null) {
                subscription.cancel();
            }
        }

        private void fail(RuntimeException exception) {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            sink.fail(exception);
            cleanup();
        }

        private void cleanup() {
            active.remove(cancellation);
            closeRegistration(registration.getAndSet(null));
        }
    }
}
