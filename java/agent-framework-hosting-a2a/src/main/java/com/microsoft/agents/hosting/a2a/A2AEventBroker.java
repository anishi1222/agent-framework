// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import com.microsoft.agents.protocols.a2a.A2AErrorCode;
import com.microsoft.agents.protocols.a2a.A2AProtocolException;
import com.microsoft.agents.protocols.a2a.A2AStreamEvent;
import com.microsoft.agents.protocols.a2a.Task;
import com.microsoft.agents.protocols.a2a.TaskStatusUpdateEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;

final class A2AEventBroker {
    private final Map<ChannelKey, TaskChannel> channels = new LinkedHashMap<>();

    private final int maxChannels;

    private final int maxBufferedEvents;

    A2AEventBroker(int maxChannels, int maxBufferedEvents) {
        this.maxChannels = HostingA2AValidation.positive(maxChannels, "maxChannels");
        this.maxBufferedEvents = HostingA2AValidation.positive(maxBufferedEvents, "maxBufferedEvents");
    }

    synchronized void register(A2APrincipal principal, Task task) {
        ChannelKey key = key(principal, task.id());
        TaskChannel existing = channels.get(key);
        if (existing != null && existing.replace(task)) {
            return;
        }
        if (existing == null && channels.size() >= maxChannels) {
            throw new com.microsoft.agents.protocols.a2a.A2AException("A2A task event channel capacity is exhausted.");
        }
        channels.put(key, new TaskChannel(task, maxBufferedEvents));
    }

    void publish(A2APrincipal principal, Task current, A2AStreamEvent event) {
        ChannelKey key = key(principal, current.id());
        TaskChannel channel;
        synchronized (this) {
            channel = channels.get(key);
        }
        if (channel == null) {
            throw new IllegalStateException("Task event channel is not registered.");
        }
        channel.publish(current, event);
        if (isBoundary(event)) {
            synchronized (this) {
                channels.remove(key, channel);
            }
        }
    }

    synchronized void remove(A2APrincipal principal, String taskId) {
        channels.remove(key(principal, taskId));
    }

    Flow.Publisher<A2AStreamEvent> subscribe(A2APrincipal principal, String taskId) {
        ChannelKey key = key(principal, taskId);
        TaskChannel channel;
        synchronized (this) {
            channel = channels.get(key);
        }
        if (channel == null) {
            return failed(new A2AProtocolException(A2AErrorCode.TASK_NOT_FOUND, "Task was not found."));
        }
        return channel.publisher(() -> remove(key, channel));
    }

    private synchronized void remove(ChannelKey key, TaskChannel channel) {
        channels.remove(key, channel);
    }

    private static ChannelKey key(A2APrincipal principal, String taskId) {
        return new ChannelKey(
                principal.principalId(), principal.isolationKey(), HostingA2AValidation.nonBlank(taskId, "taskId"));
    }

    private static Flow.Publisher<A2AStreamEvent> failed(Throwable failure) {
        return subscriber -> {
            subscriber.onSubscribe(EmptySubscription.INSTANCE);
            subscriber.onError(failure);
        };
    }

    private static boolean isBoundary(A2AStreamEvent event) {
        if (event instanceof Task task) {
            return task.status().state().isTerminal() || task.status().state().isInterrupted();
        }
        if (event instanceof TaskStatusUpdateEvent status) {
            return status.status().state().isTerminal()
                    || status.status().state().isInterrupted();
        }
        return false;
    }

    private record ChannelKey(String principalId, String isolationKey, String taskId) {}

    private static final class TaskChannel {
        private final List<TaskSubscription> subscriptions = new ArrayList<>();

        private final int maxBufferedEvents;

        private Task current;

        private boolean closed;

        private TaskChannel(Task current, int maxBufferedEvents) {
            this.current = current;
            this.maxBufferedEvents = maxBufferedEvents;
        }

        private synchronized boolean replace(Task task) {
            if (closed || isBoundary(current)) {
                return false;
            }
            current = task;
            return true;
        }

        private Flow.Publisher<A2AStreamEvent> publisher(Runnable boundaryCallback) {
            return new TaskPublisher(this, maxBufferedEvents, boundaryCallback);
        }

        private void publish(Task nextCurrent, A2AStreamEvent event) {
            List<TaskSubscription> drains;
            synchronized (this) {
                if (closed) {
                    return;
                }
                current = nextCurrent;
                if (isBoundary(event)) {
                    closed = true;
                }
                drains = List.copyOf(subscriptions);
                for (TaskSubscription subscription : drains) {
                    subscription.enqueueLocked(event);
                    if (isBoundary(event)) {
                        subscription.completeLocked();
                        subscriptions.remove(subscription);
                    }
                }
            }
            drains.forEach(TaskSubscription::drain);
        }

        private boolean start(TaskSubscription subscription) {
            boolean boundary;
            synchronized (this) {
                subscription.enqueueLocked(current);
                boundary = isBoundary(current);
                if (boundary) {
                    closed = true;
                    subscription.completeLocked();
                } else {
                    subscriptions.add(subscription);
                }
            }
            subscription.drain();
            return boundary;
        }

        private synchronized void cancel(TaskSubscription subscription) {
            subscriptions.remove(subscription);
        }

        private static boolean isBoundary(A2AStreamEvent event) {
            return A2AEventBroker.isBoundary(event);
        }
    }

    private static final class TaskPublisher implements Flow.Publisher<A2AStreamEvent> {
        private final TaskChannel channel;

        private final int maxBufferedEvents;

        private final Runnable boundaryCallback;

        private boolean subscribed;

        private TaskPublisher(TaskChannel channel, int maxBufferedEvents, Runnable boundaryCallback) {
            this.channel = channel;
            this.maxBufferedEvents = maxBufferedEvents;
            this.boundaryCallback = boundaryCallback;
        }

        @Override
        public synchronized void subscribe(Flow.Subscriber<? super A2AStreamEvent> subscriber) {
            Objects.requireNonNull(subscriber, "subscriber");
            if (subscribed) {
                subscriber.onSubscribe(EmptySubscription.INSTANCE);
                subscriber.onError(new IllegalStateException("A2A task subscription permits one subscriber."));
                return;
            }
            subscribed = true;
            TaskSubscription subscription = new TaskSubscription(channel, subscriber, maxBufferedEvents);
            subscriber.onSubscribe(subscription);
            if (channel.start(subscription)) {
                boundaryCallback.run();
            }
        }
    }

    private static final class TaskSubscription implements Flow.Subscription {
        private final TaskChannel channel;

        private final Flow.Subscriber<? super A2AStreamEvent> subscriber;

        private final int maxBufferedEvents;

        private final ArrayDeque<A2AStreamEvent> queue = new ArrayDeque<>();

        private long demand;

        private boolean cancelled;

        private boolean completed;

        private boolean terminalSignalled;

        private boolean draining;

        private Throwable failure;

        private TaskSubscription(
                TaskChannel channel, Flow.Subscriber<? super A2AStreamEvent> subscriber, int maxBufferedEvents) {
            this.channel = channel;
            this.subscriber = subscriber;
            this.maxBufferedEvents = maxBufferedEvents;
        }

        @Override
        public void request(long count) {
            synchronized (this) {
                if (cancelled || terminalSignalled) {
                    return;
                }
                if (count <= 0) {
                    failure = new IllegalArgumentException("Flow demand must be positive.");
                    completed = true;
                    queue.clear();
                } else {
                    demand = addCap(demand, count);
                }
            }
            drain();
        }

        @Override
        public void cancel() {
            synchronized (this) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                queue.clear();
            }
            channel.cancel(this);
        }

        private synchronized void enqueueLocked(A2AStreamEvent event) {
            if (cancelled || completed) {
                return;
            }
            if (queue.size() >= maxBufferedEvents) {
                failure = new com.microsoft.agents.protocols.a2a.A2ATransportException(
                        "A2A host stream exceeded maxBufferedEvents=" + maxBufferedEvents + ".");
                completed = true;
                queue.clear();
                return;
            }
            queue.addLast(event);
        }

        private synchronized void completeLocked() {
            completed = true;
        }

        private void drain() {
            synchronized (this) {
                if (draining || cancelled) {
                    return;
                }
                draining = true;
            }
            while (true) {
                A2AStreamEvent event;
                boolean terminal;
                Throwable terminalFailure;
                synchronized (this) {
                    if (cancelled) {
                        draining = false;
                        return;
                    }
                    if (demand > 0 && !queue.isEmpty()) {
                        demand--;
                        event = queue.removeFirst();
                        terminal = false;
                        terminalFailure = null;
                    } else if (completed && queue.isEmpty() && !terminalSignalled) {
                        terminalSignalled = true;
                        event = null;
                        terminal = true;
                        terminalFailure = failure;
                    } else {
                        draining = false;
                        return;
                    }
                }
                if (terminal) {
                    if (terminalFailure == null) {
                        subscriber.onComplete();
                    } else {
                        subscriber.onError(terminalFailure);
                    }
                    return;
                }
                try {
                    subscriber.onNext(event);
                } catch (RuntimeException subscriberFailure) {
                    cancel();
                    return;
                }
            }
        }

        private static long addCap(long left, long right) {
            long value = left + right;
            return value < 0 ? Long.MAX_VALUE : value;
        }
    }

    private enum EmptySubscription implements Flow.Subscription {
        INSTANCE;

        @Override
        public void request(long count) {}

        @Override
        public void cancel() {}
    }
}
