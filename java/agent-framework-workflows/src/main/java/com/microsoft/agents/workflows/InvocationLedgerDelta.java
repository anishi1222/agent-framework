// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.tools.InvocationId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Contains invocation-ledger mutations committed atomically with a workflow checkpoint.
 *
 * @param mutations distinct invocation mutations
 */
public record InvocationLedgerDelta(List<LedgerEntryMutation> mutations) {
    /** Creates an immutable validated ledger delta. */
    public InvocationLedgerDelta {
        Objects.requireNonNull(mutations, "mutations");
        ArrayList<LedgerEntryMutation> copy = new ArrayList<>(mutations.size());
        HashSet<InvocationId> identifiers = new HashSet<>();
        for (LedgerEntryMutation mutation : mutations) {
            LedgerEntryMutation checked = Objects.requireNonNull(mutation, "mutation");
            if (!identifiers.add(checked.entry().invocationId())) {
                throw new WorkflowValidationException("Invocation ledger delta contains duplicate invocation id '"
                        + checked.entry().invocationId() + "'.");
            }
            copy.add(checked);
        }
        mutations = List.copyOf(copy);
    }

    /**
     * Returns an empty ledger delta.
     *
     * @return empty delta
     */
    public static InvocationLedgerDelta empty() {
        return new InvocationLedgerDelta(List.of());
    }
}
