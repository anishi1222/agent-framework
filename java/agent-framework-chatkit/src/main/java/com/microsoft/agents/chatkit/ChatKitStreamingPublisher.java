// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.TextContent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Cold, bounded, single-subscriber adapter from agent updates to ChatKit thread events.
 *
 * <p>The source factory is invoked only after a subscriber receives its subscription and has not
 * cancelled it. The bridge requests one source update at a time, emits no more events than
 * downstream demand, uses no executor or helper thread, and reserves one bounded buffer slot for
 * the final item-done event.
 */
public final class ChatKitStreamingPublisher implements Flow.Publisher<ChatKitThreadEvent> {
    private static final int DEFAULT_MAX_BUFFERED_EVENTS = 256;

    private final Supplier<? extends Flow.Publisher<AgentResponseUpdate>> sourceFactory;
    private final String threadId;
    private final Supplier<String> messageIdSupplier;
    private final Clock clock;
    private final int maxBufferedEvents;
    private final AtomicBoolean subscribed = new AtomicBoolean();

    /**
     * Creates a publisher with a generated {@code msg_} identifier, the UTC clock, and a bounded
     * 256-event buffer.
     *
     * @param sourceFactory cold agent-update source factory
     * @param threadId target ChatKit thread identifier
     */
    public ChatKitStreamingPublisher(
            Supplier<? extends Flow.Publisher<AgentResponseUpdate>> sourceFactory, String threadId) {
        this(
                sourceFactory,
                threadId,
                ChatKitStreamingPublisher::newMessageId,
                Clock.systemUTC(),
                DEFAULT_MAX_BUFFERED_EVENTS);
    }

    /**
     * Creates a publisher with explicit deterministic dependencies and bounds.
     *
     * @param sourceFactory cold agent-update source factory
     * @param threadId target ChatKit thread identifier
     * @param messageIdSupplier supplier called exactly once for the sole subscription
     * @param clock clock used for the assistant item creation time
     * @param maxBufferedEvents maximum queued events, including the reserved item-done slot
     */
    public ChatKitStreamingPublisher(
            Supplier<? extends Flow.Publisher<AgentResponseUpdate>> sourceFactory,
            String threadId,
            Supplier<String> messageIdSupplier,
            Clock clock,
            int maxBufferedEvents) {
        this.sourceFactory = Objects.requireNonNull(sourceFactory, "sourceFactory");
        this.threadId = requireNonBlank(threadId, "threadId");
        this.messageIdSupplier = Objects.requireNonNull(messageIdSupplier, "messageIdSupplier");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxBufferedEvents < 2) {
            throw new IllegalArgumentException("maxBufferedEvents must be at least two.");
        }
        this.maxBufferedEvents = maxBufferedEvents;
    }

    /** Subscribes the sole downstream consumer. */
    @Override
    public void subscribe(Flow.Subscriber<? super ChatKitThreadEvent> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        if (!subscribed.compareAndSet(false, true)) {
            rejectAdditionalSubscriber(subscriber);
            return;
        }

        Bridge bridge = new Bridge(subscriber, sourceFactory, threadId, messageIdSupplier, clock, maxBufferedEvents);
        try {
            subscriber.onSubscribe(bridge);
        } catch (RuntimeException exception) {
            bridge.cancel();
            return;
        }
        bridge.start();
    }

    private static void rejectAdditionalSubscriber(Flow.Subscriber<? super ChatKitThreadEvent> subscriber) {
        try {
            subscriber.onSubscribe(EmptySubscription.INSTANCE);
            subscriber.onError(new IllegalStateException("ChatKitStreamingPublisher supports one subscriber."));
        } catch (RuntimeException ignored) {
            // A violating downstream cannot be signalled safely.
        }
    }

    private static String newMessageId() {
        return "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private enum EmptySubscription implements Flow.Subscription {
        INSTANCE;

        @Override
        public void request(long count) {}

        @Override
        public void cancel() {}
    }

    private static final class Bridge implements Flow.Subscription, Flow.Subscriber<AgentResponseUpdate> {
        private final Object lock = new Object();
        private final Flow.Subscriber<? super ChatKitThreadEvent> downstream;
        private final Supplier<? extends Flow.Publisher<AgentResponseUpdate>> sourceFactory;
        private final String threadId;
        private final Supplier<String> messageIdSupplier;
        private final Clock clock;
        private final int maxBufferedEvents;
        private final ArrayDeque<ChatKitThreadEvent> queue = new ArrayDeque<>();
        private final StringBuilder accumulatedText = new StringBuilder();

        private Flow.Subscription upstream;
        private long demand;
        private boolean draining;
        private boolean cancelled;
        private boolean terminated;
        private boolean sourceDone;
        private boolean upstreamRequested;
        private boolean processingUpdate;
        private boolean sawUpdate;
        private Throwable sourceError;
        private String messageId;
        private Instant createdAt;

        private Bridge(
                Flow.Subscriber<? super ChatKitThreadEvent> downstream,
                Supplier<? extends Flow.Publisher<AgentResponseUpdate>> sourceFactory,
                String threadId,
                Supplier<String> messageIdSupplier,
                Clock clock,
                int maxBufferedEvents) {
            this.downstream = downstream;
            this.sourceFactory = sourceFactory;
            this.threadId = threadId;
            this.messageIdSupplier = messageIdSupplier;
            this.clock = clock;
            this.maxBufferedEvents = maxBufferedEvents;
        }

        private void start() {
            synchronized (lock) {
                if (cancelled || terminated || sourceDone) {
                    return;
                }
            }

            String generatedId;
            Instant generatedAt;
            try {
                generatedId = requireNonBlank(messageIdSupplier.get(), "messageIdSupplier result");
                generatedAt = Objects.requireNonNull(clock.instant(), "clock instant");
            } catch (RuntimeException exception) {
                fail(exception, true);
                return;
            }

            synchronized (lock) {
                if (cancelled || terminated || sourceDone) {
                    return;
                }
            }

            Flow.Publisher<AgentResponseUpdate> source;
            try {
                source = Objects.requireNonNull(sourceFactory.get(), "sourceFactory result");
            } catch (RuntimeException exception) {
                fail(exception, true);
                return;
            }

            synchronized (lock) {
                if (cancelled || terminated || sourceDone) {
                    return;
                }
                messageId = generatedId;
                createdAt = generatedAt;
            }
            try {
                source.subscribe(this);
            } catch (RuntimeException exception) {
                fail(exception, true);
            }
        }

        @Override
        public void request(long count) {
            if (count <= 0) {
                fail(new IllegalArgumentException("Demand must be positive."), true);
                return;
            }
            synchronized (lock) {
                if (cancelled || terminated) {
                    return;
                }
                demand = addCap(demand, count);
            }
            drain();
        }

        @Override
        public void cancel() {
            Flow.Subscription subscription;
            synchronized (lock) {
                if (cancelled || terminated) {
                    return;
                }
                cancelled = true;
                queue.clear();
                subscription = upstream;
                upstream = null;
                upstreamRequested = false;
            }
            cancelQuietly(subscription);
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            Objects.requireNonNull(subscription, "subscription");
            boolean reject;
            synchronized (lock) {
                reject = cancelled || terminated || sourceDone || upstream != null;
                if (!reject) {
                    upstream = subscription;
                }
            }
            if (reject) {
                cancelQuietly(subscription);
            } else {
                drain();
            }
        }

        @Override
        public void onNext(AgentResponseUpdate update) {
            if (update == null) {
                fail(new NullPointerException("Agent update must not be null."), true);
                return;
            }

            boolean accepted;
            boolean firstUpdate;
            synchronized (lock) {
                if (cancelled || terminated || sourceDone) {
                    return;
                }
                accepted = upstreamRequested;
                if (accepted) {
                    upstreamRequested = false;
                    processingUpdate = true;
                    firstUpdate = !sawUpdate;
                    sawUpdate = true;
                } else {
                    firstUpdate = false;
                }
            }
            if (!accepted) {
                fail(new IllegalStateException("The source emitted an update without demand."), true);
                return;
            }

            ArrayList<ChatKitThreadEvent> mapped = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            if (firstUpdate) {
                if (mapped.size() >= maxBufferedEvents - 1) {
                    failBufferOverflow();
                    return;
                }
                mapped.add(new ChatKitThreadItemAddedEvent(
                        new ChatKitAssistantMessageItem(messageId, threadId, List.of(), createdAt)));
            }
            List<Content> contents = update.contents();
            if (contents != null) {
                for (Content content : contents) {
                    if (content instanceof TextContent textContent) {
                        if (mapped.size() >= maxBufferedEvents - 1) {
                            failBufferOverflow();
                            return;
                        }
                        String delta = textContent.text();
                        mapped.add(new ChatKitThreadItemUpdatedEvent(messageId, 0, delta));
                        text.append(delta);
                    }
                }
            }

            boolean occupied;
            synchronized (lock) {
                if (cancelled || terminated || sourceDone) {
                    processingUpdate = false;
                    return;
                }
                occupied = !queue.isEmpty();
                if (!occupied) {
                    queue.addAll(mapped);
                    accumulatedText.append(text);
                }
                processingUpdate = false;
            }
            if (occupied) {
                fail(new IllegalStateException("The source emitted more than one requested update."), true);
                return;
            }
            drain();
        }

        @Override
        public void onError(Throwable throwable) {
            fail(Objects.requireNonNull(throwable, "throwable"), true);
        }

        @Override
        public void onComplete() {
            synchronized (lock) {
                if (cancelled || terminated || sourceDone) {
                    return;
                }
                sourceDone = true;
                upstreamRequested = false;
                processingUpdate = false;
                upstream = null;
                if (sawUpdate) {
                    List<String> finalContent =
                            accumulatedText.isEmpty() ? List.of() : List.of(accumulatedText.toString());
                    queue.add(new ChatKitThreadItemDoneEvent(
                            new ChatKitAssistantMessageItem(messageId, threadId, finalContent, createdAt)));
                }
            }
            drain();
        }

        private void fail(Throwable failure, boolean clearQueue) {
            Flow.Subscription subscription;
            synchronized (lock) {
                if (cancelled || terminated || sourceDone) {
                    return;
                }
                sourceDone = true;
                sourceError = Objects.requireNonNull(failure, "failure");
                upstreamRequested = false;
                processingUpdate = false;
                subscription = upstream;
                upstream = null;
                if (clearQueue) {
                    queue.clear();
                }
            }
            cancelQuietly(subscription);
            drain();
        }

        private void drain() {
            synchronized (lock) {
                if (draining) {
                    return;
                }
                draining = true;
            }

            while (true) {
                ChatKitThreadEvent event = null;
                Flow.Subscription requestFrom = null;
                Throwable terminalError = null;
                boolean terminalComplete = false;

                synchronized (lock) {
                    if (cancelled || terminated) {
                        draining = false;
                        return;
                    }
                    if (demand > 0 && !queue.isEmpty()) {
                        event = queue.removeFirst();
                        demand--;
                    } else if (queue.isEmpty() && sourceDone) {
                        terminated = true;
                        terminalError = sourceError;
                        terminalComplete = terminalError == null;
                        draining = false;
                    } else if (queue.isEmpty()
                            && demand > 0
                            && upstream != null
                            && !upstreamRequested
                            && !processingUpdate) {
                        upstreamRequested = true;
                        requestFrom = upstream;
                    } else {
                        draining = false;
                        return;
                    }
                }

                if (event != null) {
                    try {
                        downstream.onNext(event);
                    } catch (RuntimeException exception) {
                        cancel();
                        return;
                    }
                    continue;
                }
                if (requestFrom != null) {
                    try {
                        requestFrom.request(1);
                    } catch (RuntimeException exception) {
                        fail(exception, true);
                    }
                    continue;
                }
                if (terminalError != null) {
                    try {
                        downstream.onError(terminalError);
                    } catch (RuntimeException ignored) {
                        // A violating downstream cannot be signalled safely.
                    }
                    return;
                }
                if (terminalComplete) {
                    try {
                        downstream.onComplete();
                    } catch (RuntimeException ignored) {
                        // A violating downstream cannot be signalled safely.
                    }
                    return;
                }
            }
        }

        private static long addCap(long current, long increment) {
            long result = current + increment;
            return result < 0 ? Long.MAX_VALUE : result;
        }

        private void failBufferOverflow() {
            fail(new IllegalStateException("One agent update exceeds the bounded ChatKit event buffer."), true);
        }

        private static void cancelQuietly(Flow.Subscription subscription) {
            if (subscription == null) {
                return;
            }
            try {
                subscription.cancel();
            } catch (RuntimeException ignored) {
                // Cancellation is best effort and no further callbacks are made.
            }
        }
    }
}
