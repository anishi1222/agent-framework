// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.anthropic;

import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.AgentFrameworkException;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implements provider-neutral chat through the official Anthropic Java Messages SDK.
 */
public final class AnthropicChatClient implements ChatClient {
    private final AnthropicChatClientOptions options;

    private final AnthropicTransport transport;

    private final boolean ownsTransport;

    private final AtomicBoolean closed = new AtomicBoolean();

    private final Set<RunCancellation> active = ConcurrentHashMap.newKeySet();

    private AnthropicChatClient(
            AnthropicChatClientOptions options, AnthropicTransport transport, boolean ownsTransport) {
        this.options = options;
        this.transport = transport;
        this.ownsTransport = ownsTransport;
    }

    /** Creates a client builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns immutable options. */
    public AnthropicChatClientOptions options() {
        return options;
    }

    /** Returns implemented capability flags. */
    public AnthropicCapabilities capabilities() {
        return AnthropicCapabilities.current();
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
            AnthropicMapper.validate(request);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        CompletionStage<ChatResponse> stage;
        try {
            stage = Objects.requireNonNull(
                    transport.completeAsync(request, options, cancellation),
                    "AnthropicTransport.completeAsync returned null.");
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(normalize(exception));
        }
        active.add(cancellation);
        CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        AtomicReference<RunCancellationRegistration> registration = new AtomicReference<>();
        registration.set(RunCancellations.register(cancellation, () -> {
            cancelStage(stage);
            result.completeExceptionally(new RunCancelledException());
        }));
        stage.whenComplete((response, stageFailure) -> {
            try {
                if (stageFailure != null) {
                    result.completeExceptionally(normalize(stageFailure));
                } else if (response == null) {
                    result.completeExceptionally(failure("null_response"));
                } else {
                    result.complete(response);
                }
            } finally {
                active.remove(cancellation);
                closeRegistration(registration.getAndSet(null));
            }
        });
        return result;
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> completeStreaming(
            ChatClientRequest request, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        try {
            AnthropicMapper.validate(request);
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

    private static void cancelStage(CompletionStage<?> stage) {
        try {
            stage.toCompletableFuture().cancel(true);
        } catch (RuntimeException ignored) {
            // The explicit run cancellation remains authoritative.
        }
    }

    private static RuntimeException normalize(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof AgentFrameworkException framework) {
            return framework;
        }
        return failure("transport_error");
    }

    private static AnthropicProviderException failure(String kind) {
        return new AnthropicProviderException(kind, null, null, null);
    }

    private static void closeRegistration(RunCancellationRegistration registration) {
        if (registration != null) {
            registration.close();
        }
    }

    private static Flow.Publisher<ChatResponseUpdate> failedPublisher(RuntimeException failure) {
        AtomicReference<SingleSubscriberPublisher<ChatResponseUpdate>> reference = new AtomicReference<>();
        SingleSubscriberPublisher<ChatResponseUpdate> publisher =
                new SingleSubscriberPublisher<>(() -> reference.get().fail(failure), () -> {}, 1);
        reference.set(publisher);
        return publisher;
    }

    /** Builds immutable {@link AnthropicChatClient} instances. */
    public static final class Builder {
        private AnthropicChatClientOptions options;

        private AnthropicTransport transport;

        private boolean closeTransport;

        private ExecutorService executor;

        private Builder() {}

        /** Sets required immutable options. */
        public Builder options(AnthropicChatClientOptions options) {
            this.options = Objects.requireNonNull(options, "options");
            return this;
        }

        /** Injects a caller-owned framework transport. */
        public Builder transport(AnthropicTransport transport) {
            return transport(transport, false);
        }

        /** Injects a transport and optionally transfers ownership. */
        public Builder transport(AnthropicTransport transport, boolean closeTransport) {
            this.transport = Objects.requireNonNull(transport, "transport");
            this.closeTransport = closeTransport;
            return this;
        }

        /** Injects a caller-owned execution and stream-consumption executor. */
        public Builder executor(ExecutorService executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /** Creates a configured client. */
        public AnthropicChatClient build() {
            AnthropicChatClientOptions builtOptions = Objects.requireNonNull(options, "options");
            if (transport != null && executor != null) {
                throw new IllegalStateException("transport cannot be combined with executor.");
            }
            if (transport != null) {
                return new AnthropicChatClient(builtOptions, transport, closeTransport);
            }
            return new AnthropicChatClient(builtOptions, AnthropicSdkTransport.create(builtOptions, executor), true);
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
                transport.completeStreaming(request, options, cancellation).subscribe(this);
            } catch (RuntimeException exception) {
                fail(normalize(exception));
            }
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (!upstream.compareAndSet(null, Objects.requireNonNull(subscription, "subscription"))) {
                subscription.cancel();
                return;
            }
            if (cancellation.isCancellationRequested() || closed.get()) {
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
            } else if (update.finishReason() != null) {
                terminal = update;
            } else {
                sink.emit(update);
            }
        }

        @Override
        public void onError(Throwable failure) {
            fail(normalize(failure));
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

        private void fail(RuntimeException failure) {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            sink.fail(failure);
            cleanup();
        }

        private void cleanup() {
            active.remove(cancellation);
            closeRegistration(registration.getAndSet(null));
        }
    }
}
