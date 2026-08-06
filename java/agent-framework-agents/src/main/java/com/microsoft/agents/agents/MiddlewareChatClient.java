// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.internal.SingleSubscriberPublisher;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Decorates a caller-owned {@link ChatClient} with immutable chat middleware.
 *
 * <p>The decorator does not close the inner client. Streaming middleware remains cold and executes
 * exactly once after subscription.
 */
public final class MiddlewareChatClient implements ChatClient {
    private static final int MAX_BUFFERED_UPDATES = 256;

    private final ChatClient inner;

    private final ChatMiddlewarePipeline pipeline;

    /**
     * Creates a middleware chat client.
     *
     * @param inner caller-owned inner client
     * @param middleware middleware in registration order
     */
    public MiddlewareChatClient(ChatClient inner, Collection<? extends ChatMiddleware> middleware) {
        this.inner = AgentValidation.requireNonNull(inner, "inner");
        this.pipeline = new ChatMiddlewarePipeline(middleware);
    }

    @Override
    public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
        ChatMiddlewareContext context =
                new ChatMiddlewareContext(request, cancellation, new MiddlewareMetadata(initialMetadata(request)));
        return pipeline.executeAsync(context, next -> inner.completeAsync(next.request(), next.cancellation()));
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> completeStreaming(
            ChatClientRequest request, RunCancellation cancellation) {
        AgentValidation.requireNonNull(request, "request");
        AgentValidation.requireNonNull(cancellation, "cancellation");
        AtomicReference<Flow.Subscription> upstream = new AtomicReference<>();
        AtomicReference<SingleSubscriberPublisher<ChatResponseUpdate>> sinkReference = new AtomicReference<>();
        SingleSubscriberPublisher<ChatResponseUpdate> sink = new SingleSubscriberPublisher<>(
                () -> {
                    ChatMiddlewareContext context = new ChatMiddlewareContext(
                            request, cancellation, new MiddlewareMetadata(initialMetadata(request)));
                    Flow.Publisher<ChatResponseUpdate> publisher = pipeline.executeStreaming(
                            context, next -> inner.completeStreaming(next.request(), next.cancellation()));
                    publisher.subscribe(new Flow.Subscriber<>() {
                        @Override
                        public void onSubscribe(Flow.Subscription subscription) {
                            if (!upstream.compareAndSet(null, subscription)) {
                                subscription.cancel();
                                return;
                            }
                            if (cancellation.isCancellationRequested()) {
                                subscription.cancel();
                            } else {
                                subscription.request(Long.MAX_VALUE);
                            }
                        }

                        @Override
                        public void onNext(ChatResponseUpdate item) {
                            sinkReference.get().emit(item);
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            sinkReference.get().fail(throwable);
                        }

                        @Override
                        public void onComplete() {
                            sinkReference.get().complete();
                        }
                    });
                },
                () -> {
                    cancellation.cancel();
                    Flow.Subscription subscription = upstream.get();
                    if (subscription != null) {
                        subscription.cancel();
                    }
                },
                MAX_BUFFERED_UPDATES);
        sinkReference.set(sink);
        return sink;
    }

    private static java.util.Map<String, StateValue> initialMetadata(ChatClientRequest request) {
        LinkedHashMap<String, StateValue> metadata =
                new LinkedHashMap<>(request.options().metadata());
        if (request.runContext() != null) {
            metadata.putAll(request.runContext().metadata());
        }
        return java.util.Map.copyOf(metadata);
    }
}
