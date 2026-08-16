// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.StorageConflictException;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.core.VersionedSnapshot;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implements atomic process-local session storage with detached snapshots.
 *
 * <p>Operations for one key are linearizable. A global increasing counter supplies opaque revisions,
 * including after delete and recreate, so a stale revision cannot accidentally match a later value.
 */
public final class InMemorySessionStore implements SessionStore {
    private final ConcurrentHashMap<SessionKey, VersionedSnapshot<AgentSessionSnapshot>> snapshots =
            new ConcurrentHashMap<>();

    private final AtomicLong revisions = new AtomicLong();

    @Override
    public CompletionStage<Optional<VersionedSnapshot<AgentSessionSnapshot>>> loadAsync(SessionKey key) {
        if (key == null) {
            return CompletableFuture.failedFuture(validation("key must not be null."));
        }
        VersionedSnapshot<AgentSessionSnapshot> current = snapshots.get(key);
        return CompletableFuture.completedFuture(current == null ? Optional.empty() : Optional.of(detach(current)));
    }

    @Override
    public CompletionStage<VersionedSnapshot<AgentSessionSnapshot>> saveAsync(
            SessionKey key, AgentSessionSnapshot snapshot, long expectedRevision) {
        if (key == null) {
            return CompletableFuture.failedFuture(validation("key must not be null."));
        }
        if (snapshot == null) {
            return CompletableFuture.failedFuture(validation("snapshot must not be null."));
        }
        ValidationException revisionFailure = validateSaveExpectedRevision(expectedRevision);
        if (revisionFailure != null) {
            return CompletableFuture.failedFuture(revisionFailure);
        }
        AgentSessionSnapshot safeSnapshot = detach(snapshot);
        AtomicReference<StorageConflictException> conflict = new AtomicReference<>();
        AtomicReference<VersionedSnapshot<AgentSessionSnapshot>> stored = new AtomicReference<>();
        snapshots.compute(key, (ignored, current) -> {
            if (expectedRevision == CREATE_ONLY) {
                if (current != null) {
                    conflict.set(conflict(key, expectedRevision, current.revision()));
                    return current;
                }
            } else if (current == null || current.revision() != expectedRevision) {
                conflict.set(conflict(key, expectedRevision, current == null ? null : current.revision()));
                return current;
            }
            VersionedSnapshot<AgentSessionSnapshot> replacement =
                    new VersionedSnapshot<>(safeSnapshot, revisions.incrementAndGet());
            stored.set(replacement);
            return replacement;
        });
        if (conflict.get() != null) {
            return CompletableFuture.failedFuture(conflict.get());
        }
        return CompletableFuture.completedFuture(detach(stored.get()));
    }

    @Override
    public CompletionStage<Void> deleteAsync(SessionKey key, long expectedRevision) {
        if (key == null) {
            return CompletableFuture.failedFuture(validation("key must not be null."));
        }
        if (expectedRevision <= 0) {
            return CompletableFuture.failedFuture(validation("delete expectedRevision must be greater than zero."));
        }
        AtomicReference<StorageConflictException> conflict = new AtomicReference<>();
        snapshots.compute(key, (ignored, current) -> {
            if (current == null || current.revision() != expectedRevision) {
                conflict.set(conflict(key, expectedRevision, current == null ? null : current.revision()));
                return current;
            }
            return null;
        });
        return conflict.get() == null
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.failedFuture(conflict.get());
    }

    @Override
    public SessionStoreDurability durability() {
        return SessionStoreDurability.PROCESS_MEMORY;
    }

    private static ValidationException validateSaveExpectedRevision(long expectedRevision) {
        if (expectedRevision != CREATE_ONLY && expectedRevision <= 0) {
            return validation("expectedRevision must be -1 for create or greater than zero.");
        }
        return null;
    }

    private static ValidationException validation(String message) {
        return new ValidationException(message);
    }

    private static StorageConflictException conflict(SessionKey key, long expected, Long actual) {
        return new StorageConflictException("Session '"
                + key.value()
                + "' expected revision "
                + expected
                + " but current revision is "
                + (actual == null ? "absent" : actual)
                + ".");
    }

    private static VersionedSnapshot<AgentSessionSnapshot> detach(VersionedSnapshot<AgentSessionSnapshot> versioned) {
        return new VersionedSnapshot<>(detach(versioned.snapshot()), versioned.revision());
    }

    private static AgentSessionSnapshot detach(AgentSessionSnapshot snapshot) {
        return new AgentSessionSnapshot(
                snapshot.sessionId(),
                snapshot.messages(),
                new AgentSessionStateBag(snapshot.state().values()),
                snapshot.pendingRun());
    }
}
