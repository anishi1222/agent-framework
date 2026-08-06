// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.StorageConflictException;
import com.microsoft.agents.core.UnsupportedStorageCapabilityException;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.core.VersionedSnapshot;
import com.microsoft.agents.tools.InvocationId;
import com.microsoft.agents.tools.InvocationLedgerEntry;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Implements detached process-local checkpoint storage for tests and development.
 *
 * <p>All operations are linearizable. The optional atomic-ledger capability is selected explicitly
 * at construction time and remains stable for the storage lifetime.
 */
public final class InMemoryCheckpointStorage implements CheckpointStorage {
    private final Map<CheckpointKey, VersionedSnapshot<WorkflowCheckpoint>> checkpoints = new HashMap<>();

    private final Map<InvocationId, VersionedSnapshot<InvocationLedgerEntry>> ledger = new HashMap<>();

    private final AtomicLong revisions = new AtomicLong();

    private final Set<StorageCapability> capabilities;

    /** Creates storage without atomic checkpoint-and-ledger support. */
    public InMemoryCheckpointStorage() {
        this(false);
    }

    /**
     * Creates storage with optional atomic checkpoint-and-ledger support.
     *
     * @param atomicCheckpointAndLedger whether atomic ledger commits are supported
     */
    public InMemoryCheckpointStorage(boolean atomicCheckpointAndLedger) {
        capabilities = atomicCheckpointAndLedger ? Set.of(StorageCapability.ATOMIC_CHECKPOINT_AND_LEDGER) : Set.of();
    }

    @Override
    public Set<StorageCapability> capabilities() {
        return capabilities;
    }

    @Override
    public synchronized CompletionStage<Optional<VersionedSnapshot<WorkflowCheckpoint>>> loadAsync(CheckpointKey key) {
        if (key == null) {
            return CompletableFuture.failedFuture(new ValidationException("key must not be null."));
        }
        return CompletableFuture.completedFuture(Optional.ofNullable(checkpoints.get(key)));
    }

    @Override
    public synchronized CompletionStage<VersionedSnapshot<WorkflowCheckpoint>> saveAsync(
            CheckpointKey key, WorkflowCheckpoint checkpoint, long expectedRevision) {
        ValidationException validation = validateSave(key, checkpoint, expectedRevision);
        if (validation != null) {
            return CompletableFuture.failedFuture(validation);
        }
        VersionedSnapshot<WorkflowCheckpoint> current = checkpoints.get(key);
        StorageConflictException conflict = checkpointConflict(key, current, expectedRevision);
        if (conflict != null) {
            return CompletableFuture.failedFuture(conflict);
        }
        long revision = revisions.incrementAndGet();
        VersionedSnapshot<WorkflowCheckpoint> stored =
                new VersionedSnapshot<>(checkpoint.withRevision(revision), revision);
        checkpoints.put(key, stored);
        return CompletableFuture.completedFuture(stored);
    }

    @Override
    public synchronized CompletionStage<Void> deleteAsync(CheckpointKey key, long expectedRevision) {
        if (key == null) {
            return CompletableFuture.failedFuture(new ValidationException("key must not be null."));
        }
        if (expectedRevision <= 0) {
            return CompletableFuture.failedFuture(
                    new ValidationException("delete expectedRevision must be greater than zero."));
        }
        VersionedSnapshot<WorkflowCheckpoint> current = checkpoints.get(key);
        if (current == null || current.revision() != expectedRevision) {
            return CompletableFuture.failedFuture(conflict(
                    "Checkpoint '" + key + "'", expectedRevision, current == null ? null : current.revision()));
        }
        checkpoints.remove(key);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<VersionedSnapshot<WorkflowCheckpoint>> commitAsync(
            CheckpointCommit commit, long expectedRevision) {
        if (!capabilities.contains(StorageCapability.ATOMIC_CHECKPOINT_AND_LEDGER)) {
            return CompletableFuture.failedFuture(new UnsupportedStorageCapabilityException(
                    "Checkpoint storage does not support ATOMIC_CHECKPOINT_AND_LEDGER."));
        }
        if (commit == null) {
            return CompletableFuture.failedFuture(new ValidationException("commit must not be null."));
        }
        ValidationException validation = validateSave(commit.key(), commit.checkpoint(), expectedRevision);
        if (validation != null) {
            return CompletableFuture.failedFuture(validation);
        }

        VersionedSnapshot<WorkflowCheckpoint> current = checkpoints.get(commit.key());
        StorageConflictException checkpointConflict = checkpointConflict(commit.key(), current, expectedRevision);
        if (checkpointConflict != null) {
            return CompletableFuture.failedFuture(checkpointConflict);
        }
        for (LedgerEntryMutation mutation : commit.ledgerDelta().mutations()) {
            VersionedSnapshot<InvocationLedgerEntry> existing =
                    ledger.get(mutation.entry().invocationId());
            long expected = mutation.expectedRevision();
            boolean mismatch = expected == 0 ? existing != null : existing == null || existing.revision() != expected;
            if (mismatch) {
                return CompletableFuture.failedFuture(conflict(
                        "Invocation '" + mutation.entry().invocationId() + "'",
                        expected,
                        existing == null ? null : existing.revision()));
            }
        }

        long checkpointRevision = revisions.incrementAndGet();
        VersionedSnapshot<WorkflowCheckpoint> storedCheckpoint =
                new VersionedSnapshot<>(commit.checkpoint().withRevision(checkpointRevision), checkpointRevision);
        checkpoints.put(commit.key(), storedCheckpoint);
        for (LedgerEntryMutation mutation : commit.ledgerDelta().mutations()) {
            long ledgerRevision = revisions.incrementAndGet();
            ledger.put(mutation.entry().invocationId(), new VersionedSnapshot<>(mutation.entry(), ledgerRevision));
        }
        return CompletableFuture.completedFuture(storedCheckpoint);
    }

    /**
     * Loads an invocation entry written through an atomic commit.
     *
     * @param invocationId invocation identifier
     * @return optional versioned ledger entry
     */
    public synchronized CompletionStage<Optional<VersionedSnapshot<InvocationLedgerEntry>>> loadLedgerAsync(
            InvocationId invocationId) {
        if (invocationId == null) {
            return CompletableFuture.failedFuture(new ValidationException("invocationId must not be null."));
        }
        return CompletableFuture.completedFuture(Optional.ofNullable(ledger.get(invocationId)));
    }

    @Override
    public CheckpointStorageDurability durability() {
        return CheckpointStorageDurability.PROCESS_MEMORY;
    }

    private static ValidationException validateSave(
            CheckpointKey key, WorkflowCheckpoint checkpoint, long expectedRevision) {
        if (key == null) {
            return new ValidationException("key must not be null.");
        }
        if (checkpoint == null) {
            return new ValidationException("checkpoint must not be null.");
        }
        if (expectedRevision != CREATE_ONLY && expectedRevision <= 0) {
            return new ValidationException("expectedRevision must be -1 for create-only or greater than zero.");
        }
        return null;
    }

    private static StorageConflictException checkpointConflict(
            CheckpointKey key, VersionedSnapshot<WorkflowCheckpoint> current, long expectedRevision) {
        boolean mismatch = expectedRevision == CREATE_ONLY
                ? current != null
                : current == null || current.revision() != expectedRevision;
        return mismatch
                ? conflict("Checkpoint '" + key + "'", expectedRevision, current == null ? null : current.revision())
                : null;
    }

    private static StorageConflictException conflict(String subject, long expected, Long actual) {
        return new StorageConflictException(subject + " expected revision " + expected + " but current revision is "
                + (actual == null ? "absent" : actual) + ".");
    }
}
