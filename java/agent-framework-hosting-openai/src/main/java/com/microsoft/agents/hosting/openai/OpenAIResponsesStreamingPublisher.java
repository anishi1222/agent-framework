// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.openai;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.hosting.HostingError;
import com.microsoft.agents.hosting.HostingErrorCode;
import com.microsoft.agents.hosting.HostingEvent;
import com.microsoft.agents.hosting.HostingException;
import com.microsoft.agents.hosting.HostingOutcome;
import com.microsoft.agents.hosting.HostingOutcomeStatus;
import com.microsoft.agents.hosting.HostingRun;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

final class OpenAIResponsesStreamingPublisher implements Flow.Publisher<byte[]> {
    private final HostingRun source;

    private final OpenAIResponsesResponseMapper mapper;

    private final OpenAIResponsesJsonCodec codec;

    private final Function<List<Message>, CompletionStage<Void>> persist;

    private final Supplier<CompletionStage<Void>> release;

    private final Consumer<HostingOutcome> discardOutcome;

    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    private final AtomicBoolean subscribed = new AtomicBoolean();

    private final AtomicBoolean discarded = new AtomicBoolean();

    private final AtomicReference<HostingOutcome> terminalOutcome = new AtomicReference<>();

    OpenAIResponsesStreamingPublisher(
            HostingRun source,
            OpenAIResponsesResponseMapper mapper,
            OpenAIResponsesJsonCodec codec,
            Function<List<Message>, CompletionStage<Void>> persist,
            Supplier<CompletionStage<Void>> release,
            Consumer<HostingOutcome> discardOutcome) {
        this.source = Objects.requireNonNull(source, "source");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.persist = Objects.requireNonNull(persist, "persist");
        this.release = Objects.requireNonNull(release, "release");
        this.discardOutcome = Objects.requireNonNull(discardOutcome, "discardOutcome");
    }

    CompletableFuture<Void> completion() {
        return completion;
    }

    void discardUndelivered() {
        if (!discarded.compareAndSet(false, true)) {
            return;
        }
        source.cancel();
        HostingOutcome outcome = terminalOutcome.get();
        if (outcome != null) {
            discardOutcome.accept(outcome);
        }
        releaseState().whenComplete((ignored, failure) -> {
            if (failure == null) {
                completion.completeExceptionally(new HostingException(
                        HostingErrorCode.CLIENT_CANCELLED, "OpenAI Responses stream was not delivered."));
            } else {
                completion.completeExceptionally(RunHandles.unwrap(failure));
            }
        });
    }

    private CompletionStage<Void> persistState(List<Message> messages) {
        try {
            return Objects.requireNonNull(persist.apply(messages), "OpenAI Responses persist stage");
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletionStage<Void> releaseState() {
        try {
            return Objects.requireNonNull(release.get(), "OpenAI Responses release stage");
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public void subscribe(Flow.Subscriber<? super byte[]> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        if (!subscribed.compareAndSet(false, true)) {
            subscriber.onSubscribe(new EmptySubscription());
            subscriber.onError(new IllegalStateException("OpenAI Responses hosted runs support one subscriber."));
            return;
        }
        Bridge bridge;
        try {
            bridge = new Bridge(subscriber);
        } catch (Throwable failure) {
            subscriber.onSubscribe(new EmptySubscription());
            subscriber.onError(failure);
            source.cancel();
            releaseState().whenComplete((ignored, releaseFailure) -> {
                if (releaseFailure != null) {
                    failure.addSuppressed(RunHandles.unwrap(releaseFailure));
                }
                completion.completeExceptionally(failure);
            });
            return;
        }
        subscriber.onSubscribe(bridge);
        source.events().subscribe(bridge);
    }

    private final class Bridge implements Flow.Subscriber<HostingEvent>, Flow.Subscription {
        private final Flow.Subscriber<? super byte[]> downstream;

        private final OpenAIResponsesResponseMapper.StreamingAccumulator accumulator = mapper.newStreamingAccumulator();

        private final ArrayDeque<byte[]> pending = new ArrayDeque<>();

        private Flow.Subscription upstream;

        private long demand;

        private boolean upstreamRequested;

        private boolean sourceDone;

        private boolean terminalReady;

        private boolean terminalFailurePending;

        private boolean cancelled;

        private boolean draining;

        private Bridge(Flow.Subscriber<? super byte[]> downstream) {
            this.downstream = downstream;
            enqueue(accumulator.start());
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
                enqueue(accumulator.accept(item));
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
                enqueue(accumulator.finishOutput());
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
            releaseState().whenComplete((ignored, failure) -> {
                Throwable terminal = failure == null
                        ? new HostingException(
                                HostingErrorCode.CLIENT_CANCELLED, "OpenAI Responses stream was cancelled.")
                        : RunHandles.unwrap(failure);
                completion.completeExceptionally(terminal);
            });
        }

        private void resolveTerminal() {
            source.terminalAsync().whenComplete((outcome, failure) -> {
                if (failure != null || outcome == null) {
                    terminalFailure(failure);
                    return;
                }
                terminalOutcome.set(outcome);
                CompletionStage<Void> stateStage;
                OpenAIResponsesResponseMapper.StreamEnvelope terminal;
                if (outcome.status() == HostingOutcomeStatus.COMPLETED) {
                    stateStage = persistState(accumulator.messages());
                    terminal = accumulator.terminal("response.completed", accumulator.completedResponse());
                } else {
                    HostingError error = outcome.error() == null
                            ? HostingError.of(HostingErrorCode.INTERNAL_ERROR, "Hosted execution failed.")
                            : outcome.error();
                    stateStage = releaseState();
                    terminal = accumulator.terminal(
                            terminalEvent(outcome.status()),
                            accumulator.terminalResponse(error, terminalStatus(outcome.status())));
                }
                stateStage.whenComplete((ignored, stateFailure) -> {
                    synchronized (Bridge.this) {
                        if (cancelled) {
                            return;
                        }
                        if (stateFailure != null) {
                            terminalFailure(stateFailure);
                            return;
                        }
                        try {
                            enqueue(List.of(terminal));
                            terminalReady = true;
                            drain();
                        } catch (Throwable encodingFailure) {
                            fail(encodingFailure);
                        }
                    }
                });
            });
        }

        private synchronized void terminalFailure(Throwable failure) {
            if (cancelled || terminalReady || terminalFailurePending) {
                return;
            }
            terminalFailurePending = true;
            Throwable cause = failure == null
                    ? new HostingException(HostingErrorCode.INTERNAL_ERROR, "OpenAI Responses streaming failed.")
                    : RunHandles.unwrap(failure);
            releaseState().whenComplete((ignored, releaseFailure) -> {
                synchronized (Bridge.this) {
                    if (cancelled) {
                        return;
                    }
                    terminalFailurePending = false;
                    if (releaseFailure != null) {
                        cause.addSuppressed(RunHandles.unwrap(releaseFailure));
                    }
                    HostingError error = cause instanceof HostingException hosting
                            ? hosting.error()
                            : HostingError.of(HostingErrorCode.INTERNAL_ERROR, "OpenAI Responses streaming failed.");
                    try {
                        enqueue(List.of(accumulator.terminal(
                                "response.failed", accumulator.terminalResponse(error, "failed"))));
                        terminalReady = true;
                        drain();
                    } catch (Throwable terminalEncodingFailure) {
                        fail(terminalEncodingFailure);
                    }
                }
            });
        }

        private void enqueue(List<OpenAIResponsesResponseMapper.StreamEnvelope> events) {
            for (OpenAIResponsesResponseMapper.StreamEnvelope event : events) {
                if (pending.size() >= codec.limits().maxSseBufferedEvents()) {
                    throw new HostingException(HostingErrorCode.OVERFLOW, "OpenAI Responses SSE buffer overflowed.");
                }
                pending.addLast(codec.encodeSseFrame(event.event(), event.data()));
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
                        byte[] frame = pending.removeFirst();
                        if (demand != Long.MAX_VALUE) {
                            demand--;
                        }
                        downstream.onNext(frame);
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
            releaseState().whenComplete((ignored, releaseFailure) -> {
                if (releaseFailure != null) {
                    failure.addSuppressed(RunHandles.unwrap(releaseFailure));
                }
                completion.completeExceptionally(failure);
            });
        }
    }

    private static String terminalEvent(HostingOutcomeStatus status) {
        return switch (status) {
            case CANCELLED -> "response.cancelled";
            case FAILED, OVERFLOW, INPUT_REQUIRED, APPROVAL_REQUIRED -> "response.failed";
            case COMPLETED -> "response.completed";
        };
    }

    private static String terminalStatus(HostingOutcomeStatus status) {
        return switch (status) {
            case CANCELLED -> "cancelled";
            case FAILED, OVERFLOW, INPUT_REQUIRED, APPROVAL_REQUIRED -> "failed";
            case COMPLETED -> "completed";
        };
    }

    private static final class EmptySubscription implements Flow.Subscription {
        @Override
        public void request(long count) {}

        @Override
        public void cancel() {}
    }
}
