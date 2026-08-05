// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Carries immutable framework context for one function invocation.
 *
 * @param logicalRunId uninterrupted logical run identifier
 * @param callId provider call correlation identifier
 * @param invocationId stable invocation identifier
 * @param cancellation run cancellation signal
 * @param executor executor selected and owned by the invocation runtime
 * @param metadata immutable invocation metadata
 */
public record ToolInvocationContext(
        String logicalRunId,
        String callId,
        InvocationId invocationId,
        RunCancellation cancellation,
        Executor executor,
        Map<String, StateValue> metadata) {
    /** Creates validated immutable invocation context. */
    public ToolInvocationContext {
        logicalRunId = ToolValidation.requireNonBlank(logicalRunId, "logicalRunId");
        callId = ToolValidation.requireNonBlank(callId, "callId");
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(executor, "executor");
        metadata = ToolValidation.copyMetadata(metadata);
    }
}
