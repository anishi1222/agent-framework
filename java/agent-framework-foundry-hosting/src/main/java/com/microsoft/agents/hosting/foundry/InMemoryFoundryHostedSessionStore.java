// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.foundry;

import com.microsoft.agents.core.StorageConflictException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Provides a bounded process-local hosted session store with TTL and optimistic concurrency. */
public final class InMemoryFoundryHostedSessionStore implements FoundryHostedSessionStore, AutoCloseable {
    private final Object lock = new Object();
    private final LinkedHashMap<FoundryHostedSessionKey, FoundryHostedSession> sessions =
            new LinkedHashMap<>(16, 0.75f, true);
    private final int maximumSessions;
    private final int maximumSubmittedMessageIds;
    private final Duration timeToLive;
    private final Clock clock;
    private boolean closed;

    /**
     * Creates a bounded process-local store.
     *
     * @param maximumSessions positive capacity
     * @param timeToLive positive inactivity TTL
     */
    public InMemoryFoundryHostedSessionStore(int maximumSessions, Duration timeToLive) {
        this(maximumSessions, timeToLive, 10_000, Clock.systemUTC());
    }

    /**
     * Creates a bounded process-local store.
     *
     * @param maximumSessions positive session capacity
     * @param timeToLive positive inactivity TTL
     * @param maximumSubmittedMessageIds positive per-session message-id capacity
     */
    public InMemoryFoundryHostedSessionStore(int maximumSessions, Duration timeToLive, int maximumSubmittedMessageIds) {
        this(maximumSessions, timeToLive, maximumSubmittedMessageIds, Clock.systemUTC());
    }

    InMemoryFoundryHostedSessionStore(int maximumSessions, Duration timeToLive, Clock clock) {
        this(maximumSessions, timeToLive, 10_000, clock);
    }

    InMemoryFoundryHostedSessionStore(
            int maximumSessions, Duration timeToLive, int maximumSubmittedMessageIds, Clock clock) {
        if (maximumSessions <= 0) {
            throw new IllegalArgumentException("maximumSessions must be positive.");
        }
        if (maximumSubmittedMessageIds <= 0) {
            throw new IllegalArgumentException("maximumSubmittedMessageIds must be positive.");
        }
        if (timeToLive == null || timeToLive.isZero() || timeToLive.isNegative()) {
            throw new IllegalArgumentException("timeToLive must be positive.");
        }
        this.maximumSessions = maximumSessions;
        this.maximumSubmittedMessageIds = maximumSubmittedMessageIds;
        this.timeToLive = timeToLive;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<Optional<FoundryHostedSession>> loadAsync(FoundryHostedSessionKey key) {
        synchronized (lock) {
            requireOpen();
            purgeExpired();
            return CompletableFuture.completedStage(Optional.ofNullable(sessions.get(key)));
        }
    }

    @Override
    public CompletionStage<FoundryHostedSession> saveAsync(FoundryHostedSession session, long expectedRevision) {
        synchronized (lock) {
            requireOpen();
            purgeExpired();
            if (session.submittedMessageIds().size() > maximumSubmittedMessageIds) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Foundry hosted submitted-message capacity is exhausted."));
            }
            FoundryHostedSession current = sessions.get(session.key());
            long actual = current == null ? FoundryHostedSession.CREATE_ONLY : current.revision();
            if (actual != expectedRevision) {
                return CompletableFuture.failedFuture(
                        new StorageConflictException("Foundry hosted session revision conflict."));
            }
            long revision = current == null ? 1 : current.revision() + 1;
            Instant now = clock.instant();
            FoundryHostedSession stored = new FoundryHostedSession(
                    session.key(),
                    session.threadId(),
                    session.runId(),
                    session.submittedMessageIds(),
                    revision,
                    current == null ? session.createdAt() : current.createdAt(),
                    now);
            sessions.put(stored.key(), stored);
            evictOldest();
            return CompletableFuture.completedStage(stored);
        }
    }

    @Override
    public CompletionStage<Boolean> deleteAsync(FoundryHostedSessionKey key) {
        synchronized (lock) {
            requireOpen();
            return CompletableFuture.completedStage(sessions.remove(key) != null);
        }
    }

    /** Returns the current bounded entry count. */
    public int size() {
        synchronized (lock) {
            purgeExpired();
            return sessions.size();
        }
    }

    /** Clears process-local references without deleting remote service resources. */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            sessions.clear();
        }
    }

    private void purgeExpired() {
        Instant threshold = clock.instant().minus(timeToLive);
        sessions.values().removeIf(session -> session.updatedAt().isBefore(threshold));
    }

    private void evictOldest() {
        Iterator<Map.Entry<FoundryHostedSessionKey, FoundryHostedSession>> iterator =
                sessions.entrySet().iterator();
        while (sessions.size() > maximumSessions && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Foundry hosted session store is closed.");
        }
    }
}
