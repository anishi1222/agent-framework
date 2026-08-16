// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import com.microsoft.agents.core.StorageConflictException;
import com.microsoft.agents.core.VersionedSnapshot;
import com.microsoft.agents.protocols.agui.AGUIErrorCode;
import com.microsoft.agents.protocols.agui.AGUIProtocolException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Provides a capacity-bounded, TTL-expiring, optimistic in-memory AG-UI thread store. */
public final class InMemoryAGUIThreadStore implements AGUIThreadStore {
    private final Object lock = new Object();

    private final int maxEntries;

    private final Duration timeToLive;

    private final Clock clock;

    private final Map<AGUIThreadKey, VersionedSnapshot<AGUIThreadState>> entries = new HashMap<>();

    private final AtomicLong revisions = new AtomicLong();

    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a store with system time.
     *
     * @param maxEntries maximum retained threads
     * @param timeToLive inactivity TTL measured from the last stored state
     */
    public InMemoryAGUIThreadStore(int maxEntries, Duration timeToLive) {
        this(maxEntries, timeToLive, Clock.systemUTC());
    }

    InMemoryAGUIThreadStore(int maxEntries, Duration timeToLive, Clock clock) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be greater than zero.");
        }
        this.maxEntries = maxEntries;
        this.timeToLive = java.util.Objects.requireNonNull(timeToLive, "timeToLive");
        if (timeToLive.isZero() || timeToLive.isNegative()) {
            throw new IllegalArgumentException("timeToLive must be positive.");
        }
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<Optional<VersionedSnapshot<AGUIThreadState>>> loadAsync(AGUIThreadKey key) {
        java.util.Objects.requireNonNull(key, "key");
        try {
            requireOpen();
            synchronized (lock) {
                removeExpired();
                return CompletableFuture.completedFuture(Optional.ofNullable(entries.get(key)));
            }
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public CompletionStage<VersionedSnapshot<AGUIThreadState>> compareAndSetAsync(
            AGUIThreadKey key, AGUIThreadState state, long expectedRevision) {
        java.util.Objects.requireNonNull(key, "key");
        java.util.Objects.requireNonNull(state, "state");
        try {
            requireOpen();
            synchronized (lock) {
                removeExpired();
                VersionedSnapshot<AGUIThreadState> current = entries.get(key);
                long actual = current == null ? CREATE_ONLY : current.revision();
                if (actual != expectedRevision) {
                    throw new StorageConflictException("AG-UI thread revision does not match the expected revision.");
                }
                if (current == null && entries.size() >= maxEntries) {
                    throw new AGUIProtocolException(
                            AGUIErrorCode.OVERFLOW, "AG-UI thread-store capacity is exhausted.");
                }
                VersionedSnapshot<AGUIThreadState> stored = new VersionedSnapshot<>(state, revisions.incrementAndGet());
                entries.put(key, stored);
                return CompletableFuture.completedFuture(stored);
            }
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public CompletionStage<Void> deleteAsync(AGUIThreadKey key, long expectedRevision) {
        java.util.Objects.requireNonNull(key, "key");
        try {
            requireOpen();
            synchronized (lock) {
                VersionedSnapshot<AGUIThreadState> current = entries.get(key);
                if (current == null || current.revision() != expectedRevision) {
                    throw new StorageConflictException("AG-UI thread revision does not match the expected revision.");
                }
                entries.remove(key);
                return CompletableFuture.completedFuture(null);
            }
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Returns the number of live retained entries.
     *
     * @return live entry count
     */
    public int size() {
        requireOpen();
        synchronized (lock) {
            removeExpired();
            return entries.size();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (lock) {
            entries.clear();
        }
    }

    private void removeExpired() {
        Instant cutoff = clock.instant().minus(timeToLive);
        Iterator<VersionedSnapshot<AGUIThreadState>> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().snapshot().updatedAt().isBefore(cutoff)) {
                iterator.remove();
            }
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("AG-UI thread store is closed.");
        }
    }
}
