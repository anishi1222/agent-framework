// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.tools.InvocationLedgerEntry;
import java.util.Objects;

/**
 * Describes one optimistic invocation-ledger mutation in an atomic checkpoint commit.
 *
 * @param entry replacement ledger entry
 * @param expectedRevision zero to create or a positive replacement revision
 */
public record LedgerEntryMutation(InvocationLedgerEntry entry, long expectedRevision) {
    /** Creates a validated ledger mutation. */
    public LedgerEntryMutation {
        Objects.requireNonNull(entry, "entry");
        if (expectedRevision < 0) {
            throw new WorkflowValidationException("ledger expectedRevision must not be negative.");
        }
    }
}
