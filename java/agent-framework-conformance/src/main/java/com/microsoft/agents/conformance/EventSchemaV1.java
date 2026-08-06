// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict event shapes and history invariants for fixture schema version 1. */
final class EventSchemaV1 {
    private EventSchemaV1() {}

    static ToolHistory validateToolEvents(JsonNode events, String sourceName) {
        boolean usesModes = events.get(0).has("mode");
        Map<String, ToolLaneState> lanes = new LinkedHashMap<>();

        for (int index = 0; index < events.size(); index++) {
            JsonNode event = events.get(index);
            String eventSource = JsonSchemaV1.indexed(sourceName, index);
            requireEventObject(event, eventSource);
            if (event.has("mode") != usesModes) {
                throw JsonSchemaV1.invalid(sourceName + " must either declare mode on every event or on none.");
            }
            validateToolEventShape(event, eventSource);
            String lane = usesModes ? JsonSchemaV1.requireText(event, "mode", eventSource) : "default";
            ToolLaneState state = lanes.computeIfAbsent(lane, ignored -> new ToolLaneState());
            validateSequence(event, eventSource, state.nextSequence++);
            if (state.finalTerminal) {
                throw JsonSchemaV1.invalid(eventSource + " appears after the lane's final terminal event.");
            }
            applyToolEvent(event, eventSource, state);
        }

        lanes.forEach((lane, state) -> finishToolLane(sourceName + " lane '" + lane + "'", state));
        LinkedHashMap<String, ToolLaneSummary> summaries = new LinkedHashMap<>();
        lanes.forEach((lane, state) -> summaries.put(lane, state.summary()));
        return new ToolHistory(summaries);
    }

    static RunSignalSummary validateRunSignalEvents(JsonNode events, String sourceName) {
        boolean subscribed = false;
        boolean cancellationRequested = false;
        boolean terminal = false;
        int terminalCount = 0;
        int updatesAfterCancellation = 0;
        String terminalOutcome = null;
        ArrayList<Boolean> cancelReturnValues = new ArrayList<>();
        Set<String> cancellationSources = new HashSet<>();

        for (int index = 0; index < events.size(); index++) {
            JsonNode event = events.get(index);
            String eventSource = JsonSchemaV1.indexed(sourceName, index);
            requireEventObject(event, eventSource);
            validateRunSignalShape(event, eventSource);
            validateSequence(event, eventSource, index);
            if (terminal) {
                throw JsonSchemaV1.invalid(eventSource + " appears after the terminal event.");
            }

            String type = JsonSchemaV1.requireText(event, "type", eventSource);
            switch (type) {
                case "subscribed" -> {
                    if (index != 0 || subscribed) {
                        throw JsonSchemaV1.invalid(eventSource + " must be the single first subscribed event.");
                    }
                    subscribed = true;
                }
                case "demand" -> {
                    requireSubscribed(subscribed, eventSource);
                    JsonSchemaV1.requirePositiveInteger(event, "count", eventSource);
                }
                case "update" -> {
                    requireSubscribed(subscribed, eventSource);
                    if (cancellationRequested) {
                        updatesAfterCancellation++;
                        throw JsonSchemaV1.invalid(eventSource + " must not update after cancellation.");
                    }
                }
                case "cancelRequested" -> {
                    requireSubscribed(subscribed, eventSource);
                    String source = JsonSchemaV1.requireText(event, "source", eventSource);
                    if (!cancellationSources.add(source)) {
                        throw JsonSchemaV1.invalid(eventSource + " repeats cancellation source '" + source + "'.");
                    }
                    cancelReturnValues.add(!cancellationRequested);
                    cancellationRequested = true;
                }
                case "terminal" -> {
                    requireSubscribed(subscribed, eventSource);
                    String outcome = JsonSchemaV1.requireText(event, "outcome", eventSource);
                    if (!Set.of("success", "cancelled", "failed").contains(outcome)) {
                        throw JsonSchemaV1.invalid(eventSource + " has unknown terminal outcome '" + outcome + "'.");
                    }
                    if (cancellationRequested && !"cancelled".equals(outcome)) {
                        throw JsonSchemaV1.invalid(
                                eventSource + " must be cancelled after cancellation was requested.");
                    }
                    terminalCount++;
                    terminalOutcome = outcome;
                    terminal = true;
                    if (index != events.size() - 1) {
                        throw JsonSchemaV1.invalid(eventSource + " terminal event must be last.");
                    }
                }
                default ->
                    throw JsonSchemaV1.invalid(eventSource + " has unknown run-signal event type '" + type + "'.");
            }
        }

        if (!subscribed || !terminal) {
            throw JsonSchemaV1.invalid(sourceName + " must contain subscribed and terminal events.");
        }
        return new RunSignalSummary(
                cancelReturnValues, terminalCount, terminalOutcome, updatesAfterCancellation, false);
    }

    static WorkflowSummary validateWorkflowEvents(JsonNode events, String sourceName) {
        return validateWorkflowEvents(events, sourceName, WorkflowSeed.empty());
    }

    static WorkflowSummary validateWorkflowEvents(JsonNode events, String sourceName, WorkflowSeed seed) {
        WorkflowState state = new WorkflowState(seed);
        for (int index = 0; index < events.size(); index++) {
            JsonNode event = events.get(index);
            String eventSource = JsonSchemaV1.indexed(sourceName, index);
            requireEventObject(event, eventSource);
            validateWorkflowEventShape(event, eventSource);
            validateSequence(event, eventSource, index);
            if (state.terminal) {
                throw JsonSchemaV1.invalid(eventSource + " appears after the terminal event.");
            }
            applyWorkflowEvent(event, eventSource, state);
            if ("terminal".equals(event.get("type").textValue()) && index != events.size() - 1) {
                throw JsonSchemaV1.invalid(eventSource + " terminal event must be last.");
            }
        }
        if (!state.terminal) {
            throw JsonSchemaV1.invalid(sourceName + " must contain exactly one terminal event.");
        }
        if (state.loadedCheckpointId != null && !state.resumed) {
            throw JsonSchemaV1.invalid(sourceName + " loads a checkpoint without resuming it.");
        }
        return state.summary();
    }

    private static void validateToolEventShape(JsonNode event, String sourceName) {
        String type = JsonSchemaV1.requireText(event, "type", sourceName);
        switch (type) {
            case "modelResponse" -> {
                event(event, sourceName, List.of("role"), List.of("mode"));
                JsonSchemaV1.requireText(event, "role", sourceName);
            }
            case "functionCall" -> {
                event(
                        event,
                        sourceName,
                        List.of("callId"),
                        List.of("mode", "name", "arguments", "invocationId", "logicalRunId", "executionViews"));
                JsonSchemaV1.requireText(event, "callId", sourceName);
                optionalText(event, "name", sourceName);
                optionalText(event, "invocationId", sourceName);
                optionalText(event, "logicalRunId", sourceName);
                if (event.has("executionViews")) {
                    JsonSchemaV1.requireTextArray(event, "executionViews", sourceName, true, true);
                }
                if (event.has("arguments") && !event.get("arguments").isObject()) {
                    throw JsonSchemaV1.invalid(JsonSchemaV1.path(sourceName, "arguments") + " must be a JSON object.");
                }
            }
            case "functionCallDelta" -> {
                event(event, sourceName, List.of("mode", "callId", "argumentsDelta"), List.of());
                JsonSchemaV1.requireText(event, "mode", sourceName);
                JsonSchemaV1.requireText(event, "callId", sourceName);
                JsonSchemaV1.requireString(event, "argumentsDelta", sourceName);
            }
            case "duplicateFunctionCall", "replayedFunctionCall" -> {
                event(event, sourceName, List.of("callId", "invocationId"), List.of("mode"));
                JsonSchemaV1.requireText(event, "callId", sourceName);
                JsonSchemaV1.requireText(event, "invocationId", sourceName);
            }
            case "duplicateCallRejected", "replayRejected" -> {
                event(event, sourceName, List.of("callId", "reason"), List.of("mode"));
                JsonSchemaV1.requireText(event, "callId", sourceName);
                JsonSchemaV1.requireText(event, "reason", sourceName);
            }
            case "toolInvoked" -> {
                event(event, sourceName, List.of(), List.of("mode", "callId", "invocationId"));
                String callId = optionalText(event, "callId", sourceName);
                String invocationId = optionalText(event, "invocationId", sourceName);
                if (callId == null && invocationId == null) {
                    throw JsonSchemaV1.invalid(sourceName + " must declare callId or invocationId.");
                }
            }
            case "toolFailed" -> {
                event(event, sourceName, List.of("callId", "errorType"), List.of("mode"));
                JsonSchemaV1.requireText(event, "callId", sourceName);
                JsonSchemaV1.requireText(event, "errorType", sourceName);
            }
            case "functionResult" -> {
                event(
                        event,
                        sourceName,
                        List.of("callId", "result"),
                        List.of("mode", "isError", "invocationId", "outcome"));
                JsonSchemaV1.requireText(event, "callId", sourceName);
                JsonSchemaV1.require(event, "result", sourceName);
                optionalText(event, "invocationId", sourceName);
                String outcome = optionalText(event, "outcome", sourceName);
                if (outcome != null
                        && !Set.of(
                                        "succeeded",
                                        "failed",
                                        "outputValidationFailed",
                                        "cancelled",
                                        "rejected",
                                        "duplicate")
                                .contains(outcome)) {
                    throw JsonSchemaV1.invalid(sourceName + " has unknown function-result outcome '" + outcome + "'.");
                }
                if (event.has("isError")) {
                    JsonSchemaV1.requireBoolean(event, "isError", sourceName);
                }
            }
            case "assistantMessage", "textDelta" -> {
                event(event, sourceName, List.of("text"), List.of("mode"));
                JsonSchemaV1.requireString(event, "text", sourceName);
            }
            case "approvalRequested" -> {
                event(event, sourceName, List.of("approvalId", "callId"), List.of("mode"));
                JsonSchemaV1.requireText(event, "approvalId", sourceName);
                JsonSchemaV1.requireText(event, "callId", sourceName);
            }
            case "approvalDecision", "replayedApprovalDecision" -> {
                event(event, sourceName, List.of("approvalId", "approved"), List.of("mode"));
                JsonSchemaV1.requireText(event, "approvalId", sourceName);
                JsonSchemaV1.requireBoolean(event, "approved", sourceName);
            }
            case "approvalReplayRejected" -> {
                event(event, sourceName, List.of("approvalId", "reason"), List.of("mode"));
                JsonSchemaV1.requireText(event, "approvalId", sourceName);
                JsonSchemaV1.requireText(event, "reason", sourceName);
            }
            case "terminal" -> {
                event(event, sourceName, List.of("outcome"), List.of("mode"));
                JsonSchemaV1.requireText(event, "outcome", sourceName);
            }
            default -> throw JsonSchemaV1.invalid(sourceName + " has unknown tool-loop event type '" + type + "'.");
        }
    }

    private static void validateRunSignalShape(JsonNode event, String sourceName) {
        String type = JsonSchemaV1.requireText(event, "type", sourceName);
        switch (type) {
            case "subscribed" -> event(event, sourceName, List.of(), List.of());
            case "demand" -> {
                event(event, sourceName, List.of("count"), List.of());
                JsonSchemaV1.requirePositiveInteger(event, "count", sourceName);
            }
            case "update" -> {
                event(event, sourceName, List.of("value"), List.of());
                JsonSchemaV1.require(event, "value", sourceName);
            }
            case "cancelRequested" -> {
                event(event, sourceName, List.of("source"), List.of());
                JsonSchemaV1.requireText(event, "source", sourceName);
            }
            case "terminal" -> {
                event(event, sourceName, List.of("outcome"), List.of());
                JsonSchemaV1.requireText(event, "outcome", sourceName);
            }
            default -> throw JsonSchemaV1.invalid(sourceName + " has unknown run-signal event type '" + type + "'.");
        }
    }

    private static void validateWorkflowEventShape(JsonNode event, String sourceName) {
        String type = JsonSchemaV1.requireText(event, "type", sourceName);
        switch (type) {
            case "workflowStarted" -> {
                event(event, sourceName, List.of("workflowId"), List.of());
                JsonSchemaV1.requireText(event, "workflowId", sourceName);
            }
            case "executorInvoked" -> {
                event(event, sourceName, List.of("executorId"), List.of("input"));
                JsonSchemaV1.requireText(event, "executorId", sourceName);
            }
            case "executorCompleted" -> {
                event(event, sourceName, List.of("executorId"), List.of("outputs"));
                JsonSchemaV1.requireText(event, "executorId", sourceName);
                if (event.has("outputs") && !event.get("outputs").isArray()) {
                    throw JsonSchemaV1.invalid(JsonSchemaV1.path(sourceName, "outputs") + " must be an array.");
                }
            }
            case "workflowOutput" -> {
                event(event, sourceName, List.of("value"), List.of("executorId"));
                JsonSchemaV1.require(event, "value", sourceName);
                optionalText(event, "executorId", sourceName);
            }
            case "fanOut" -> {
                event(event, sourceName, List.of("targetIds"), List.of("sourceId", "value"));
                optionalText(event, "sourceId", sourceName);
                JsonSchemaV1.requireTextArray(event, "targetIds", sourceName, true, true);
            }
            case "fanInBuffered" -> {
                event(event, sourceName, List.of("sourceId", "targetId", "value"), List.of());
                JsonSchemaV1.requireText(event, "sourceId", sourceName);
                JsonSchemaV1.requireText(event, "targetId", sourceName);
                JsonSchemaV1.require(event, "value", sourceName);
            }
            case "fanInReleased" -> {
                event(event, sourceName, List.of("sourceIds", "targetId", "values"), List.of("epoch"));
                JsonSchemaV1.requireTextArray(event, "sourceIds", sourceName, true, true);
                JsonSchemaV1.requireText(event, "targetId", sourceName);
                JsonSchemaV1.requireArray(event, "values", sourceName, true);
                if (event.has("epoch")) {
                    JsonSchemaV1.requireNonNegativeInteger(event, "epoch", sourceName);
                }
            }
            case "executorFailed" -> {
                event(event, sourceName, List.of("executorId", "errorType"), List.of());
                JsonSchemaV1.requireText(event, "executorId", sourceName);
                JsonSchemaV1.requireText(event, "errorType", sourceName);
            }
            case "cancellationRequested", "executorCancelled" -> {
                event(event, sourceName, List.of("executorId"), List.of());
                JsonSchemaV1.requireText(event, "executorId", sourceName);
            }
            case "checkpointSaved" -> {
                event(event, sourceName, List.of("checkpointId", "revision", "previousCheckpointId"), List.of());
                JsonSchemaV1.requireText(event, "checkpointId", sourceName);
                JsonSchemaV1.requirePositiveInteger(event, "revision", sourceName);
                JsonNode previous = JsonSchemaV1.requireNullable(event, "previousCheckpointId", sourceName);
                if (!previous.isNull()
                        && (!previous.isTextual() || previous.textValue().isBlank())) {
                    throw JsonSchemaV1.invalid(JsonSchemaV1.path(sourceName, "previousCheckpointId")
                            + " must be null or a non-blank string.");
                }
            }
            case "checkpointLoaded" -> {
                event(event, sourceName, List.of("checkpointId", "revision"), List.of());
                JsonSchemaV1.requireText(event, "checkpointId", sourceName);
                JsonSchemaV1.requirePositiveInteger(event, "revision", sourceName);
            }
            case "workflowResumed" -> {
                event(event, sourceName, List.of("checkpointId"), List.of());
                JsonSchemaV1.requireText(event, "checkpointId", sourceName);
            }
            case "runInterrupted" -> {
                event(event, sourceName, List.of("reason"), List.of());
                JsonSchemaV1.requireText(event, "reason", sourceName);
            }
            case "terminal" -> {
                event(event, sourceName, List.of("outcome"), List.of());
                JsonSchemaV1.requireText(event, "outcome", sourceName);
            }
            default -> throw JsonSchemaV1.invalid(sourceName + " has unknown workflow event type '" + type + "'.");
        }
    }

    private static void applyToolEvent(JsonNode event, String sourceName, ToolLaneState state) {
        String type = event.get("type").textValue();
        switch (type) {
            case "functionCall" -> {
                String callId = event.get("callId").textValue();
                String invocationId = optionalText(event, "invocationId", sourceName);
                String logicalRunId = optionalText(event, "logicalRunId", sourceName);
                List<String> executionViews = event.has("executionViews")
                        ? JsonSchemaV1.requireTextArray(event, "executionViews", sourceName, true, true)
                        : List.of();
                registerCall(callId, invocationId, logicalRunId, executionViews, sourceName, state);
            }
            case "functionCallDelta" -> {
                String callId = event.get("callId").textValue();
                ToolCall call = state.calls.get(callId);
                if (call == null) {
                    registerCall(callId, null, null, List.of(), sourceName, state);
                } else if (call.resultRecorded) {
                    throw JsonSchemaV1.invalid(sourceName + " continues call '" + callId + "' after its result.");
                }
            }
            case "duplicateFunctionCall" -> {
                ToolCall call = requireCall(event.get("callId").textValue(), sourceName, state);
                requireInvocation(event.get("invocationId").textValue(), call, sourceName);
                if (!call.invoked || call.resultRecorded) {
                    throw JsonSchemaV1.invalid(sourceName + " is not an in-flight duplicate call.");
                }
                if (!state.pendingDuplicateCalls.add(call.callId)) {
                    throw JsonSchemaV1.invalid(
                            sourceName + " repeats duplicate call observation '" + call.callId + "'.");
                }
            }
            case "duplicateCallRejected" -> {
                String callId = event.get("callId").textValue();
                requireCall(callId, sourceName, state);
                if (!state.pendingDuplicateCalls.remove(callId)) {
                    throw JsonSchemaV1.invalid(sourceName + " rejects a duplicate call that was not observed.");
                }
                state.duplicateCallRejectionCount++;
            }
            case "replayedFunctionCall" -> {
                ToolCall call = requireCall(event.get("callId").textValue(), sourceName, state);
                requireInvocation(event.get("invocationId").textValue(), call, sourceName);
                if (!call.resultRecorded) {
                    throw JsonSchemaV1.invalid(sourceName + " replay must follow the call's terminal result.");
                }
                if (!state.pendingReplayedCalls.add(call.callId)) {
                    throw JsonSchemaV1.invalid(sourceName + " repeats replay observation '" + call.callId + "'.");
                }
            }
            case "replayRejected" -> {
                String callId = event.get("callId").textValue();
                requireCall(callId, sourceName, state);
                if (!state.pendingReplayedCalls.remove(callId)) {
                    throw JsonSchemaV1.invalid(sourceName + " rejects a replay that was not observed.");
                }
                state.replayRejectionCount++;
            }
            case "toolInvoked" -> {
                ToolCall call = resolveInvokedCall(event, sourceName, state);
                if (call.invoked) {
                    throw JsonSchemaV1.invalid(sourceName + " invokes call '" + call.callId + "' more than once.");
                }
                if (call.approvalId != null) {
                    Approval approval = state.approvals.get(call.approvalId);
                    if (approval.decision == null || !approval.decision) {
                        throw JsonSchemaV1.invalid(sourceName + " invokes call '" + call.callId
                                + "' before an affirmative approval decision.");
                    }
                    state.invocationsAfterApproval++;
                } else {
                    state.invocationsWithoutApproval++;
                }
                call.invoked = true;
                state.invocationCounts.merge(call.callId, 1, Integer::sum);
            }
            case "toolFailed" -> {
                ToolCall call = requireCall(event.get("callId").textValue(), sourceName, state);
                if (!call.invoked || call.resultRecorded) {
                    throw JsonSchemaV1.invalid(sourceName + " failure is not correlated to an active invocation.");
                }
                call.failed = true;
            }
            case "functionResult" -> {
                ToolCall call = requireCall(event.get("callId").textValue(), sourceName, state);
                if (call.resultRecorded) {
                    throw JsonSchemaV1.invalid(
                            sourceName + " records duplicate result for call '" + call.callId + "'.");
                }
                String invocationId = optionalText(event, "invocationId", sourceName);
                if (invocationId != null) {
                    requireInvocation(invocationId, call, sourceName);
                }
                String outcome = optionalText(event, "outcome", sourceName);
                boolean rejected = false;
                if (call.approvalId != null) {
                    Approval approval = state.approvals.get(call.approvalId);
                    rejected = Boolean.FALSE.equals(approval.decision);
                }
                if (rejected) {
                    if (call.invoked) {
                        throw JsonSchemaV1.invalid(
                                sourceName + " records execution for rejected call '" + call.callId + "'.");
                    }
                    if (!"rejected".equals(outcome)) {
                        throw JsonSchemaV1.invalid(sourceName + " rejected call '" + call.callId
                                + "' must use function-result outcome 'rejected'.");
                    }
                } else {
                    if (!call.invoked) {
                        throw JsonSchemaV1.invalid(sourceName
                                + " result is not correlated to an active invocation for call '" + call.callId + "'.");
                    }
                    if ("rejected".equals(outcome)) {
                        throw JsonSchemaV1.invalid(sourceName + " non-rejected call '" + call.callId
                                + "' cannot use function-result outcome 'rejected'.");
                    }
                }
                call.resultRecorded = true;
                call.resultOutcome = outcome == null ? (call.failed ? "failed" : "succeeded") : outcome;
                state.resultOrder.add(call.callId);
                state.results.put(call.callId, event.get("result").deepCopy());
                state.resultOutcomes.put(call.callId, call.resultOutcome);
                state.lastResultSequence = event.get("sequence").intValue();
            }
            case "approvalRequested" -> {
                String callId = event.get("callId").textValue();
                ToolCall call = requireCall(callId, sourceName, state);
                String approvalId = event.get("approvalId").textValue();
                if (call.invoked || call.resultRecorded || call.approvalId != null) {
                    throw JsonSchemaV1.invalid(sourceName + " requests approval after call execution began.");
                }
                if (state.approvals.putIfAbsent(approvalId, new Approval(approvalId, callId)) != null) {
                    throw JsonSchemaV1.invalid(sourceName + " declares duplicate approvalId '" + approvalId + "'.");
                }
                call.approvalId = approvalId;
            }
            case "approvalDecision" -> {
                Approval approval = requireApproval(event.get("approvalId").textValue(), sourceName, state);
                if (approval.decision != null) {
                    throw JsonSchemaV1.invalid(sourceName + " records duplicate primary approval decision.");
                }
                ToolCall call = state.calls.get(approval.callId);
                boolean approved = event.get("approved").booleanValue();
                if (!approved && (call.invoked || call.resultRecorded)) {
                    throw JsonSchemaV1.invalid(sourceName + " rejects call after execution began.");
                }
                approval.decision = approved;
            }
            case "replayedApprovalDecision" -> {
                Approval approval = requireApproval(event.get("approvalId").textValue(), sourceName, state);
                if (approval.decision == null) {
                    throw JsonSchemaV1.invalid(sourceName + " replayed decision has no primary decision.");
                }
                if (!state.pendingReplayedApprovals.add(approval.approvalId)) {
                    throw JsonSchemaV1.invalid(sourceName + " repeats approval replay observation.");
                }
            }
            case "approvalReplayRejected" -> {
                String approvalId = event.get("approvalId").textValue();
                requireApproval(approvalId, sourceName, state);
                if (!state.pendingReplayedApprovals.remove(approvalId)) {
                    throw JsonSchemaV1.invalid(sourceName + " rejects an approval replay that was not observed.");
                }
                state.approvalReplayRejectionCount++;
            }
            case "assistantMessage", "textDelta" -> {
                state.assistantText.append(event.get("text").textValue());
                if (state.firstAssistantSequence < 0) {
                    state.firstAssistantSequence = event.get("sequence").intValue();
                }
            }
            case "terminal" -> applyToolTerminal(event, sourceName, state);
            default -> {
                // Shape-only events do not change correlation state.
            }
        }
    }

    private static void applyToolTerminal(JsonNode event, String sourceName, ToolLaneState state) {
        String outcome = event.get("outcome").textValue();
        if (!Set.of("success", "inputRequired", "cancelled", "failed").contains(outcome)) {
            throw JsonSchemaV1.invalid(sourceName + " has unknown terminal outcome '" + outcome + "'.");
        }
        state.terminalCount++;
        state.terminalOutcomes.add(outcome);
        if ("inputRequired".equals(outcome)) {
            boolean hasPendingApproval =
                    state.approvals.values().stream().anyMatch(approval -> approval.decision == null);
            if (!hasPendingApproval) {
                throw JsonSchemaV1.invalid(sourceName + " requires input without a pending approval.");
            }
            return;
        }
        state.finalTerminal = true;
        state.finalOutcome = outcome;
    }

    private static void finishToolLane(String sourceName, ToolLaneState state) {
        if (!state.finalTerminal) {
            throw JsonSchemaV1.invalid(sourceName + " must contain one final terminal event.");
        }
        if (!state.pendingDuplicateCalls.isEmpty()
                || !state.pendingReplayedCalls.isEmpty()
                || !state.pendingReplayedApprovals.isEmpty()) {
            throw JsonSchemaV1.invalid(sourceName + " contains an observed duplicate or replay without rejection.");
        }
        for (Approval approval : state.approvals.values()) {
            if (approval.decision == null) {
                throw JsonSchemaV1.invalid(sourceName + " leaves approval '" + approval.approvalId + "' pending.");
            }
        }
        if ("success".equals(state.finalOutcome)) {
            for (ToolCall call : state.calls.values()) {
                if (!call.resultRecorded) {
                    throw JsonSchemaV1.invalid(
                            sourceName + " completes successfully with unresolved call '" + call.callId + "'.");
                }
            }
        }
    }

    private static void applyWorkflowEvent(JsonNode event, String sourceName, WorkflowState state) {
        String type = event.get("type").textValue();
        switch (type) {
            case "workflowStarted" -> {
                if (state.workflowId != null) {
                    throw JsonSchemaV1.invalid(sourceName + " declares a second workflowId.");
                }
                state.workflowId = event.get("workflowId").textValue();
            }
            case "executorInvoked" -> {
                String executorId = event.get("executorId").textValue();
                if (!state.invokedExecutors.add(executorId)) {
                    throw JsonSchemaV1.invalid(sourceName + " invokes duplicate executorId '" + executorId + "'.");
                }
                state.activeExecutors.add(executorId);
                state.executorOrder.add(executorId);
            }
            case "executorCompleted" -> {
                String executorId = event.get("executorId").textValue();
                requireActiveExecutor(executorId, sourceName, state);
                state.activeExecutors.remove(executorId);
                state.completedExecutors.add(executorId);
            }
            case "executorFailed" -> {
                String executorId = event.get("executorId").textValue();
                requireActiveExecutor(executorId, sourceName, state);
                state.activeExecutors.remove(executorId);
                state.completedExecutors.add(executorId);
                state.failedExecutors.add(executorId);
            }
            case "cancellationRequested" -> {
                String executorId = event.get("executorId").textValue();
                requireActiveExecutor(executorId, sourceName, state);
                if (!state.cancellationRequested.add(executorId)) {
                    throw JsonSchemaV1.invalid(sourceName + " repeats cancellation for executor '" + executorId + "'.");
                }
            }
            case "executorCancelled" -> {
                String executorId = event.get("executorId").textValue();
                requireActiveExecutor(executorId, sourceName, state);
                if (!state.cancellationRequested.remove(executorId)) {
                    throw JsonSchemaV1.invalid(sourceName + " cancels executor without a correlated request.");
                }
                state.activeExecutors.remove(executorId);
                state.completedExecutors.add(executorId);
                state.cancelledExecutors.add(executorId);
            }
            case "workflowOutput" -> {
                String executorId = optionalText(event, "executorId", sourceName);
                if (executorId != null) {
                    requireActiveExecutor(executorId, sourceName, state);
                }
                state.outputs.add(event.get("value").deepCopy());
            }
            case "fanOut" -> {
                String sourceId = optionalText(event, "sourceId", sourceName);
                if (sourceId != null && !state.completedExecutors.contains(sourceId)) {
                    throw JsonSchemaV1.invalid(sourceName + " fan-out source '" + sourceId + "' has not completed.");
                }
                JsonSchemaV1.requireTextArray(event, "targetIds", sourceName, true, true)
                        .forEach(targetId -> state.fanOutDeliveries.merge(targetId, 1, Integer::sum));
            }
            case "fanInBuffered" -> {
                String sourceId = event.get("sourceId").textValue();
                String targetId = event.get("targetId").textValue();
                LinkedHashSet<String> buffered =
                        state.bufferedSources.computeIfAbsent(targetId, ignored -> new LinkedHashSet<>());
                if (!buffered.add(sourceId)) {
                    throw JsonSchemaV1.invalid(sourceName + " buffers duplicate sourceId '" + sourceId
                            + "' for target '" + targetId + "'.");
                }
            }
            case "fanInReleased" -> {
                String targetId = event.get("targetId").textValue();
                List<String> sourceIds = JsonSchemaV1.requireTextArray(event, "sourceIds", sourceName, true, true);
                Set<String> buffered = state.bufferedSources.get(targetId);
                if (buffered == null || !List.copyOf(buffered).equals(sourceIds)) {
                    throw JsonSchemaV1.invalid(
                            sourceName + " fan-in sources do not match buffered order for target '" + targetId + "'.");
                }
                if (event.get("values").size() != sourceIds.size()) {
                    throw JsonSchemaV1.invalid(sourceName + " fan-in values must align with sourceIds.");
                }
                state.fanInReleaseCount++;
                if (event.has("epoch")) {
                    state.fanInEpochs.add(event.get("epoch").longValue());
                }
                state.fanInValues = event.get("values").deepCopy();
                state.bufferedSources.remove(targetId);
            }
            case "checkpointSaved" -> saveCheckpoint(event, sourceName, state);
            case "checkpointLoaded" -> {
                String checkpointId = event.get("checkpointId").textValue();
                int revision = event.get("revision").intValue();
                Integer knownRevision = state.checkpoints.get(checkpointId);
                if (knownRevision == null || knownRevision != revision) {
                    throw JsonSchemaV1.invalid(sourceName + " loads unknown checkpoint or revision '" + checkpointId
                            + "' revision " + revision + ".");
                }
                state.loadedCheckpointId = checkpointId;
                state.lastLoadedCheckpointId = checkpointId;
                state.resumed = false;
            }
            case "workflowResumed" -> {
                String checkpointId = event.get("checkpointId").textValue();
                if (!checkpointId.equals(state.loadedCheckpointId)) {
                    throw JsonSchemaV1.invalid(sourceName + " resumes a checkpoint that was not loaded.");
                }
                state.resumed = true;
            }
            case "terminal" -> {
                String outcome = event.get("outcome").textValue();
                if (!Set.of("idle", "failed", "cancelled").contains(outcome)) {
                    throw JsonSchemaV1.invalid(sourceName + " has unknown terminal outcome '" + outcome + "'.");
                }
                if ("idle".equals(outcome) && (!state.activeExecutors.isEmpty() || !state.bufferedSources.isEmpty())) {
                    throw JsonSchemaV1.invalid(sourceName + " reaches idle with active executors or buffered fan-in.");
                }
                state.terminalCount++;
                state.terminalOutcome = outcome;
                state.terminal = true;
            }
            default -> {
                // Shape-only events do not change correlation state.
            }
        }
    }

    private static void saveCheckpoint(JsonNode event, String sourceName, WorkflowState state) {
        String checkpointId = event.get("checkpointId").textValue();
        int revision = event.get("revision").intValue();
        JsonNode previousNode = event.get("previousCheckpointId");
        String previousCheckpointId = previousNode.isNull() ? null : previousNode.textValue();
        if (state.checkpoints.containsKey(checkpointId)) {
            throw JsonSchemaV1.invalid(sourceName + " declares duplicate checkpointId '" + checkpointId + "'.");
        }
        if (!java.util.Objects.equals(previousCheckpointId, state.lastCheckpointId)) {
            throw JsonSchemaV1.invalid(sourceName + " previousCheckpointId must reference the latest checkpoint.");
        }
        if (revision <= state.lastCheckpointRevision) {
            throw JsonSchemaV1.invalid(sourceName + " checkpoint revision must increase deterministically.");
        }
        state.checkpoints.put(checkpointId, revision);
        state.checkpointParents.put(checkpointId, previousCheckpointId);
        state.lastCheckpointId = checkpointId;
        state.lastCheckpointRevision = revision;
    }

    private static void registerCall(
            String callId,
            String invocationId,
            String logicalRunId,
            List<String> executionViews,
            String sourceName,
            ToolLaneState state) {
        if (state.calls.containsKey(callId)) {
            throw JsonSchemaV1.invalid(sourceName + " declares duplicate callId '" + callId + "'.");
        }
        ToolCall call = new ToolCall(callId, invocationId, logicalRunId, executionViews);
        state.calls.put(callId, call);
        state.callOrder.add(callId);
        if (invocationId != null) {
            String previous = state.callsByInvocationId.putIfAbsent(invocationId, callId);
            if (previous != null) {
                throw JsonSchemaV1.invalid(sourceName + " declares duplicate invocationId '" + invocationId + "'.");
            }
        }
    }

    private static ToolCall resolveInvokedCall(JsonNode event, String sourceName, ToolLaneState state) {
        String callId = optionalText(event, "callId", sourceName);
        String invocationId = optionalText(event, "invocationId", sourceName);
        ToolCall call;
        if (callId != null) {
            call = requireCall(callId, sourceName, state);
            if (invocationId != null) {
                requireInvocation(invocationId, call, sourceName);
            }
        } else {
            String resolvedCallId = state.callsByInvocationId.get(invocationId);
            if (resolvedCallId == null) {
                throw JsonSchemaV1.invalid(sourceName + " references orphan invocationId '" + invocationId + "'.");
            }
            call = state.calls.get(resolvedCallId);
        }
        return call;
    }

    private static ToolCall requireCall(String callId, String sourceName, ToolLaneState state) {
        ToolCall call = state.calls.get(callId);
        if (call == null) {
            throw JsonSchemaV1.invalid(sourceName + " references orphan callId '" + callId + "'.");
        }
        return call;
    }

    private static Approval requireApproval(String approvalId, String sourceName, ToolLaneState state) {
        Approval approval = state.approvals.get(approvalId);
        if (approval == null) {
            throw JsonSchemaV1.invalid(sourceName + " references orphan approvalId '" + approvalId + "'.");
        }
        return approval;
    }

    private static void requireInvocation(String invocationId, ToolCall call, String sourceName) {
        if (call.invocationId == null || !call.invocationId.equals(invocationId)) {
            throw JsonSchemaV1.invalid(sourceName + " invocationId does not correlate to call '" + call.callId + "'.");
        }
    }

    private static void requireActiveExecutor(String executorId, String sourceName, WorkflowState state) {
        if (!state.activeExecutors.contains(executorId)) {
            throw JsonSchemaV1.invalid(sourceName + " references inactive executorId '" + executorId + "'.");
        }
    }

    private static void requireSubscribed(boolean subscribed, String sourceName) {
        if (!subscribed) {
            throw JsonSchemaV1.invalid(sourceName + " appears before subscription.");
        }
    }

    private static void validateSequence(JsonNode event, String sourceName, int expected) {
        int actual = JsonSchemaV1.requireNonNegativeInteger(event, "sequence", sourceName);
        if (actual != expected) {
            throw JsonSchemaV1.invalid(
                    JsonSchemaV1.path(sourceName, "sequence") + " must be " + expected + " but was " + actual + ".");
        }
    }

    private static void event(
            JsonNode event, String sourceName, List<String> requiredEventFields, List<String> optionalEventFields) {
        java.util.ArrayList<String> required = new java.util.ArrayList<>(List.of("sequence", "type"));
        required.addAll(requiredEventFields);
        JsonSchemaV1.object(event, sourceName, required, optionalEventFields);
        JsonSchemaV1.requireNonNegativeInteger(event, "sequence", sourceName);
        JsonSchemaV1.requireText(event, "type", sourceName);
        if (event.has("mode")) {
            JsonSchemaV1.requireText(event, "mode", sourceName);
        }
    }

    private static String optionalText(JsonNode event, String field, String sourceName) {
        return JsonSchemaV1.optionalText(event, field, sourceName);
    }

    private static void requireEventObject(JsonNode event, String sourceName) {
        if (!event.isObject()) {
            throw JsonSchemaV1.invalid(sourceName + " must be a JSON object.");
        }
        if (event.isEmpty()) {
            throw JsonSchemaV1.invalid(sourceName + " must not be an empty event object.");
        }
    }

    static record ToolHistory(Map<String, ToolLaneSummary> lanes) {
        ToolHistory {
            lanes = Map.copyOf(lanes);
        }

        ToolLaneSummary requireLane(String lane) {
            ToolLaneSummary summary = lanes.get(lane);
            if (summary == null) {
                throw JsonSchemaV1.invalid("Tool history does not contain lane '" + lane + "'.");
            }
            return summary;
        }
    }

    static record ToolLaneSummary(
            Map<String, Integer> invocationCounts,
            List<String> callOrder,
            List<String> resultOrder,
            Map<String, JsonNode> results,
            Map<String, String> resultOutcomes,
            String assistantText,
            int terminalCount,
            List<String> terminalOutcomes,
            int duplicateCallRejectionCount,
            int replayRejectionCount,
            int approvalReplayRejectionCount,
            Map<String, Boolean> approvalDecisions,
            int invocationsAfterApproval,
            int invocationsWithoutApproval,
            int lastResultSequence,
            int firstAssistantSequence,
            Map<String, String> invocationIds,
            Map<String, String> logicalRunIds,
            Map<String, List<String>> executionViews) {
        ToolLaneSummary {
            invocationCounts = Map.copyOf(invocationCounts);
            callOrder = List.copyOf(callOrder);
            resultOrder = List.copyOf(resultOrder);
            LinkedHashMap<String, JsonNode> resultCopies = new LinkedHashMap<>();
            results.forEach((key, value) -> resultCopies.put(key, value.deepCopy()));
            results = Map.copyOf(resultCopies);
            resultOutcomes = Map.copyOf(resultOutcomes);
            terminalOutcomes = List.copyOf(terminalOutcomes);
            approvalDecisions = Map.copyOf(approvalDecisions);
            invocationIds = Map.copyOf(invocationIds);
            logicalRunIds = Map.copyOf(logicalRunIds);
            LinkedHashMap<String, List<String>> viewCopies = new LinkedHashMap<>();
            executionViews.forEach((key, value) -> viewCopies.put(key, List.copyOf(value)));
            executionViews = Map.copyOf(viewCopies);
        }

        int totalInvocationCount() {
            return invocationCounts.values().stream()
                    .mapToInt(Integer::intValue)
                    .sum();
        }

        int functionResultCount() {
            return resultOrder.size();
        }

        boolean resultPrecedesAssistantText() {
            return lastResultSequence >= 0
                    && firstAssistantSequence >= 0
                    && lastResultSequence < firstAssistantSequence;
        }
    }

    static record RunSignalSummary(
            List<Boolean> cancelReturnValues,
            int terminalCount,
            String terminalOutcome,
            int updatesAfterCancellation,
            boolean successAfterTerminal) {
        RunSignalSummary {
            cancelReturnValues = List.copyOf(cancelReturnValues);
        }
    }

    static record WorkflowSummary(
            List<String> executorOrder,
            List<JsonNode> outputs,
            Map<String, Integer> fanOutDeliveries,
            int fanInReleaseCount,
            List<Long> fanInEpochs,
            List<JsonNode> fanInValues,
            List<String> failedExecutors,
            List<String> cancelledExecutors,
            String loadedCheckpointId,
            Map<String, String> checkpointParents,
            int duplicateBufferedValues,
            int terminalCount,
            String terminalOutcome,
            boolean successAfterTerminal) {
        WorkflowSummary {
            executorOrder = List.copyOf(executorOrder);
            outputs = copyNodes(outputs);
            fanOutDeliveries = Map.copyOf(fanOutDeliveries);
            fanInEpochs = List.copyOf(fanInEpochs);
            fanInValues = copyNodes(fanInValues);
            failedExecutors = List.copyOf(failedExecutors);
            cancelledExecutors = List.copyOf(cancelledExecutors);
            checkpointParents = Collections.unmodifiableMap(new LinkedHashMap<>(checkpointParents));
        }
    }

    static record WorkflowSeed(
            Map<String, Integer> checkpoints,
            String lastCheckpointId,
            Map<String, List<String>> bufferedSources,
            List<String> pendingExecutors) {
        WorkflowSeed {
            checkpoints = Map.copyOf(checkpoints);
            LinkedHashMap<String, List<String>> buffers = new LinkedHashMap<>();
            bufferedSources.forEach((target, sources) -> buffers.put(target, List.copyOf(sources)));
            bufferedSources = Map.copyOf(buffers);
            pendingExecutors = List.copyOf(pendingExecutors);
        }

        static WorkflowSeed empty() {
            return new WorkflowSeed(Map.of(), null, Map.of(), List.of());
        }
    }

    private static final class ToolLaneState {
        private final Map<String, ToolCall> calls = new LinkedHashMap<>();

        private final Map<String, String> callsByInvocationId = new HashMap<>();

        private final Map<String, Approval> approvals = new LinkedHashMap<>();

        private final Map<String, Integer> invocationCounts = new LinkedHashMap<>();

        private final List<String> callOrder = new ArrayList<>();

        private final List<String> resultOrder = new ArrayList<>();

        private final Map<String, JsonNode> results = new LinkedHashMap<>();

        private final Map<String, String> resultOutcomes = new LinkedHashMap<>();

        private final StringBuilder assistantText = new StringBuilder();

        private final List<String> terminalOutcomes = new ArrayList<>();

        private final Set<String> pendingDuplicateCalls = new HashSet<>();

        private final Set<String> pendingReplayedCalls = new HashSet<>();

        private final Set<String> pendingReplayedApprovals = new HashSet<>();

        private int nextSequence;

        private int terminalCount;

        private int duplicateCallRejectionCount;

        private int replayRejectionCount;

        private int approvalReplayRejectionCount;

        private int invocationsAfterApproval;

        private int invocationsWithoutApproval;

        private int lastResultSequence = -1;

        private int firstAssistantSequence = -1;

        private boolean finalTerminal;

        private String finalOutcome;

        private ToolLaneSummary summary() {
            LinkedHashMap<String, Boolean> decisions = new LinkedHashMap<>();
            approvals.forEach((approvalId, approval) -> decisions.put(approvalId, approval.decision));
            LinkedHashMap<String, String> invocationIds = new LinkedHashMap<>();
            LinkedHashMap<String, String> logicalRunIds = new LinkedHashMap<>();
            LinkedHashMap<String, List<String>> executionViews = new LinkedHashMap<>();
            calls.forEach((callId, call) -> {
                if (call.invocationId != null) {
                    invocationIds.put(callId, call.invocationId);
                }
                if (call.logicalRunId != null) {
                    logicalRunIds.put(callId, call.logicalRunId);
                }
                if (!call.executionViews.isEmpty()) {
                    executionViews.put(callId, call.executionViews);
                }
            });
            return new ToolLaneSummary(
                    invocationCounts,
                    callOrder,
                    resultOrder,
                    results,
                    resultOutcomes,
                    assistantText.toString(),
                    terminalCount,
                    terminalOutcomes,
                    duplicateCallRejectionCount,
                    replayRejectionCount,
                    approvalReplayRejectionCount,
                    decisions,
                    invocationsAfterApproval,
                    invocationsWithoutApproval,
                    lastResultSequence,
                    firstAssistantSequence,
                    invocationIds,
                    logicalRunIds,
                    executionViews);
        }
    }

    private static final class ToolCall {
        private final String callId;

        private final String invocationId;

        private final String logicalRunId;

        private final List<String> executionViews;

        private String approvalId;

        private boolean invoked;

        private boolean failed;

        private boolean resultRecorded;

        private String resultOutcome;

        private ToolCall(String callId, String invocationId, String logicalRunId, List<String> executionViews) {
            this.callId = callId;
            this.invocationId = invocationId;
            this.logicalRunId = logicalRunId;
            this.executionViews = List.copyOf(executionViews);
        }
    }

    private static final class Approval {
        private final String approvalId;

        private final String callId;

        private Boolean decision;

        private Approval(String approvalId, String callId) {
            this.approvalId = approvalId;
            this.callId = callId;
        }
    }

    private static final class WorkflowState {
        private final Set<String> invokedExecutors = new HashSet<>();

        private final Set<String> activeExecutors = new HashSet<>();

        private final Set<String> completedExecutors = new HashSet<>();

        private final Set<String> cancellationRequested = new HashSet<>();

        private final Map<String, LinkedHashSet<String>> bufferedSources = new LinkedHashMap<>();

        private final Map<String, Integer> checkpoints = new LinkedHashMap<>();

        private final Map<String, String> checkpointParents = new LinkedHashMap<>();

        private final List<String> executorOrder = new ArrayList<>();

        private final List<JsonNode> outputs = new ArrayList<>();

        private final Map<String, Integer> fanOutDeliveries = new LinkedHashMap<>();

        private final List<String> failedExecutors = new ArrayList<>();

        private final List<String> cancelledExecutors = new ArrayList<>();

        private String workflowId;

        private String lastCheckpointId;

        private int lastCheckpointRevision;

        private String loadedCheckpointId;

        private String lastLoadedCheckpointId;

        private JsonNode fanInValues;

        private final List<Long> fanInEpochs = new ArrayList<>();

        private int fanInReleaseCount;

        private int duplicateBufferedValues;

        private int terminalCount;

        private String terminalOutcome;

        private boolean resumed;

        private boolean terminal;

        private WorkflowState(WorkflowSeed seed) {
            checkpoints.putAll(seed.checkpoints());
            lastCheckpointId = seed.lastCheckpointId();
            if (lastCheckpointId != null) {
                lastCheckpointRevision = checkpoints.get(lastCheckpointId);
            }
            seed.bufferedSources()
                    .forEach((target, sources) -> bufferedSources.put(target, new LinkedHashSet<>(sources)));
            invokedExecutors.addAll(seed.pendingExecutors());
            activeExecutors.addAll(seed.pendingExecutors());
        }

        private WorkflowSummary summary() {
            return new WorkflowSummary(
                    executorOrder,
                    outputs,
                    fanOutDeliveries,
                    fanInReleaseCount,
                    fanInEpochs,
                    fanInValues == null ? List.of() : copyNodes(fanInValues),
                    failedExecutors,
                    cancelledExecutors,
                    lastLoadedCheckpointId,
                    checkpointParents,
                    duplicateBufferedValues,
                    terminalCount,
                    terminalOutcome,
                    false);
        }
    }

    private static List<JsonNode> copyNodes(Iterable<JsonNode> nodes) {
        ArrayList<JsonNode> copies = new ArrayList<>();
        nodes.forEach(node -> copies.add(node.deepCopy()));
        return List.copyOf(copies);
    }
}
