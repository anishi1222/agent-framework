// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.RunCancellation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class FakeChatClient implements ChatClient {
    @FunctionalInterface
    interface FiniteHandler {
        CompletionStage<ChatResponse> complete(ChatClientRequest request, RunCancellation cancellation);
    }

    @FunctionalInterface
    interface StreamingHandler {
        Flow.Publisher<ChatResponseUpdate> complete(ChatClientRequest request, RunCancellation cancellation);
    }

    private final ConcurrentLinkedQueue<FiniteHandler> finiteHandlers = new ConcurrentLinkedQueue<>();

    private final ConcurrentLinkedQueue<StreamingHandler> streamingHandlers = new ConcurrentLinkedQueue<>();

    private final CopyOnWriteArrayList<ChatClientRequest> requests = new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<RunCancellation> cancellations = new CopyOnWriteArrayList<>();

    private final CountDownLatch firstRequest = new CountDownLatch(1);

    private volatile FiniteHandler fallbackFinite = (request, cancellation) ->
            CompletableFuture.failedFuture(new AssertionError("No finite response configured."));

    private volatile StreamingHandler fallbackStreaming =
            (request, cancellation) -> subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {
                    subscriber.onError(new AssertionError("No streaming response configured."));
                }

                @Override
                public void cancel() {}
            });

    FakeChatClient enqueue(ChatResponse response) {
        finiteHandlers.add((request, cancellation) -> CompletableFuture.completedFuture(response));
        return this;
    }

    FakeChatClient enqueueFailure(Throwable failure) {
        finiteHandlers.add((request, cancellation) -> CompletableFuture.failedFuture(failure));
        return this;
    }

    FakeChatClient enqueueFinite(FiniteHandler handler) {
        finiteHandlers.add(handler);
        return this;
    }

    FakeChatClient enqueueStreaming(List<ChatResponseUpdate> updates) {
        streamingHandlers.add(
                (request, cancellation) -> new ScriptedPublisher(updates, null, true, new AtomicBoolean()));
        return this;
    }

    FakeChatClient enqueueStreamingFailure(Throwable failure) {
        streamingHandlers.add(
                (request, cancellation) -> new ScriptedPublisher(List.of(), failure, true, new AtomicBoolean()));
        return this;
    }

    FakeChatClient enqueueStreaming(StreamingHandler handler) {
        streamingHandlers.add(handler);
        return this;
    }

    void fallbackFinite(FiniteHandler handler) {
        fallbackFinite = handler;
    }

    void fallbackStreaming(StreamingHandler handler) {
        fallbackStreaming = handler;
    }

    List<ChatClientRequest> requests() {
        return List.copyOf(requests);
    }

    List<RunCancellation> cancellations() {
        return List.copyOf(cancellations);
    }

    CountDownLatch firstRequest() {
        return firstRequest;
    }

    @Override
    public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
        record(request, cancellation);
        FiniteHandler handler = finiteHandlers.poll();
        return (handler == null ? fallbackFinite : handler).complete(request, cancellation);
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> completeStreaming(
            ChatClientRequest request, RunCancellation cancellation) {
        record(request, cancellation);
        StreamingHandler handler = streamingHandlers.poll();
        return (handler == null ? fallbackStreaming : handler).complete(request, cancellation);
    }

    private void record(ChatClientRequest request, RunCancellation cancellation) {
        requests.add(request);
        cancellations.add(cancellation);
        firstRequest.countDown();
    }

    static Flow.Publisher<ChatResponseUpdate> pendingPublisher(AtomicBoolean cancelled, CountDownLatch subscribed) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long count) {
                subscribed.countDown();
                if (count <= 0) {
                    subscriber.onError(new IllegalArgumentException("demand"));
                }
            }

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        });
    }

    static Flow.Publisher<ChatResponseUpdate> emitThenHold(ChatResponseUpdate update, AtomicBoolean cancelled) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private final AtomicBoolean emitted = new AtomicBoolean();

            @Override
            public void request(long count) {
                if (count <= 0) {
                    subscriber.onError(new IllegalArgumentException("demand"));
                    return;
                }
                if (emitted.compareAndSet(false, true) && !cancelled.get()) {
                    subscriber.onNext(update);
                }
            }

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        });
    }

    static final class ScriptedPublisher implements Flow.Publisher<ChatResponseUpdate> {
        private final List<ChatResponseUpdate> updates;

        private final Throwable failure;

        private final boolean complete;

        private final AtomicBoolean cancelled;

        private ScriptedPublisher(
                List<ChatResponseUpdate> updates, Throwable failure, boolean complete, AtomicBoolean cancelled) {
            this.updates = List.copyOf(updates);
            this.failure = failure;
            this.complete = complete;
            this.cancelled = cancelled;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ChatResponseUpdate> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicLong demand = new AtomicLong();

                private final AtomicBoolean terminated = new AtomicBoolean();

                private int index;

                @Override
                public synchronized void request(long count) {
                    if (terminated.get() || cancelled.get()) {
                        return;
                    }
                    if (count <= 0) {
                        terminated.set(true);
                        subscriber.onError(new IllegalArgumentException("demand"));
                        return;
                    }
                    demand.updateAndGet(current -> addCap(current, count));
                    ArrayList<ChatResponseUpdate> ready = new ArrayList<>();
                    while (demand.get() > 0 && index < updates.size()) {
                        demand.decrementAndGet();
                        ready.add(updates.get(index++));
                    }
                    ready.forEach(subscriber::onNext);
                    if (index == updates.size() && terminated.compareAndSet(false, true)) {
                        if (failure != null) {
                            subscriber.onError(failure);
                        } else if (complete) {
                            subscriber.onComplete();
                        }
                    }
                }

                @Override
                public void cancel() {
                    cancelled.set(true);
                    terminated.set(true);
                }
            });
        }

        private static long addCap(long left, long right) {
            long sum = left + right;
            return sum < 0 ? Long.MAX_VALUE : sum;
        }
    }
}
