// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

final class TestEventSubscriber implements Flow.Subscriber<OrchestrationEvent> {
    private final long initialDemand;

    private final ArrayList<OrchestrationEvent> events = new ArrayList<>();

    private final CompletableFuture<List<OrchestrationEvent>> result = new CompletableFuture<>();

    private final AtomicInteger terminalSignals = new AtomicInteger();

    private Flow.Subscription subscription;

    private boolean requestAfterEach;

    TestEventSubscriber(long initialDemand) {
        this.initialDemand = initialDemand;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        if (initialDemand > 0) {
            subscription.request(initialDemand);
        }
    }

    @Override
    public synchronized void onNext(OrchestrationEvent item) {
        events.add(item);
        if (requestAfterEach) {
            subscription.request(1);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        terminalSignals.incrementAndGet();
        result.completeExceptionally(throwable);
    }

    @Override
    public synchronized void onComplete() {
        terminalSignals.incrementAndGet();
        result.complete(List.copyOf(events));
    }

    void requestAfterEach() {
        requestAfterEach = true;
    }

    CompletableFuture<List<OrchestrationEvent>> result() {
        return result;
    }

    List<OrchestrationEvent> events() {
        synchronized (this) {
            return List.copyOf(events);
        }
    }

    AtomicInteger terminalSignals() {
        return terminalSignals;
    }

    void cancel() {
        subscription.cancel();
    }

    void request(long count) {
        subscription.request(count);
    }
}
