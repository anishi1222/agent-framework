// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.UnsupportedStorageCapabilityException;
import com.microsoft.agents.core.VersionedSnapshot;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Stores immutable workflow checkpoints with optimistic compare-and-set semantics. */
public interface CheckpointStorage {
    /** Expected revision used to create a checkpoint only when no value exists. */
    long CREATE_ONLY = -1;

    /**
     * Returns stable optional storage capabilities.
     *
     * @return immutable capability set
     */
    Set<StorageCapability> capabilities();

    /**
     * Loads the latest checkpoint for a key.
     *
     * @param key checkpoint key
     * @return optional detached versioned checkpoint
     */
    CompletionStage<Optional<VersionedSnapshot<WorkflowCheckpoint>>> loadAsync(CheckpointKey key);

    /**
     * Saves a checkpoint with optimistic compare-and-set semantics.
     *
     * @param key checkpoint key
     * @param checkpoint checkpoint draft or snapshot
     * @param expectedRevision {@code -1} for create-only or a positive replacement revision
     * @return stored checkpoint and assigned opaque revision
     */
    CompletionStage<VersionedSnapshot<WorkflowCheckpoint>> saveAsync(
            CheckpointKey key, WorkflowCheckpoint checkpoint, long expectedRevision);

    /**
     * Deletes a checkpoint at an exact revision.
     *
     * @param key checkpoint key
     * @param expectedRevision positive expected revision
     * @return completion stage
     */
    CompletionStage<Void> deleteAsync(CheckpointKey key, long expectedRevision);

    /**
     * Atomically commits a checkpoint and invocation-ledger delta.
     *
     * <p>The default implementation fails before effects. Implementations advertising {@link
     * StorageCapability#ATOMIC_CHECKPOINT_AND_LEDGER} must override this method atomically.
     *
     * @param commit atomic commit request
     * @param expectedRevision checkpoint expected revision
     * @return stored checkpoint and assigned revision
     */
    default CompletionStage<VersionedSnapshot<WorkflowCheckpoint>> commitAsync(
            CheckpointCommit commit, long expectedRevision) {
        return CompletableFuture.failedFuture(new UnsupportedStorageCapabilityException(
                "Checkpoint storage does not support ATOMIC_CHECKPOINT_AND_LEDGER."));
    }

    /**
     * Returns the completion durability boundary.
     *
     * @return storage durability
     */
    CheckpointStorageDurability durability();
}
