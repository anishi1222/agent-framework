// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.UsageDetails;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Captures safe state required to resume an approval-suspended function loop.
 *
 * <p>The snapshot contains no tools, handlers, clients, executors, middleware, credentials, or other
 * behavior. A configured {@link FunctionInvocationLoop} restores behavior by matching stable tool
 * names and validating request digests.
 *
 * @param logicalRunId uninterrupted logical run identity
 * @param history ordered model and tool history
 * @param approvalRequests pending approval requests
 * @param pendingCalls ordered pending calls
 * @param options immutable loop options
 * @param metadata immutable logical-run metadata
 * @param toolMode current tool-selection mode
 * @param suspensionVersion positive suspension generation
 * @param modelTurns completed provider turns
 * @param toolInvocations tool bodies started before suspension
 * @param latestResponse latest provider response
 * @param usage folded usage, or {@code null}
 */
public record FunctionContinuation(
        String logicalRunId,
        List<Message> history,
        List<ToolApprovalRequest> approvalRequests,
        List<FunctionContinuationCall> pendingCalls,
        FunctionInvocationOptions options,
        Map<String, StateValue> metadata,
        ToolMode toolMode,
        long suspensionVersion,
        int modelTurns,
        int toolInvocations,
        ChatResponse latestResponse,
        UsageDetails usage) {
    /** Creates and defensively copies a continuation snapshot. */
    public FunctionContinuation {
        logicalRunId = ToolValidation.requireNonBlank(logicalRunId, "logicalRunId");
        history = List.copyOf(Objects.requireNonNull(history, "history"));
        approvalRequests = List.copyOf(Objects.requireNonNull(approvalRequests, "approvalRequests"));
        pendingCalls = List.copyOf(Objects.requireNonNull(pendingCalls, "pendingCalls"));
        Objects.requireNonNull(options, "options");
        metadata = ToolValidation.copyMetadata(metadata);
        Objects.requireNonNull(toolMode, "toolMode");
        if (suspensionVersion <= 0) {
            throw new IllegalArgumentException("suspensionVersion must be greater than zero.");
        }
        if (modelTurns < 0 || toolInvocations < 0) {
            throw new IllegalArgumentException("turn and invocation counts must not be negative.");
        }
        Objects.requireNonNull(latestResponse, "latestResponse");
        if (approvalRequests.isEmpty()) {
            throw new IllegalArgumentException("approvalRequests must not be empty.");
        }
    }
}
