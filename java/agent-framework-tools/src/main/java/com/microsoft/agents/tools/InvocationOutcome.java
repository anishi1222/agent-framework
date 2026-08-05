// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import java.util.Objects;

/**
 * Describes a durable terminal invocation outcome.
 *
 * @param invocationId stable invocation identifier
 * @param requestDigest exact request digest
 * @param result terminal invocation result
 */
public record InvocationOutcome(InvocationId invocationId, String requestDigest, ToolInvocationResult result)
        implements InvocationLedgerEntry {
    /** Creates a validated immutable terminal outcome. */
    public InvocationOutcome {
        Objects.requireNonNull(invocationId, "invocationId");
        requestDigest = ToolValidation.requireNonBlank(requestDigest, "requestDigest");
        Objects.requireNonNull(result, "result");
        if (!invocationId.equals(result.invocationId())) {
            throw new IllegalArgumentException("Outcome and result invocation identifiers must match.");
        }
    }
}
