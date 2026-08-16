// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.mistral;

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
import java.net.http.HttpClient;
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
 * Implements provider-neutral chat completion through the Mistral Chat Completions API.
 *
 * <p>The client is thread-safe. Finite and streaming calls use the same immutable request mapping.
 * Streaming is cold, bounded, single-subscriber, cancellation-propagating, and emits exactly one
 * framework terminal update.
 */
public final class MistralChatClient implements ChatClient {
    private final MistralChatClientOptions options;

    private final MistralTransport transport;

    private final boolean ownsTransport;

    private final AtomicBoolean closed = new AtomicBoolean();

    private final Set<RunCancellation> activeCancellations = ConcurrentHashMap.newKeySet();

    private MistralChatClient(MistralChatClientOptions options, MistralTransport transport, boolean ownsTransport) {
        this.options = options;
        this.transport = transport;
        this.ownsTransport = ownsTransport;
    }

    /** Creates a client builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns immutable client options. */
    public MistralChatClientOptions options() {
        return options;
    }

    /** Returns implemented provider capability flags. */
    public MistralCapabilities capabilities() {
        return MistralCapabilities.current();
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
            MistralRequestValidator.validate(request);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        CompletionStage<ChatResponse> providerStage;
        try {
            providerStage = Objects.requireNonNull(
                    transport.completeAsync(request, options, cancellation),
                    "MistralTransport.completeAsync returned null.");
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(normalize(exception));
        }
        activeCancellations.add(cancellation);
        CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        AtomicReference<RunCancellationRegistration> registration = new AtomicReference<>();
        registration.set(RunCancellations.register(cancellation, () -> {
            cancelStage(providerStage);
            result.completeExceptionally(new RunCancelledException());
        }));
        providerStage.whenComplete((response, providerFailure) -> {
            try {
                if (providerFailure != null) {
                    result.completeExceptionally(normalize(providerFailure));
                } else if (response == null) {
                    result.completeExceptionally(failure("null_response"));
                } else {
                    result.complete(response);
                }
            } finally {
                activeCancellations.remove(cancellation);
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
            MistralRequestValidator.validate(request);
        } catch (RuntimeException exception) {
            return failedPublisher(exception);
        }
        StreamingRun run = new StreamingRun(request, cancellation);
        SingleSubscriberPublisher<ChatResponseUpdate> publisher = new SingleSubscriberPublisher<>(
                run::start,
                run::cancelFromSubscriber,
                options.maxBufferedUpdates(),
                ignored -> failure("stream_buffer_overflow"));
        run.sink = publisher;
        return publisher;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        activeCancellations.forEach(RunCancellation::cancel);
        activeCancellations.clear();
        if (ownsTransport) {
            transport.close();
        }
    }

    private static Flow.Publisher<ChatResponseUpdate> failedPublisher(RuntimeException failure) {
        AtomicReference<SingleSubscriberPublisher<ChatResponseUpdate>> reference = new AtomicReference<>();
        SingleSubscriberPublisher<ChatResponseUpdate> publisher =
                new SingleSubscriberPublisher<>(() -> reference.get().fail(failure), () -> {}, 1);
        reference.set(publisher);
        return publisher;
    }

    private static void cancelStage(CompletionStage<?> stage) {
        try {
            stage.toCompletableFuture().cancel(true);
        } catch (RuntimeException ignored) {
            // RunCancellation remains authoritative when an external stage cannot be cancelled.
        }
    }

    private static void closeRegistration(RunCancellationRegistration registration) {
        if (registration != null) {
            registration.close();
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

    private static MistralProviderException failure(String kind) {
        return new MistralProviderException(kind, null, null, null);
    }

    /** Builds immutable {@link MistralChatClient} instances. */
    public static final class Builder {
        private MistralChatClientOptions options;

        private MistralTransport transport;

        private boolean closeTransport;

        private HttpClient httpClient;

        private ExecutorService executor;

        private Builder() {}

        /** Sets required immutable client options. */
        public Builder options(MistralChatClientOptions options) {
            this.options = Objects.requireNonNull(options, "options");
            return this;
        }

        /** Injects a caller-owned framework transport. */
        public Builder transport(MistralTransport transport) {
            return transport(transport, false);
        }

        /** Injects a transport and optionally transfers ownership. */
        public Builder transport(MistralTransport transport, boolean closeTransport) {
            this.transport = Objects.requireNonNull(transport, "transport");
            this.closeTransport = closeTransport;
            return this;
        }

        /**
         * Injects a caller-owned redirect-free JDK HTTP client.
         *
         * <p>The client is never closed. Its redirect policy must be {@link HttpClient.Redirect#NEVER}.
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
            return this;
        }

        /** Injects a caller-owned executor used for response parsing and stream reading. */
        public Builder executor(ExecutorService executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /** Creates the configured client. */
        public MistralChatClient build() {
            MistralChatClientOptions builtOptions = Objects.requireNonNull(options, "options");
            if (transport != null && (httpClient != null || executor != null)) {
                throw new IllegalStateException("transport cannot be combined with httpClient or executor.");
            }
            if (transport != null) {
                return new MistralChatClient(builtOptions, transport, closeTransport);
            }
            return new MistralChatClient(
                    builtOptions, MistralHttpTransport.create(builtOptions, httpClient, executor), true);
        }
    }

    private final class StreamingRun implements Flow.Subscriber<ChatResponseUpdate> {
        private final ChatClientRequest request;

        private final RunCancellation cancellation;

        private final AtomicReference<Flow.Subscription> upstream = new AtomicReference<>();

        private final AtomicReference<RunCancellationRegistration> registration = new AtomicReference<>();

        private final AtomicBoolean terminated = new AtomicBoolean();

        private ChatResponseUpdate pendingTerminal;

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
            if (cancellation.isCancellationRequested()) {
                fail(new RunCancelledException());
                return;
            }
            activeCancellations.add(cancellation);
            registration.set(RunCancellations.register(cancellation, this::cancelFromSignal));
            try {
                Flow.Publisher<ChatResponseUpdate> publisher = Objects.requireNonNull(
                        transport.completeStreaming(request, options, cancellation),
                        "MistralTransport.completeStreaming returned null.");
                publisher.subscribe(this);
            } catch (RuntimeException exception) {
                fail(normalize(exception));
            }
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
                fail(cancellation.isCancellationRequested() ? new RunCancelledException() : failure("client_closed"));
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ChatResponseUpdate update) {
            if (terminated.get()) {
                return;
            }
            Objects.requireNonNull(update, "update");
            if (pendingTerminal != null) {
                fail(failure("update_after_terminal"));
                cancelUpstream();
                return;
            }
            if (update.finishReason() != null) {
                pendingTerminal = update;
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
            if (pendingTerminal == null) {
                fail(failure("missing_terminal"));
                return;
            }
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            sink.emit(pendingTerminal);
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
            activeCancellations.remove(cancellation);
            closeRegistration(registration.getAndSet(null));
        }
    }
}
