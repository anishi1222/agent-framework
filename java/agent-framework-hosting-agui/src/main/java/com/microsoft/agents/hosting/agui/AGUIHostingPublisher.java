// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import com.microsoft.agents.hosting.HostingEvent;
import com.microsoft.agents.hosting.HostingOutcome;
import com.microsoft.agents.hosting.HostingRouteKind;
import com.microsoft.agents.hosting.HostingRun;
import com.microsoft.agents.protocols.agui.AGUIErrorCode;
import com.microsoft.agents.protocols.agui.AGUIEvent;
import com.microsoft.agents.protocols.agui.AGUIEventStreamValidator;
import com.microsoft.agents.protocols.agui.AGUIEvents;
import com.microsoft.agents.protocols.agui.AGUIJsonCodec;
import com.microsoft.agents.protocols.agui.AGUIProtocolException;
import com.microsoft.agents.protocols.agui.AGUIValidationContext;
import com.microsoft.agents.protocols.agui.RunAgentInput;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class AGUIHostingPublisher implements Flow.Publisher<AGUIEvent> {
    private final HostingRun source;

    private final HostingRouteKind kind;

    private final RunAgentInput input;

    private final boolean includeInput;

    private final AGUIJsonCodec codec;

    private final AGUITerminalMapper terminalMapper;

    private final Consumer<HostingOutcome> discard;

    private final Consumer<AGUIEvent> observer;

    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    private final AtomicBoolean subscribed = new AtomicBoolean();

    private Consumer<HostingOutcome> tracker = ignored -> {};

    AGUIHostingPublisher(
            HostingRun source,
            HostingRouteKind kind,
            RunAgentInput input,
            boolean includeInput,
            AGUIJsonCodec codec,
            AGUITerminalMapper terminalMapper,
            Consumer<HostingOutcome> discard,
            Consumer<AGUIEvent> observer) {
        this.source = Objects.requireNonNull(source, "source");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.input = Objects.requireNonNull(input, "input");
        this.includeInput = includeInput;
        this.codec = Objects.requireNonNull(codec, "codec");
        this.terminalMapper = Objects.requireNonNull(terminalMapper, "terminalMapper");
        this.discard = Objects.requireNonNull(discard, "discard");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    synchronized void outcomeTracker(Consumer<HostingOutcome> value) {
        tracker = Objects.requireNonNull(value, "value");
    }

    CompletableFuture<Void> completion() {
        return completion;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super AGUIEvent> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        if (!subscribed.compareAndSet(false, true)) {
            subscriber.onSubscribe(new EmptySubscription());
            subscriber.onError(new IllegalStateException("AG-UI hosted run publishers support one subscriber."));
            return;
        }
        Bridge bridge = new Bridge(subscriber);
        subscriber.onSubscribe(bridge);
        source.events().subscribe(bridge);
    }

    private final class Bridge implements Flow.Subscriber<HostingEvent>, Flow.Subscription {
        private final Flow.Subscriber<? super AGUIEvent> downstream;

        private final AGUIHostingEventConverter converter;

        private final AGUIEventStreamValidator validator;

        private final ArrayDeque<AGUIEvent> pending = new ArrayDeque<>();

        private Flow.Subscription upstream;

        private long demand;

        private boolean upstreamRequested;

        private boolean sourceDone;

        private boolean terminalReady;

        private boolean cancelled;

        private boolean draining;

        private Bridge(Flow.Subscriber<? super AGUIEvent> downstream) {
            this.downstream = downstream;
            converter = new AGUIHostingEventConverter(kind, input.runId(), codec);
            validator = new AGUIEventStreamValidator(codec.limits(), AGUIValidationContext.fromInput(input));
            enqueue(List.of(new AGUIEvents.RunStarted(
                    input.threadId(),
                    input.runId(),
                    input.parentRunId(),
                    includeInput ? input : null,
                    BigDecimal.valueOf(Instant.now().toEpochMilli()),
                    null)));
        }

        @Override
        public synchronized void onSubscribe(Flow.Subscription subscription) {
            if (upstream != null || cancelled) {
                subscription.cancel();
                return;
            }
            upstream = subscription;
            drain();
        }

        @Override
        public synchronized void onNext(HostingEvent item) {
            if (cancelled || sourceDone) {
                return;
            }
            upstreamRequested = false;
            try {
                enqueue(converter.convert(item));
                drain();
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        @Override
        public synchronized void onError(Throwable throwable) {
            if (cancelled || sourceDone) {
                return;
            }
            sourceDone = true;
            resolveTerminal();
        }

        @Override
        public synchronized void onComplete() {
            if (cancelled || sourceDone) {
                return;
            }
            sourceDone = true;
            try {
                enqueue(converter.finish());
                resolveTerminal();
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        @Override
        public synchronized void request(long count) {
            if (count <= 0) {
                fail(new IllegalArgumentException("Reactive Streams demand must be positive."));
                return;
            }
            if (demand != Long.MAX_VALUE) {
                long added = demand + count;
                demand = added < 0 || count == Long.MAX_VALUE ? Long.MAX_VALUE : added;
            }
            drain();
        }

        @Override
        public synchronized void cancel() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            if (upstream != null) {
                upstream.cancel();
            }
            source.cancel();
            pending.clear();
            completion.completeExceptionally(
                    new AGUIProtocolException(AGUIErrorCode.CANCELLED, "AG-UI hosted stream was cancelled."));
        }

        private void resolveTerminal() {
            source.terminalAsync().whenComplete((outcome, failure) -> {
                if (failure != null || outcome == null) {
                    terminalFailure(failure);
                    return;
                }
                tracker.accept(outcome);
                terminalMapper.map(outcome).whenComplete((events, mappingFailure) -> {
                    synchronized (Bridge.this) {
                        if (cancelled) {
                            return;
                        }
                        if (mappingFailure != null) {
                            if (outcome.continuation() != null) {
                                discard.accept(outcome);
                            }
                            terminalFailure(mappingFailure);
                            return;
                        }
                        try {
                            enqueue(events);
                            terminalReady = true;
                            validator.finish();
                            drain();
                        } catch (Throwable validationFailure) {
                            fail(validationFailure);
                        }
                    }
                });
            });
        }

        private synchronized void terminalFailure(Throwable failure) {
            if (cancelled) {
                return;
            }
            try {
                enqueue(List.of(new AGUIEvents.RunError(
                        "Hosted AG-UI execution failed.",
                        failure instanceof AGUIProtocolException protocol
                                ? protocol.code().name()
                                : "HOSTING_ERROR",
                        BigDecimal.valueOf(Instant.now().toEpochMilli()),
                        null)));
                terminalReady = true;
                validator.finish();
                drain();
            } catch (Throwable terminalFailure) {
                fail(terminalFailure);
            }
        }

        private void enqueue(List<AGUIEvent> events) {
            for (AGUIEvent event : events) {
                validator.accept(event);
                observer.accept(event);
                if (pending.size() >= codec.limits().maxBufferedEvents()) {
                    throw new AGUIProtocolException(AGUIErrorCode.OVERFLOW, "AG-UI hosting event buffer overflowed.");
                }
                pending.addLast(event);
            }
        }

        private void drain() {
            if (draining || cancelled) {
                return;
            }
            draining = true;
            try {
                while (!cancelled) {
                    if (demand > 0 && !pending.isEmpty()) {
                        AGUIEvent event = pending.removeFirst();
                        if (demand != Long.MAX_VALUE) {
                            demand--;
                        }
                        downstream.onNext(event);
                        continue;
                    }
                    if (terminalReady && pending.isEmpty()) {
                        cancelled = true;
                        downstream.onComplete();
                        completion.complete(null);
                        return;
                    }
                    if (demand > 0 && pending.isEmpty() && upstream != null && !upstreamRequested && !sourceDone) {
                        upstreamRequested = true;
                        upstream.request(1);
                        continue;
                    }
                    return;
                }
            } catch (Throwable failure) {
                fail(failure);
            } finally {
                draining = false;
            }
        }

        private void fail(Throwable failure) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            if (upstream != null) {
                upstream.cancel();
            }
            source.cancel();
            pending.clear();
            downstream.onError(failure);
            completion.completeExceptionally(failure);
        }
    }

    private static final class EmptySubscription implements Flow.Subscription {
        @Override
        public void request(long count) {}

        @Override
        public void cancel() {}
    }
}
