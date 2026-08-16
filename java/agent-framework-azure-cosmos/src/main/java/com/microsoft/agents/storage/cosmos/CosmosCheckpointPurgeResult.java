// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import com.microsoft.agents.core.ValidationException;

/**
 * Reports bounded key-scoped checkpoint purge progress without exposing SDK response types.
 *
 * @param deletedHeads checkpoint-head documents deleted
 * @param deletedSnapshots immutable checkpoint snapshots deleted
 * @param completedBatches successful transactional delete batches
 * @param status terminal purge status
 * @param serviceStatusCode sanitized service status for an incomplete purge, or {@code null}
 */
public record CosmosCheckpointPurgeResult(
        int deletedHeads, int deletedSnapshots, int completedBatches, Status status, Integer serviceStatusCode) {
    /** Terminal purge outcome. */
    public enum Status {
        /** The target checkpoint head and all snapshots for its key were removed. */
        COMPLETED,
        /** The target checkpoint head and all snapshots for its key were already absent. */
        ALREADY_PURGED,
        /** A concurrent write changed a conditionally protected item or left unfenced snapshots. */
        CONFLICT,
        /** Cosmos DB throttled the purge after bounded retries. */
        THROTTLED,
        /** Another service or data-contract failure interrupted the purge. */
        FAILED
    }

    /** Creates a validated immutable report. */
    public CosmosCheckpointPurgeResult {
        if (deletedHeads < 0 || deletedSnapshots < 0 || completedBatches < 0) {
            throw new ValidationException("Checkpoint purge counts must not be negative.");
        }
        if (deletedHeads > 1) {
            throw new ValidationException("A key-scoped checkpoint purge can delete at most one head.");
        }
        if (status == null) {
            throw new ValidationException("Checkpoint purge status must not be null.");
        }
        if ((status == Status.COMPLETED || status == Status.ALREADY_PURGED) && serviceStatusCode != null) {
            throw new ValidationException("Completed checkpoint purges must not carry a failure status code.");
        }
        if (status == Status.COMPLETED && deletedHeads != 1) {
            throw new ValidationException("A completed checkpoint purge must report its deleted head.");
        }
        if (status != Status.COMPLETED && deletedHeads != 0) {
            throw new ValidationException("An incomplete checkpoint purge must retain the target head.");
        }
        if (status == Status.ALREADY_PURGED && (deletedSnapshots != 0 || completedBatches != 0)) {
            throw new ValidationException("An already-purged report must have zero counts.");
        }
    }

    /**
     * Reports whether no checkpoint documents remain for the completed operation.
     *
     * @return {@code true} for completed and idempotent already-purged outcomes
     */
    public boolean isComplete() {
        return status == Status.COMPLETED || status == Status.ALREADY_PURGED;
    }
}
