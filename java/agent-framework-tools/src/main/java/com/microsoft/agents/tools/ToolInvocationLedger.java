// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.VersionedSnapshot;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Provides durable compare-and-set hooks for invocation pending and terminal records.
 *
 * <p>This SPI alone does not make crash replay exactly once. Crash-safe external effects additionally
 * require an atomic checkpoint-and-ledger commit or provider idempotency as defined by ADR-0038.
 */
public interface ToolInvocationLedger {
    /**
     * Loads the current ledger entry.
     *
     * @param invocationId invocation identifier
     * @return optional versioned ledger entry
     */
    CompletionStage<Optional<VersionedSnapshot<InvocationLedgerEntry>>> lookupAsync(InvocationId invocationId);

    /**
     * Records a pending invocation with optimistic concurrency.
     *
     * @param record pending record
     * @param expectedRevision zero to create, otherwise the expected opaque revision
     * @return versioned pending record
     */
    CompletionStage<VersionedSnapshot<InvocationRecord>> recordPendingAsync(
            InvocationRecord record, long expectedRevision);

    /**
     * Records a terminal invocation outcome with optimistic concurrency.
     *
     * @param outcome terminal outcome
     * @param expectedRevision expected pending-record revision
     * @return versioned terminal outcome
     */
    CompletionStage<VersionedSnapshot<InvocationOutcome>> recordOutcomeAsync(
            InvocationOutcome outcome, long expectedRevision);
}
