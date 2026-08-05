// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import java.util.Objects;

/**
 * Describes a durable pending invocation before an external side effect begins.
 *
 * @param invocationId stable invocation identifier
 * @param logicalRunId logical run identifier
 * @param callId provider call identifier
 * @param toolName exact tool name
 * @param requestDigest schema-and-argument request digest
 */
public record InvocationRecord(
        InvocationId invocationId, String logicalRunId, String callId, String toolName, String requestDigest)
        implements InvocationLedgerEntry {
    /** Creates a validated immutable pending record. */
    public InvocationRecord {
        Objects.requireNonNull(invocationId, "invocationId");
        logicalRunId = ToolValidation.requireNonBlank(logicalRunId, "logicalRunId");
        callId = ToolValidation.requireNonBlank(callId, "callId");
        toolName = ToolValidation.requireNonBlank(toolName, "toolName");
        requestDigest = ToolValidation.requireNonBlank(requestDigest, "requestDigest");
    }
}
