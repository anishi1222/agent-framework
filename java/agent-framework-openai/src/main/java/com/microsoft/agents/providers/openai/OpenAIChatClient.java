// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

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
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implements provider-neutral chat completion through the OpenAI Responses API.
 *
 * <p>The client is safe for concurrent requests. Streaming publishers are cold, bounded, and
 * single-subscriber. Closing the client cancels active operations and closes only a transport whose
 * ownership was assigned to this client.
 */
public final class OpenAIChatClient implements ChatClient {
    private final OpenAIChatClientOptions options;

    private final OpenAITransport transport;

    private final boolean ownsTransport;

    private final AtomicBoolean closed = new AtomicBoolean();

    private final Set<RunCancellation> activeCancellations = ConcurrentHashMap.newKeySet();

    private OpenAIChatClient(OpenAIChatClientOptions options, OpenAITransport transport, boolean ownsTransport) {
        this.options = options;
        this.transport = transport;
        this.ownsTransport = ownsTransport;
    }

    /**
     * Creates an OpenAI client builder.
     *
     * @return client builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the immutable client options.
     *
     * @return client options
     */
    public OpenAIChatClientOptions options() {
        return options;
    }

    @Override
    public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        if (closed.get()) {
            return CompletableFuture.failedFuture(closedFailure());
        }
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }

        OpenAITransport.Request mappedRequest;
        CompletionStage<OpenAITransport.Response> providerStage;
        try {
            mappedRequest = OpenAIRequestMapper.map(request, options, options.responseOptions());
            providerStage = Objects.requireNonNull(
                    transport.completeAsync(mappedRequest, cancellation),
                    "OpenAITransport.completeAsync returned null.");
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(normalizeFailure(failure));
        }

        activeCancellations.add(cancellation);
        if (closed.get()) {
            activeCancellations.remove(cancellation);
            cancellation.cancel();
            cancelStage(providerStage);
            return CompletableFuture.failedFuture(closedFailure());
        }
        CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        AtomicReference<RunCancellationRegistration> registration = new AtomicReference<>();
        registration.set(RunCancellations.register(cancellation, () -> {
            cancelStage(providerStage);
            result.completeExceptionally(new RunCancelledException());
        }));
        providerStage.whenComplete((response, failure) -> {
            try {
                if (failure != null) {
                    result.completeExceptionally(normalizeFailure(failure));
                } else if (response == null) {
                    result.completeExceptionally(new OpenAISdkException("null_response"));
                } else {
                    result.complete(OpenAIResponseMapper.map(response));
                }
            } catch (RuntimeException mappingFailure) {
                result.completeExceptionally(normalizeFailure(mappingFailure));
            } finally {
                activeCancellations.remove(cancellation);
                closeRegistration(registration.get());
            }
        });
        return result;
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> completeStreaming(
            ChatClientRequest request, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        if (closed.get()) {
            return failedPublisher(closedFailure());
        }

        StreamingRun run = new StreamingRun(request, cancellation);
        SingleSubscriberPublisher<ChatResponseUpdate> publisher = new SingleSubscriberPublisher<>(
                run::start,
                run::cancelFromSubscriber,
                options.maxBufferedUpdates(),
                OpenAIStreamingBufferOverflowException::new);
        run.publisher(publisher);
        return publisher;
    }

    /**
     * Cancels active operations and releases an owned transport.
     *
     * <p>An injected transport is caller-owned unless {@link Builder#transport(OpenAITransport,
     * boolean)} explicitly transfers ownership.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        activeCancellations.forEach(RunCancellation::cancel);
        activeCancellations.clear();
        if (ownsTransport) {
            try {
                transport.close();
            } catch (RuntimeException failure) {
                throw normalizeFailure(failure);
            }
        }
    }

    private static Flow.Publisher<ChatResponseUpdate> failedPublisher(RuntimeException failure) {
        AtomicReference<SingleSubscriberPublisher<ChatResponseUpdate>> reference = new AtomicReference<>();
        SingleSubscriberPublisher<ChatResponseUpdate> publisher =
                new SingleSubscriberPublisher<>(() -> reference.get().fail(failure), () -> {}, 1);
        reference.set(publisher);
        return publisher;
    }

    private static RuntimeException closedFailure() {
        return new OpenAISdkException("client_closed");
    }

    private static void cancelStage(CompletionStage<?> stage) {
        try {
            stage.toCompletableFuture().cancel(true);
        } catch (RuntimeException ignored) {
            // The cancellation signal remains authoritative for stages that cannot be cancelled.
        }
    }

    private static void closeRegistration(RunCancellationRegistration registration) {
        if (registration != null) {
            registration.close();
        }
    }

    private static RuntimeException normalizeFailure(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof RuntimeException runtime) {
            if (runtime instanceof AgentFrameworkException) {
                return runtime;
            }
            return OpenAIErrorMapper.map(runtime);
        }
        return new OpenAISdkException("transport_error");
    }

    /** Builds immutable {@link OpenAIChatClient} instances. */
    public static final class Builder {
        private OpenAIChatClientOptions options;

        private OpenAITransport transport;

        private boolean closeTransport;

        private Builder() {}

        /**
         * Sets the required immutable client options.
         *
         * @param options client options
         * @return this builder
         */
        public Builder options(OpenAIChatClientOptions options) {
            this.options = Objects.requireNonNull(options, "options");
            return this;
        }

        /**
         * Injects a caller-owned transport.
         *
         * <p>This overload does not transfer ownership. It is intended for deterministic tests and
         * custom network boundaries.
         *
         * @param transport transport boundary
         * @return this builder
         */
        public Builder transport(OpenAITransport transport) {
            return transport(transport, false);
        }

        /**
         * Injects a transport and selects whether the client closes it.
         *
         * @param transport transport boundary
         * @param closeTransport whether ownership transfers to the client
         * @return this builder
         */
        public Builder transport(OpenAITransport transport, boolean closeTransport) {
            this.transport = Objects.requireNonNull(transport, "transport");
            this.closeTransport = closeTransport;
            return this;
        }

        /**
         * Creates a configured OpenAI chat client.
         *
         * @return OpenAI chat client
         */
        public OpenAIChatClient build() {
            OpenAIChatClientOptions builtOptions = Objects.requireNonNull(options, "options");
            if (transport == null) {
                return new OpenAIChatClient(builtOptions, OpenAISdkTransport.create(builtOptions), true);
            }
            return new OpenAIChatClient(builtOptions, transport, closeTransport);
        }
    }

    private final class StreamingRun implements Flow.Subscriber<OpenAITransport.StreamEvent> {
        private final ChatClientRequest request;

        private final RunCancellation cancellation;

        private final OpenAIResponseMapper.StreamMapper mapper = new OpenAIResponseMapper.StreamMapper();

        private final AtomicReference<Flow.Subscription> upstream = new AtomicReference<>();

        private final AtomicReference<RunCancellationRegistration> registration = new AtomicReference<>();

        private final AtomicBoolean terminated = new AtomicBoolean();

        private SingleSubscriberPublisher<ChatResponseUpdate> sink;

        private StreamingRun(ChatClientRequest request, RunCancellation cancellation) {
            this.request = request;
            this.cancellation = cancellation;
        }

        private void publisher(SingleSubscriberPublisher<ChatResponseUpdate> publisher) {
            sink = publisher;
        }

        private void start() {
            if (closed.get()) {
                fail(closedFailure());
                return;
            }
            activeCancellations.add(cancellation);
            registration.set(RunCancellations.register(cancellation, this::cancelFromSignal));
            if (closed.get()) {
                fail(closedFailure());
                cancellation.cancel();
                return;
            }
            if (terminated.get()) {
                return;
            }
            try {
                OpenAITransport.Request mapped = OpenAIRequestMapper.map(request, options, options.responseOptions());
                Flow.Publisher<OpenAITransport.StreamEvent> providerPublisher = Objects.requireNonNull(
                        transport.completeStreaming(mapped, cancellation),
                        "OpenAITransport.completeStreaming returned null.");
                providerPublisher.subscribe(this);
            } catch (RuntimeException failure) {
                fail(normalizeFailure(failure));
            }
        }

        private void cancelFromSignal() {
            Flow.Subscription subscription = upstream.get();
            if (subscription != null) {
                subscription.cancel();
            }
            fail(new RunCancelledException());
        }

        private void cancelFromSubscriber() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            cancellation.cancel();
            Flow.Subscription subscription = upstream.get();
            if (subscription != null) {
                subscription.cancel();
            }
            cleanup();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            Objects.requireNonNull(subscription, "subscription");
            if (!upstream.compareAndSet(null, subscription)) {
                subscription.cancel();
                return;
            }
            if (terminated.get() || cancellation.isCancellationRequested() || closed.get()) {
                subscription.cancel();
                fail(cancellation.isCancellationRequested() ? new RunCancelledException() : closedFailure());
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(OpenAITransport.StreamEvent event) {
            if (terminated.get()) {
                return;
            }
            try {
                for (ChatResponseUpdate update : mapper.map(Objects.requireNonNull(event, "event"))) {
                    sink.emit(update);
                }
            } catch (RuntimeException failure) {
                Flow.Subscription subscription = upstream.get();
                if (subscription != null) {
                    subscription.cancel();
                }
                fail(normalizeFailure(failure));
            }
        }

        @Override
        public void onError(Throwable failure) {
            fail(normalizeFailure(failure));
        }

        @Override
        public void onComplete() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            try {
                mapper.requireTerminal();
                sink.complete();
            } catch (RuntimeException failure) {
                sink.fail(normalizeFailure(failure));
            } finally {
                cleanup();
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
            activeCancellations.remove(cancellation);
            closeRegistration(registration.getAndSet(null));
        }
    }
}
