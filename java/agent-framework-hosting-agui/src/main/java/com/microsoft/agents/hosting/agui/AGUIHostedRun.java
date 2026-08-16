// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.hosting.HostingOutcome;
import com.microsoft.agents.protocols.agui.AGUIEvent;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Exposes one AG-UI event stream, terminal completion, cancellation, and delivery cleanup. */
public final class AGUIHostedRun {
    private final String hostRunId;

    private final Flow.Publisher<AGUIEvent> events;

    private final CompletionStage<Void> completionAsync;

    private final RunCancellation cancellation;

    private final Consumer<HostingOutcome> discard;

    private final AtomicReference<HostingOutcome> pendingOutcome = new AtomicReference<>();

    private final AtomicReference<Delivery> delivery = new AtomicReference<>(Delivery.OPEN);

    AGUIHostedRun(
            String hostRunId,
            Flow.Publisher<AGUIEvent> events,
            CompletionStage<Void> completionAsync,
            RunCancellation cancellation,
            Consumer<HostingOutcome> discard) {
        this.hostRunId = require(hostRunId, "hostRunId");
        this.events = java.util.Objects.requireNonNull(events, "events");
        this.completionAsync = java.util.Objects.requireNonNull(completionAsync, "completionAsync");
        this.cancellation = java.util.Objects.requireNonNull(cancellation, "cancellation");
        this.discard = java.util.Objects.requireNonNull(discard, "discard");
    }

    /**
     * Returns the generic host-generated run identifier.
     *
     * @return host run identifier
     */
    public String hostRunId() {
        return hostRunId;
    }

    /**
     * Returns the cold single-subscriber official event publisher.
     *
     * @return event publisher
     */
    public Flow.Publisher<AGUIEvent> events() {
        return events;
    }

    /**
     * Returns completion after the terminal event has been produced.
     *
     * @return completion stage
     */
    public CompletionStage<Void> completionAsync() {
        return completionAsync;
    }

    /**
     * Requests cancellation.
     *
     * @return whether this call initiated cancellation
     */
    public boolean cancel() {
        return cancellation.cancel();
    }

    /** Confirms that the transport delivered the complete terminal frame. */
    public void confirmDelivery() {
        if (delivery.compareAndSet(Delivery.OPEN, Delivery.DELIVERED)) {
            pendingOutcome.set(null);
        }
    }

    /** Discards an undelivered process-local continuation and cancels remaining work. */
    public void discardUndelivered() {
        cancellation.cancel();
        if (delivery.getAndSet(Delivery.UNDELIVERED) == Delivery.DELIVERED) {
            return;
        }
        HostingOutcome outcome = pendingOutcome.getAndSet(null);
        if (outcome != null && outcome.continuation() != null) {
            discard.accept(outcome);
        }
    }

    void trackOutcome(HostingOutcome outcome) {
        if (outcome == null || outcome.continuation() == null) {
            return;
        }
        if (delivery.get() == Delivery.UNDELIVERED) {
            discard.accept(outcome);
        } else if (delivery.get() == Delivery.OPEN) {
            pendingOutcome.compareAndSet(null, outcome);
        }
    }

    private static String require(String value, String name) {
        java.util.Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private enum Delivery {
        OPEN,
        DELIVERED,
        UNDELIVERED
    }
}
