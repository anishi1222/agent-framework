// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.VersionedSnapshot;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Stores immutable agent-session snapshots with compare-and-set revisions.
 *
 * <p>Expected revision {@value #CREATE_ONLY} is create-only. Every successful replacement returns a
 * greater opaque revision. A mismatch completes exceptionally with {@link
 * com.microsoft.agents.core.StorageConflictException}; implementations never silently retry or fall
 * back to last-writer-wins behavior.
 *
 * <p>All validation and operational failures discovered after an asynchronous method is called are
 * reported by exceptional completion of the returned stage. Implementations do not throw
 * synchronously for an invalid key, snapshot, expected revision, or compare-and-set conflict.
 */
public interface SessionStore {
    /** Expected revision used for a create-only write. */
    long CREATE_ONLY = -1L;

    /**
     * Loads a detached snapshot.
     *
     * @param key session key
     * @return stage containing an optional versioned detached snapshot, or completing exceptionally
     *     when validation or loading fails
     */
    CompletionStage<Optional<VersionedSnapshot<AgentSessionSnapshot>>> loadAsync(SessionKey key);

    /**
     * Atomically creates or replaces a snapshot.
     *
     * @param key session key
     * @param snapshot detached replacement snapshot
     * @param expectedRevision {@link #CREATE_ONLY} for create, otherwise the current opaque revision
     * @return stage containing the stored detached snapshot and new revision, or completing
     *     exceptionally when validation, storage, or compare-and-set fails
     */
    CompletionStage<VersionedSnapshot<AgentSessionSnapshot>> saveAsync(
            SessionKey key, AgentSessionSnapshot snapshot, long expectedRevision);

    /**
     * Atomically deletes an existing snapshot.
     *
     * @param key session key
     * @param expectedRevision current opaque revision
     * @return stage completing when deletion succeeds, or completing exceptionally when validation,
     *     storage, or compare-and-set fails
     */
    CompletionStage<Void> deleteAsync(SessionKey key, long expectedRevision);

    /**
     * Returns the store acknowledgement boundary.
     *
     * @return documented durability
     */
    SessionStoreDurability durability();
}
