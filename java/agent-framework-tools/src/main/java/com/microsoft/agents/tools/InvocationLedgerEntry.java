// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

/**
 * Represents one pending or terminal durable invocation ledger entry.
 */
public sealed interface InvocationLedgerEntry permits InvocationRecord, InvocationOutcome {
    /**
     * Returns the stable invocation identifier.
     *
     * @return invocation identifier
     */
    InvocationId invocationId();

    /**
     * Returns the request digest bound to this entry.
     *
     * @return request digest
     */
    String requestDigest();
}
