// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import java.util.Objects;

/**
 * Couples one checkpoint replacement with an invocation-ledger delta.
 *
 * @param key checkpoint storage key
 * @param checkpoint replacement checkpoint
 * @param ledgerDelta invocation-ledger mutations
 */
public record CheckpointCommit(CheckpointKey key, WorkflowCheckpoint checkpoint, InvocationLedgerDelta ledgerDelta) {
    /** Creates an immutable atomic commit request. */
    public CheckpointCommit {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(ledgerDelta, "ledgerDelta");
    }
}
