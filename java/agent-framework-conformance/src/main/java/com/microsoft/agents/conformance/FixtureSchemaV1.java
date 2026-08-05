// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit nested schemas and semantic checks for fixture schema version 1. */
final class FixtureSchemaV1 {
    private FixtureSchemaV1() {}

    static void validate(JsonNode root, FixtureKind kind, String sourceName) {
        switch (kind) {
            case CONTRACT -> validateContract(root, sourceName);
            case MESSAGE_CONTENT -> validateMessageContent(root, sourceName);
            case RESPONSE_AGGREGATION -> validateResponseAggregation(root, sourceName);
            case TOOL_LOOP -> validateToolLoop(root, sourceName);
            case RUN_SIGNAL -> validateRunSignal(root, sourceName);
            case SESSION_SNAPSHOT -> validateSessionSnapshot(root, sourceName);
            case WORKFLOW_TRACE -> validateWorkflowTrace(root, sourceName);
            case WORKFLOW_CHECKPOINT -> validateWorkflowCheckpoint(root, sourceName);
        }
    }

    private static void validateMessageContent(JsonNode root, String sourceName) {
        JsonNode input = requiredNonEmptyObject(root, "input", sourceName);
        JsonNode expected = requiredNonEmptyObject(root, "expected", sourceName);
        JsonSchemaV1.exactObject(input, JsonSchemaV1.path(sourceName, "input"), "messages");
        MessageState messageState = validateMessages(
                JsonSchemaV1.requireArray(input, "messages", JsonSchemaV1.path(sourceName, "input"), true),
                JsonSchemaV1.path(JsonSchemaV1.path(sourceName, "input"), "messages"));

        String expectedSource = JsonSchemaV1.path(sourceName, "expected");
        JsonSchemaV1.exactObject(
                expected, expectedSource, "roles", "contentKinds", "messageTexts", "assistantText", "callResultPairs");
        List<String> expectedRoles = JsonSchemaV1.requireTextArray(expected, "roles", expectedSource, true, false);
        if (!expectedRoles.equals(messageState.roles)) {
            throw JsonSchemaV1.invalid(
                    JsonSchemaV1.path(expectedSource, "roles") + " must preserve input message order.");
        }
        List<String> expectedContentKinds =
                JsonSchemaV1.requireTextArray(expected, "contentKinds", expectedSource, true, false);
        if (!expectedContentKinds.equals(messageState.contentKinds)) {
            throw JsonSchemaV1.invalid(
                    JsonSchemaV1.path(expectedSource, "contentKinds") + " must preserve input content order.");
        }
        List<String> expectedMessageTexts = requireStringArray(expected, "messageTexts", expectedSource);
        if (!expectedMessageTexts.equals(messageState.messageTexts)) {
            throw JsonSchemaV1.invalid(
                    JsonSchemaV1.path(expectedSource, "messageTexts") + " must match space-joined message text.");
        }
        String expectedAssistantText = JsonSchemaV1.requireString(expected, "assistantText", expectedSource);
        if (!expectedAssistantText.equals(messageState.assistantText.toString())) {
            throw JsonSchemaV1.invalid(
                    JsonSchemaV1.path(expectedSource, "assistantText") + " must match assistant text contents.");
        }
        JsonNode pairs = JsonSchemaV1.requireArray(expected, "callResultPairs", expectedSource, false);
        Set<String> pairIds = new HashSet<>();
        for (int index = 0; index < pairs.size(); index++) {
            JsonNode pair = pairs.get(index);
            String pairSource = JsonSchemaV1.indexed(JsonSchemaV1.path(expectedSource, "callResultPairs"), index);
            JsonSchemaV1.exactObject(pair, pairSource, "callId", "resultCount");
            String callId = JsonSchemaV1.requireText(pair, "callId", pairSource);
            int resultCount = JsonSchemaV1.requireNonNegativeInteger(pair, "resultCount", pairSource);
            if (!pairIds.add(callId) || !messageState.calls.contains(callId)) {
                throw JsonSchemaV1.invalid(pairSource + " must reference one unique declared callId.");
            }
            if (messageState.resultCounts.getOrDefault(callId, 0) != resultCount) {
                throw JsonSchemaV1.invalid(pairSource + " resultCount does not match correlated message contents.");
            }
        }
        if (!pairIds.equals(messageState.calls)) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(expectedSource, "callResultPairs")
                    + " must cover every declared callId exactly once.");
        }
    }

    private static void validateResponseAggregation(JsonNode root, String sourceName) {
        JsonNode input = requiredNonEmptyObject(root, "input", sourceName);
        JsonNode expected = requiredNonEmptyObject(root, "expected", sourceName);
        String inputSource = JsonSchemaV1.path(sourceName, "input");
        JsonSchemaV1.exactObject(input, inputSource, "updates");
        JsonNode updates = JsonSchemaV1.requireArray(input, "updates", inputSource, true);
        String aggregateRole = null;
        StringBuilder aggregateText = new StringBuilder();
        ArrayList<JsonNode> usageUpdates = new ArrayList<>();
        String aggregateFinishReason = null;
        boolean finishSeen = false;
        for (int index = 0; index < updates.size(); index++) {
            JsonNode update = updates.get(index);
            String updateSource = JsonSchemaV1.indexed(JsonSchemaV1.path(inputSource, "updates"), index);
            JsonSchemaV1.object(
                    update, updateSource, List.of("sequence"), List.of("role", "contents", "usage", "finishReason"));
            int sequence = JsonSchemaV1.requireNonNegativeInteger(update, "sequence", updateSource);
            if (sequence != index) {
                throw JsonSchemaV1.invalid(JsonSchemaV1.path(updateSource, "sequence") + " must be " + index
                        + " but was " + sequence + ".");
            }
            if (update.size() == 1) {
                throw JsonSchemaV1.invalid(updateSource + " must carry response data.");
            }
            if (update.has("contents")) {
                String role = JsonSchemaV1.requireText(update, "role", updateSource);
                if (aggregateRole == null) {
                    aggregateRole = role;
                } else if (!aggregateRole.equals(role)) {
                    throw JsonSchemaV1.invalid(updateSource + " role must match preceding response updates.");
                }
                aggregateText.append(validateTextContents(
                        JsonSchemaV1.requireArray(update, "contents", updateSource, true),
                        JsonSchemaV1.path(updateSource, "contents")));
            } else if (update.has("role")) {
                throw JsonSchemaV1.invalid(updateSource + " role requires contents.");
            }
            if (update.has("usage")) {
                JsonNode usage = JsonSchemaV1.requireObject(update, "usage", updateSource);
                validateUsageInput(usage, JsonSchemaV1.path(updateSource, "usage"));
                usageUpdates.add(usage);
            }
            if (update.has("finishReason")) {
                aggregateFinishReason = JsonSchemaV1.requireText(update, "finishReason", updateSource);
                if (finishSeen || index != updates.size() - 1) {
                    throw JsonSchemaV1.invalid(updateSource + " finishReason must occur once on the final update.");
                }
                finishSeen = true;
            }
        }
        if (!finishSeen) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(inputSource, "updates") + " must end with a finishReason.");
        }
        ObjectNode aggregateUsage = aggregateUsage(usageUpdates);
        if (aggregateRole == null || usageUpdates.isEmpty() || aggregateUsage.isEmpty()) {
            throw JsonSchemaV1.invalid(
                    JsonSchemaV1.path(inputSource, "updates") + " must aggregate role, text, and usage data.");
        }

        String expectedSource = JsonSchemaV1.path(sourceName, "expected");
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "messageCount",
                "role",
                "text",
                "usage",
                "droppedUsageKeys",
                "finishReason",
                "updateSequences");
        if (JsonSchemaV1.requireNonNegativeInteger(expected, "messageCount", expectedSource) != 1) {
            throw JsonSchemaV1.invalid(
                    JsonSchemaV1.path(expectedSource, "messageCount") + " must be 1 for one aggregated response.");
        }
        if (!aggregateRole.equals(JsonSchemaV1.requireText(expected, "role", expectedSource))) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(expectedSource, "role") + " must match aggregated updates.");
        }
        if (!aggregateText.toString().equals(JsonSchemaV1.requireString(expected, "text", expectedSource))) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(expectedSource, "text") + " must match aggregated updates.");
        }
        JsonNode expectedUsage = JsonSchemaV1.requireObject(expected, "usage", expectedSource);
        validateAggregatedUsage(expectedUsage, JsonSchemaV1.path(expectedSource, "usage"));
        if (!numericJsonEquals(aggregateUsage, expectedUsage)) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(expectedSource, "usage")
                    + " must equal sequentially folded integral usage across updates.");
        }
        LinkedHashSet<String> droppedUsageKeys = new LinkedHashSet<>();
        usageUpdates.forEach(usage -> usage.fieldNames().forEachRemaining(droppedUsageKeys::add));
        aggregateUsage.fieldNames().forEachRemaining(droppedUsageKeys::remove);
        List<String> expectedDroppedUsageKeys =
                JsonSchemaV1.requireTextArray(expected, "droppedUsageKeys", expectedSource, true, true);
        if (!expectedDroppedUsageKeys.equals(List.copyOf(droppedUsageKeys))) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(expectedSource, "droppedUsageKeys")
                    + " must list every input usage key omitted by the final fold.");
        }
        if (!aggregateFinishReason.equals(JsonSchemaV1.requireText(expected, "finishReason", expectedSource))) {
            throw JsonSchemaV1.invalid(
                    JsonSchemaV1.path(expectedSource, "finishReason") + " must match the final update.");
        }
        JsonSchemaV1.requireIntegerArray(expected, "updateSequences", expectedSource, true);
        JsonNode expectedSequences = expected.get("updateSequences");
        if (expectedSequences.size() != updates.size()) {
            throw JsonSchemaV1.invalid(
                    JsonSchemaV1.path(expectedSource, "updateSequences") + " must cover every input update.");
        }
        for (int index = 0; index < expectedSequences.size(); index++) {
            if (expectedSequences.get(index).intValue() != index) {
                throw JsonSchemaV1.invalid(
                        JsonSchemaV1.path(expectedSource, "updateSequences") + " must preserve update order.");
            }
        }
    }

    private static void validateToolLoop(JsonNode root, String sourceName) {
        JsonNode events = JsonSchemaV1.requireArray(root, "events", sourceName, true);
        EventSchemaV1.ToolHistory history =
                EventSchemaV1.validateToolEvents(events, JsonSchemaV1.path(sourceName, "events"));
        validateToolExpected(
                JsonSchemaV1.requireText(root, "caseId", sourceName),
                requiredNonEmptyObject(root, "expected", sourceName),
                JsonSchemaV1.path(sourceName, "expected"),
                history);
    }

    private static void validateRunSignal(JsonNode root, String sourceName) {
        JsonNode events = JsonSchemaV1.requireArray(root, "events", sourceName, true);
        EventSchemaV1.RunSignalSummary summary =
                EventSchemaV1.validateRunSignalEvents(events, JsonSchemaV1.path(sourceName, "events"));
        JsonNode expected = requiredNonEmptyObject(root, "expected", sourceName);
        String expectedSource = JsonSchemaV1.path(sourceName, "expected");
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "cancelReturnValues",
                "terminalCount",
                "terminalOutcome",
                "updatesAfterCancellation",
                "successAfterTerminal");
        JsonSchemaV1.requireBooleanArray(expected, "cancelReturnValues", expectedSource, true);
        JsonSchemaV1.requireNonNegativeInteger(expected, "terminalCount", expectedSource);
        JsonSchemaV1.requireText(expected, "terminalOutcome", expectedSource);
        JsonSchemaV1.requireNonNegativeInteger(expected, "updatesAfterCancellation", expectedSource);
        JsonSchemaV1.requireBoolean(expected, "successAfterTerminal", expectedSource);
        requireExpectedJson(expected, "cancelReturnValues", booleans(summary.cancelReturnValues()), expectedSource);
        requireExpectedCount(expected, "terminalCount", summary.terminalCount(), expectedSource);
        requireExpectedText(expected, "terminalOutcome", summary.terminalOutcome(), expectedSource);
        requireExpectedCount(expected, "updatesAfterCancellation", summary.updatesAfterCancellation(), expectedSource);
        requireExpectedBoolean(expected, "successAfterTerminal", summary.successAfterTerminal(), expectedSource);
    }

    private static void validateSessionSnapshot(JsonNode root, String sourceName) {
        JsonNode envelope = JsonSchemaV1.requireObject(root, "envelope", sourceName);
        validateEnvelope(envelope, "agent-session", JsonSchemaV1.path(sourceName, "envelope"));
        JsonNode payload = envelope.get("payload");
        String payloadSource = JsonSchemaV1.path(JsonSchemaV1.path(sourceName, "envelope"), "payload");
        JsonSchemaV1.object(payload, payloadSource, List.of("sessionId"), List.of("messages", "state"));
        JsonSchemaV1.requireText(payload, "sessionId", payloadSource);
        if (payload.has("messages")) {
            validateMessages(
                    JsonSchemaV1.requireArray(payload, "messages", payloadSource, true),
                    JsonSchemaV1.path(payloadSource, "messages"));
        }
        if (payload.has("state") && !payload.get("state").isObject()) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(payloadSource, "state") + " must be a JSON object.");
        }

        JsonNode operations = JsonSchemaV1.requireArray(root, "operations", sourceName, true);
        for (int index = 0; index < operations.size(); index++) {
            validateSessionOperation(
                    operations.get(index), JsonSchemaV1.indexed(JsonSchemaV1.path(sourceName, "operations"), index));
        }
        validateSessionExpected(
                JsonSchemaV1.requireText(root, "caseId", sourceName),
                requiredNonEmptyObject(root, "expected", sourceName),
                JsonSchemaV1.path(sourceName, "expected"),
                envelope,
                operations);
    }

    private static void validateWorkflowTrace(JsonNode root, String sourceName) {
        JsonNode events = JsonSchemaV1.requireArray(root, "events", sourceName, true);
        EventSchemaV1.WorkflowSummary summary =
                EventSchemaV1.validateWorkflowEvents(events, JsonSchemaV1.path(sourceName, "events"));
        validateWorkflowExpected(
                JsonSchemaV1.requireText(root, "caseId", sourceName),
                requiredNonEmptyObject(root, "expected", sourceName),
                JsonSchemaV1.path(sourceName, "expected"),
                summary);
    }

    private static void validateWorkflowCheckpoint(JsonNode root, String sourceName) {
        JsonNode envelope = JsonSchemaV1.requireObject(root, "envelope", sourceName);
        String envelopeSource = JsonSchemaV1.path(sourceName, "envelope");
        validateEnvelope(envelope, "workflow-checkpoint", envelopeSource);
        JsonNode payload = envelope.get("payload");
        String payloadSource = JsonSchemaV1.path(envelopeSource, "payload");
        JsonSchemaV1.exactObject(
                payload,
                payloadSource,
                "workflowId",
                "checkpointId",
                "revision",
                "previousCheckpointId",
                "status",
                "bufferedInputs",
                "pendingExecutors");
        JsonSchemaV1.requireText(payload, "workflowId", payloadSource);
        String checkpointId = JsonSchemaV1.requireText(payload, "checkpointId", payloadSource);
        int revision = JsonSchemaV1.requirePositiveInteger(payload, "revision", payloadSource);
        JsonNode previous = JsonSchemaV1.requireNullable(payload, "previousCheckpointId", payloadSource);
        if (!previous.isNull() && (!previous.isTextual() || previous.textValue().isBlank())) {
            throw JsonSchemaV1.invalid(
                    JsonSchemaV1.path(payloadSource, "previousCheckpointId") + " must be null or a non-blank string.");
        }
        JsonSchemaV1.requireText(payload, "status", payloadSource);
        List<String> pendingExecutors =
                JsonSchemaV1.requireTextArray(payload, "pendingExecutors", payloadSource, true, true);

        JsonNode bufferedInputs = JsonSchemaV1.requireArray(payload, "bufferedInputs", payloadSource, true);
        LinkedHashMap<String, List<String>> bufferedSources = new LinkedHashMap<>();
        Map<String, ArrayList<String>> mutableSources = new LinkedHashMap<>();
        Set<String> bufferIds = new HashSet<>();
        for (int index = 0; index < bufferedInputs.size(); index++) {
            JsonNode buffered = bufferedInputs.get(index);
            String bufferedSource = JsonSchemaV1.indexed(JsonSchemaV1.path(payloadSource, "bufferedInputs"), index);
            JsonSchemaV1.exactObject(buffered, bufferedSource, "sourceId", "targetId", "value");
            String sourceId = JsonSchemaV1.requireText(buffered, "sourceId", bufferedSource);
            String targetId = JsonSchemaV1.requireText(buffered, "targetId", bufferedSource);
            JsonSchemaV1.require(buffered, "value", bufferedSource);
            if (!bufferIds.add(targetId + "\u0000" + sourceId)) {
                throw JsonSchemaV1.invalid(bufferedSource + " duplicates a buffered source/target identity.");
            }
            mutableSources
                    .computeIfAbsent(targetId, ignored -> new ArrayList<>())
                    .add(sourceId);
        }
        mutableSources.forEach((target, sources) -> {
            sources.sort(String::compareTo);
            bufferedSources.put(target, List.copyOf(sources));
        });

        String encoded = JsonSchemaV1.requireString(root, "encoded", sourceName);
        try {
            String actualEncoding = CheckpointCanonicalizer.encode(envelope);
            if (!actualEncoding.equals(encoded)) {
                throw JsonSchemaV1.invalid(
                        JsonSchemaV1.path(sourceName, "encoded") + " must exactly match canonical envelope encoding.");
            }
        } catch (JsonProcessingException exception) {
            throw new ConformanceValidationException(sourceName + " envelope cannot be encoded.", exception);
        }

        JsonNode resumeEvents = JsonSchemaV1.requireArray(root, "resumeEvents", sourceName, true);
        EventSchemaV1.WorkflowSeed seed = new EventSchemaV1.WorkflowSeed(
                Map.of(checkpointId, revision), checkpointId, bufferedSources, pendingExecutors);
        EventSchemaV1.WorkflowSummary resumeSummary =
                EventSchemaV1.validateWorkflowEvents(resumeEvents, JsonSchemaV1.path(sourceName, "resumeEvents"), seed);

        JsonNode expected = requiredNonEmptyObject(root, "expected", sourceName);
        String expectedSource = JsonSchemaV1.path(sourceName, "expected");
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "decodedCheckpointId",
                "decodedPendingExecutors",
                "decodedBufferedInputOrder",
                "resumeFanInValues",
                "terminalOutcome",
                "deterministicEncoding",
                "roundTripWithinJavaV1",
                "wrongDocumentKindRejected",
                "unsupportedPayloadVersionRejected");
        if (!checkpointId.equals(JsonSchemaV1.requireText(expected, "decodedCheckpointId", expectedSource))) {
            throw JsonSchemaV1.invalid(
                    JsonSchemaV1.path(expectedSource, "decodedCheckpointId") + " must match the envelope.");
        }
        JsonSchemaV1.requireTextArray(expected, "decodedPendingExecutors", expectedSource, true, true);
        JsonSchemaV1.requireTextArray(expected, "decodedBufferedInputOrder", expectedSource, true, true);
        JsonSchemaV1.requireArray(expected, "resumeFanInValues", expectedSource, true);
        JsonSchemaV1.requireText(expected, "terminalOutcome", expectedSource);
        requireTrue(expected, "deterministicEncoding", expectedSource);
        requireTrue(expected, "roundTripWithinJavaV1", expectedSource);
        requireTrue(expected, "wrongDocumentKindRejected", expectedSource);
        requireTrue(expected, "unsupportedPayloadVersionRejected", expectedSource);
        requireExpectedJson(expected, "resumeFanInValues", nodes(resumeSummary.fanInValues()), expectedSource);
        requireExpectedText(expected, "terminalOutcome", resumeSummary.terminalOutcome(), expectedSource);
        ArrayList<String> canonicalPendingExecutors = new ArrayList<>(pendingExecutors);
        canonicalPendingExecutors.sort(String::compareTo);
        requireExpectedJson(expected, "decodedPendingExecutors", strings(canonicalPendingExecutors), expectedSource);
        ArrayList<String> canonicalBufferedInputOrder = new ArrayList<>();
        CheckpointCanonicalizer.orderedBufferedInputs(bufferedInputs)
                .forEach(buffered -> canonicalBufferedInputOrder.add(buffered.get("targetId")
                                .textValue() + ":" + buffered.get("sourceId").textValue()));
        requireExpectedJson(
                expected, "decodedBufferedInputOrder", strings(canonicalBufferedInputOrder), expectedSource);
    }

    private static void validateContract(JsonNode root, String sourceName) {
        JsonNode input = requiredNonEmptyObject(root, "input", sourceName);
        JsonNode expected = requiredNonEmptyObject(root, "expected", sourceName);
        String caseId = JsonSchemaV1.requireText(root, "caseId", sourceName);
        String inputSource = JsonSchemaV1.path(sourceName, "input");
        String expectedSource = JsonSchemaV1.path(sourceName, "expected");
        switch (caseId) {
            case "JCF-CORE-003" -> validateRunOptionsContract(input, expected, inputSource, expectedSource);
            case "JCF-CORE-004" -> validateChatOptionsContract(input, expected, inputSource, expectedSource);
            case "JCF-AGENTS-001" -> validateAgentLifecycleContract(input, expected, inputSource, expectedSource);
            case "JCF-AGENTS-002" -> validateAgentContextContract(input, expected, inputSource, expectedSource);
            case "JCF-AGENTS-003" -> validateMiddlewareContract(input, expected, inputSource, expectedSource);
            case "JCF-TOOLS-001" -> validateToolCapabilitiesContract(input, expected, inputSource, expectedSource);
            case "JCF-PROVIDERS-001" -> validateOpenAiProviderContract(input, expected, inputSource, expectedSource);
            case "JCF-PROVIDERS-002" -> validateFoundryProviderContract(input, expected, inputSource, expectedSource);
            case "JCF-HOSTING-001" -> validateHostingContract(input, expected, inputSource, expectedSource);
            default ->
                throw JsonSchemaV1.invalid(
                        sourceName + " has no contract schema for caseId '" + caseId + "' in schemaVersion 1.");
        }
    }

    private static MessageState validateMessages(JsonNode messages, String sourceName) {
        MessageState state = new MessageState();
        for (int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
            JsonNode message = messages.get(messageIndex);
            String messageSource = JsonSchemaV1.indexed(sourceName, messageIndex);
            JsonSchemaV1.exactObject(message, messageSource, "role", "contents");
            String role = JsonSchemaV1.requireText(message, "role", messageSource);
            if (!Set.of("system", "developer", "user", "assistant", "tool").contains(role)) {
                throw JsonSchemaV1.invalid(messageSource + " has unknown message role '" + role + "'.");
            }
            state.roles.add(role);
            JsonNode contents = JsonSchemaV1.requireArray(message, "contents", messageSource, true);
            ArrayList<String> messageTextItems = new ArrayList<>();
            for (int contentIndex = 0; contentIndex < contents.size(); contentIndex++) {
                JsonNode content = contents.get(contentIndex);
                String contentSource = JsonSchemaV1.indexed(JsonSchemaV1.path(messageSource, "contents"), contentIndex);
                validateContent(content, contentSource, state, messageTextItems);
            }
            String messageText = String.join(" ", messageTextItems);
            state.messageTexts.add(messageText);
            if ("assistant".equals(role)) {
                state.assistantText.append(messageText);
            }
        }
        return state;
    }

    private static void validateContent(
            JsonNode content, String sourceName, MessageState state, List<String> messageTextItems) {
        String kind = JsonSchemaV1.requireText(content, "kind", sourceName);
        state.contentKinds.add(kind);
        switch (kind) {
            case "text" -> {
                JsonSchemaV1.exactObject(content, sourceName, "kind", "text");
                messageTextItems.add(JsonSchemaV1.requireString(content, "text", sourceName));
            }
            case "data" -> {
                JsonSchemaV1.exactObject(content, sourceName, "kind", "mediaType", "uri");
                JsonSchemaV1.requireText(content, "mediaType", sourceName);
                JsonSchemaV1.requireText(content, "uri", sourceName);
            }
            case "reasoning" -> {
                JsonSchemaV1.exactObject(content, sourceName, "kind", "id", "text");
                String id = JsonSchemaV1.requireText(content, "id", sourceName);
                if (!state.contentIds.add(id)) {
                    throw JsonSchemaV1.invalid(sourceName + " declares duplicate content id '" + id + "'.");
                }
                JsonSchemaV1.requireString(content, "text", sourceName);
            }
            case "functionCall" -> {
                JsonSchemaV1.exactObject(content, sourceName, "kind", "callId", "name", "arguments");
                String callId = JsonSchemaV1.requireText(content, "callId", sourceName);
                if (!state.calls.add(callId)) {
                    throw JsonSchemaV1.invalid(sourceName + " declares duplicate callId '" + callId + "'.");
                }
                JsonSchemaV1.requireText(content, "name", sourceName);
                JsonSchemaV1.requireObject(content, "arguments", sourceName);
            }
            case "functionResult" -> {
                JsonSchemaV1.exactObject(content, sourceName, "kind", "callId", "result");
                String callId = JsonSchemaV1.requireText(content, "callId", sourceName);
                if (!state.calls.contains(callId)) {
                    throw JsonSchemaV1.invalid(sourceName + " references orphan callId '" + callId + "'.");
                }
                JsonSchemaV1.require(content, "result", sourceName);
                state.resultCounts.merge(callId, 1, Integer::sum);
            }
            default -> throw JsonSchemaV1.invalid(sourceName + " has unknown content kind '" + kind + "'.");
        }
    }

    private static String validateTextContents(JsonNode contents, String sourceName) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < contents.size(); index++) {
            JsonNode content = contents.get(index);
            String contentSource = JsonSchemaV1.indexed(sourceName, index);
            JsonSchemaV1.exactObject(content, contentSource, "kind", "text");
            JsonSchemaV1.requireLiteral(content, "kind", "text", contentSource);
            text.append(JsonSchemaV1.requireString(content, "text", contentSource));
        }
        return text.toString();
    }

    private static void validateUsageInput(JsonNode usage, String sourceName) {
        usage.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            String valueSource = JsonSchemaV1.path(sourceName, entry.getKey());
            if (value.isNumber() && value.decimalValue().signum() < 0) {
                throw JsonSchemaV1.invalid(valueSource + " must be non-negative when numeric.");
            }
            if (value.isObject()) {
                validateUsageInput(value, valueSource);
            }
        });
    }

    private static void validateAggregatedUsage(JsonNode usage, String sourceName) {
        if (usage.isEmpty()) {
            throw JsonSchemaV1.invalid(sourceName + " must contain at least one summed integral field.");
        }
        usage.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            String valueSource = JsonSchemaV1.path(sourceName, entry.getKey());
            if (!value.isIntegralNumber() || value.bigIntegerValue().signum() < 0) {
                throw JsonSchemaV1.invalid(valueSource + " must be a non-negative summed integer.");
            }
        });
    }

    /**
     * Sequentially folds usage updates with Python {@code add_usage_details} semantics. Each pairwise
     * fold sums integral values, treats missing and null as zero, and omits a key when either side is
     * non-integral and non-null. A later fold can therefore reintroduce an omitted key.
     */
    private static ObjectNode aggregateUsage(List<JsonNode> usages) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (JsonNode usage : usages) {
            result = addUsageDetails(result, usage);
        }
        return result;
    }

    private static ObjectNode addUsageDetails(JsonNode left, JsonNode right) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        left.fieldNames().forEachRemaining(fields::add);
        right.fieldNames().forEachRemaining(fields::add);
        for (String field : fields) {
            JsonNode leftValue = left.get(field);
            JsonNode rightValue = right.get(field);
            if (isIntegralOrNull(leftValue) && isIntegralOrNull(rightValue)) {
                BigInteger sum = integralOrZero(leftValue).add(integralOrZero(rightValue));
                result.set(field, JsonNodeFactory.instance.numberNode(sum));
            }
        }
        return result;
    }

    private static boolean isIntegralOrNull(JsonNode value) {
        return value == null || value.isNull() || value.isIntegralNumber();
    }

    private static BigInteger integralOrZero(JsonNode value) {
        return value == null || value.isNull() ? BigInteger.ZERO : value.bigIntegerValue();
    }

    private static boolean numericJsonEquals(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        if (left.isObject() && right.isObject()) {
            if (left.size() != right.size()) {
                return false;
            }
            return left.properties().stream()
                    .allMatch(entry -> right.has(entry.getKey())
                            && numericJsonEquals(entry.getValue(), right.get(entry.getKey())));
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int index = 0; index < left.size(); index++) {
                if (!numericJsonEquals(left.get(index), right.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }

    private static void validateEnvelope(JsonNode envelope, String documentKind, String sourceName) {
        JsonSchemaV1.exactObject(envelope, sourceName, "format", "documentKind", "payloadVersion", "payload");
        JsonSchemaV1.requireLiteral(envelope, "format", "agent-framework-java-state", sourceName);
        JsonSchemaV1.requireLiteral(envelope, "documentKind", documentKind, sourceName);
        int payloadVersion = JsonSchemaV1.requireInteger(envelope, "payloadVersion", sourceName);
        if (payloadVersion != 1) {
            throw JsonSchemaV1.invalid(
                    JsonSchemaV1.path(sourceName, "payloadVersion") + " has unsupported value " + payloadVersion + ".");
        }
        JsonNode payload = JsonSchemaV1.requireObject(envelope, "payload", sourceName);
        if (payload.isEmpty()) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(sourceName, "payload") + " must not be empty.");
        }
    }

    private static void validateSessionOperation(JsonNode operation, String sourceName) {
        if (!operation.isObject() || operation.isEmpty()) {
            throw JsonSchemaV1.invalid(sourceName + " must be a non-empty JSON object.");
        }
        String type = JsonSchemaV1.requireText(operation, "operation", sourceName);
        switch (type) {
            case "decode" -> {
                JsonSchemaV1.exactObject(operation, sourceName, "operation", "readerVersion");
                JsonSchemaV1.requirePositiveInteger(operation, "readerVersion", sourceName);
            }
            case "encode" -> {
                JsonSchemaV1.exactObject(operation, sourceName, "operation", "writerVersion");
                JsonSchemaV1.requirePositiveInteger(operation, "writerVersion", sourceName);
            }
            case "save" -> {
                JsonSchemaV1.object(
                        operation,
                        sourceName,
                        List.of("operation", "expectedRevision"),
                        List.of("outcome", "returnedRevision", "value"));
                JsonSchemaV1.requireNonNegativeInteger(operation, "expectedRevision", sourceName);
                optionalText(operation, "outcome", sourceName);
                if (operation.has("returnedRevision")) {
                    JsonSchemaV1.requirePositiveInteger(operation, "returnedRevision", sourceName);
                }
            }
            case "load" -> {
                JsonSchemaV1.object(operation, sourceName, List.of("operation"), List.of("outcome"));
                optionalText(operation, "outcome", sourceName);
            }
            case "mutateCallerCopy", "mutateLoadedCopy" -> {
                JsonSchemaV1.exactObject(operation, sourceName, "operation", "append");
                JsonSchemaV1.require(operation, "append", sourceName);
            }
            default -> throw JsonSchemaV1.invalid(sourceName + " has unknown session operation '" + type + "'.");
        }
    }

    private static void validateToolExpected(
            String caseId, JsonNode expected, String sourceName, EventSchemaV1.ToolHistory history) {
        switch (caseId) {
            case "JCF-TOOLS-002" -> {
                JsonSchemaV1.exactObject(
                        expected,
                        sourceName,
                        "assistantText",
                        "callResultOrder",
                        "functionResultCount",
                        "invocationCount",
                        "terminalCount");
                JsonSchemaV1.requireString(expected, "assistantText", sourceName);
                JsonSchemaV1.requireTextArray(expected, "callResultOrder", sourceName, true, true);
                requireCountFields(expected, sourceName, "functionResultCount", "invocationCount", "terminalCount");
            }
            case "JCF-TOOLS-003" -> {
                JsonSchemaV1.exactObject(
                        expected,
                        sourceName,
                        "invocationCounts",
                        "parallelExecutionPermitted",
                        "resultOrder",
                        "terminalCount");
                JsonSchemaV1.requireIntegerObject(expected, "invocationCounts", sourceName);
                JsonSchemaV1.requireBoolean(expected, "parallelExecutionPermitted", sourceName);
                JsonSchemaV1.requireTextArray(expected, "resultOrder", sourceName, true, true);
                requireCountFields(expected, sourceName, "terminalCount");
            }
            case "JCF-TOOLS-004" -> {
                JsonSchemaV1.exactObject(
                        expected,
                        sourceName,
                        "externalExactlyOnceAfterCrashClaimed",
                        "functionResultCount",
                        "invocationCount",
                        "logicalRunId",
                        "terminalCount");
                requireFalse(expected, "externalExactlyOnceAfterCrashClaimed", sourceName);
                requireCountFields(expected, sourceName, "functionResultCount", "invocationCount", "terminalCount");
                JsonSchemaV1.requireText(expected, "logicalRunId", sourceName);
            }
            case "JCF-TOOLS-005" -> {
                JsonSchemaV1.exactObject(
                        expected,
                        sourceName,
                        "detailLeakage",
                        "functionResultCount",
                        "invocationCount",
                        "orphanCalls",
                        "resultCallId");
                JsonSchemaV1.requireBoolean(expected, "detailLeakage", sourceName);
                requireCountFields(expected, sourceName, "functionResultCount", "invocationCount", "orphanCalls");
                JsonSchemaV1.requireText(expected, "resultCallId", sourceName);
            }
            case "JCF-TOOLS-006" -> {
                JsonSchemaV1.exactObject(
                        expected,
                        sourceName,
                        "finalTextEqual",
                        "logicalCallIdsEqual",
                        "logicalResultsEqual",
                        "nonStreamingInvocationCount",
                        "streamingInvocationCount",
                        "terminalCountPerMode");
                requireBooleanFields(
                        expected, sourceName, "finalTextEqual", "logicalCallIdsEqual", "logicalResultsEqual");
                requireCountFields(
                        expected,
                        sourceName,
                        "nonStreamingInvocationCount",
                        "streamingInvocationCount",
                        "terminalCountPerMode");
            }
            case "JCF-TOOLS-007" -> {
                JsonSchemaV1.exactObject(
                        expected,
                        sourceName,
                        "callerInputMutated",
                        "invocationsAfterApproval",
                        "invocationsBeforeApproval",
                        "logicalRunResumed",
                        "resultPrecedesAssistantText");
                requireBooleanFields(
                        expected, sourceName, "callerInputMutated", "logicalRunResumed", "resultPrecedesAssistantText");
                requireCountFields(expected, sourceName, "invocationsAfterApproval", "invocationsBeforeApproval");
            }
            case "JCF-TOOLS-008" -> {
                JsonSchemaV1.exactObject(
                        expected,
                        sourceName,
                        "approvalControlItemsInLaterModelHistory",
                        "functionCallOccurrences",
                        "functionResultOccurrences",
                        "invocationCount",
                        "staleDecisionAccepted");
                requireCountFields(
                        expected,
                        sourceName,
                        "approvalControlItemsInLaterModelHistory",
                        "functionCallOccurrences",
                        "functionResultOccurrences",
                        "invocationCount");
                JsonSchemaV1.requireBoolean(expected, "staleDecisionAccepted", sourceName);
            }
            case "JCF-TOOLS-009" -> {
                JsonSchemaV1.exactObject(
                        expected,
                        sourceName,
                        "duplicateCallRejected",
                        "functionResultCount",
                        "invocationCount",
                        "terminalCount");
                requireTrue(expected, "duplicateCallRejected", sourceName);
                requireCountFields(expected, sourceName, "functionResultCount", "invocationCount", "terminalCount");
            }
            case "JCF-TOOLS-010" -> {
                JsonSchemaV1.exactObject(
                        expected,
                        sourceName,
                        "primaryDecisionApproved",
                        "duplicateDecisionAccepted",
                        "functionResultCount",
                        "invocationCount",
                        "terminalCount");
                requireTrue(expected, "primaryDecisionApproved", sourceName);
                requireFalse(expected, "duplicateDecisionAccepted", sourceName);
                requireCountFields(expected, sourceName, "functionResultCount", "invocationCount", "terminalCount");
            }
            case "JCF-TOOLS-011" -> {
                JsonSchemaV1.exactObject(
                        expected,
                        sourceName,
                        "rejectionAccepted",
                        "toolExecuted",
                        "functionResultCount",
                        "invocationCount",
                        "terminalCount");
                requireTrue(expected, "rejectionAccepted", sourceName);
                requireFalse(expected, "toolExecuted", sourceName);
                requireCountFields(expected, sourceName, "functionResultCount", "invocationCount", "terminalCount");
            }
            case "JCF-TOOLS-012" -> {
                JsonSchemaV1.exactObject(
                        expected,
                        sourceName,
                        "executionViews",
                        "logicalRunId",
                        "sharedInvocationId",
                        "totalInvocationCount",
                        "functionResultCount",
                        "terminalCount");
                JsonSchemaV1.requireTextArray(expected, "executionViews", sourceName, true, true);
                JsonSchemaV1.requireText(expected, "logicalRunId", sourceName);
                JsonSchemaV1.requireText(expected, "sharedInvocationId", sourceName);
                requireCountFields(
                        expected, sourceName, "totalInvocationCount", "functionResultCount", "terminalCount");
            }
            default ->
                throw JsonSchemaV1.invalid(
                        sourceName + " has no tool-loop expected schema for caseId '" + caseId + "'.");
        }
        crossCheckToolExpected(caseId, expected, sourceName, history);
    }

    private static void validateSessionExpected(
            String caseId, JsonNode expected, String sourceName, JsonNode envelope, JsonNode operations) {
        switch (caseId) {
            case "JCF-SESSIONS-001" -> {
                JsonSchemaV1.exactObject(
                        expected,
                        sourceName,
                        "roundTripWithinJavaV1",
                        "observableSessionId",
                        "observableMessageText",
                        "crossLanguageWireCompatible",
                        "unknownDocumentKindRejected",
                        "unknownPayloadVersionRejected");
                requireTrue(expected, "roundTripWithinJavaV1", sourceName);
                JsonSchemaV1.requireText(expected, "observableSessionId", sourceName);
                JsonSchemaV1.requireString(expected, "observableMessageText", sourceName);
                requireFalse(expected, "crossLanguageWireCompatible", sourceName);
                requireTrue(expected, "unknownDocumentKindRejected", sourceName);
                requireTrue(expected, "unknownPayloadVersionRejected", sourceName);
            }
            case "JCF-SESSIONS-002" -> {
                JsonSchemaV1.exactObject(
                        expected,
                        sourceName,
                        "loadedRevision",
                        "loadedValue",
                        "lastWriterWins",
                        "failedWritePreservesSnapshot");
                JsonSchemaV1.requirePositiveInteger(expected, "loadedRevision", sourceName);
                JsonSchemaV1.require(expected, "loadedValue", sourceName);
                JsonSchemaV1.requireBoolean(expected, "lastWriterWins", sourceName);
                JsonSchemaV1.requireBoolean(expected, "failedWritePreservesSnapshot", sourceName);
            }
            case "JCF-SESSIONS-003" -> {
                JsonSchemaV1.exactObject(
                        expected, sourceName, "firstLoadValues", "secondLoadValues", "storedStateDetached");
                JsonSchemaV1.requireArray(expected, "firstLoadValues", sourceName, true);
                JsonSchemaV1.requireArray(expected, "secondLoadValues", sourceName, true);
                JsonSchemaV1.requireBoolean(expected, "storedStateDetached", sourceName);
            }
            default ->
                throw JsonSchemaV1.invalid(sourceName + " has no session expected schema for caseId '" + caseId + "'.");
        }
        crossCheckSessionExpected(caseId, expected, sourceName, envelope, operations);
    }

    private static void validateWorkflowExpected(
            String caseId, JsonNode expected, String sourceName, EventSchemaV1.WorkflowSummary summary) {
        switch (caseId) {
            case "JCF-WORKFLOWS-001" -> {
                JsonSchemaV1.exactObject(
                        expected, sourceName, "executorOrder", "outputs", "terminalCount", "terminalOutcome");
                JsonSchemaV1.requireTextArray(expected, "executorOrder", sourceName, true, true);
                JsonSchemaV1.requireArray(expected, "outputs", sourceName, true);
                requireCountFields(expected, sourceName, "terminalCount");
                JsonSchemaV1.requireText(expected, "terminalOutcome", sourceName);
            }
            case "JCF-WORKFLOWS-002" -> {
                JsonSchemaV1.exactObject(
                        expected,
                        sourceName,
                        "fanInReleaseCount",
                        "fanInValues",
                        "fanOutDeliveries",
                        "outputs",
                        "terminalCount");
                requireCountFields(expected, sourceName, "fanInReleaseCount", "terminalCount");
                JsonSchemaV1.requireArray(expected, "fanInValues", sourceName, true);
                JsonSchemaV1.requireIntegerObject(expected, "fanOutDeliveries", sourceName);
                JsonSchemaV1.requireArray(expected, "outputs", sourceName, true);
            }
            case "JCF-WORKFLOWS-003" -> {
                JsonSchemaV1.exactObject(
                        expected,
                        sourceName,
                        "cancelledExecutors",
                        "failedExecutor",
                        "outputs",
                        "successAfterTerminal",
                        "terminalCount",
                        "terminalOutcome");
                JsonSchemaV1.requireTextArray(expected, "cancelledExecutors", sourceName, true, true);
                JsonSchemaV1.requireText(expected, "failedExecutor", sourceName);
                JsonSchemaV1.requireArray(expected, "outputs", sourceName, false);
                JsonSchemaV1.requireBoolean(expected, "successAfterTerminal", sourceName);
                requireCountFields(expected, sourceName, "terminalCount");
                JsonSchemaV1.requireText(expected, "terminalOutcome", sourceName);
            }
            case "JCF-WORKFLOWS-004" -> {
                JsonSchemaV1.exactObject(
                        expected,
                        sourceName,
                        "crossLanguageWireCompatible",
                        "duplicateBufferedValues",
                        "fanInValues",
                        "nextCheckpointParent",
                        "restoredCheckpointId",
                        "terminalCount");
                requireFalse(expected, "crossLanguageWireCompatible", sourceName);
                requireCountFields(expected, sourceName, "duplicateBufferedValues", "terminalCount");
                JsonSchemaV1.requireArray(expected, "fanInValues", sourceName, true);
                JsonSchemaV1.requireText(expected, "nextCheckpointParent", sourceName);
                JsonSchemaV1.requireText(expected, "restoredCheckpointId", sourceName);
            }
            default ->
                throw JsonSchemaV1.invalid(
                        sourceName + " has no workflow expected schema for caseId '" + caseId + "'.");
        }
        crossCheckWorkflowExpected(caseId, expected, sourceName, summary);
    }

    private static void crossCheckToolExpected(
            String caseId, JsonNode expected, String sourceName, EventSchemaV1.ToolHistory history) {
        if ("JCF-TOOLS-006".equals(caseId)) {
            EventSchemaV1.ToolLaneSummary streaming = history.requireLane("streaming");
            EventSchemaV1.ToolLaneSummary nonStreaming = history.requireLane("nonStreaming");
            requireExpectedBoolean(
                    expected,
                    "logicalCallIdsEqual",
                    streaming.callOrder().equals(nonStreaming.callOrder()),
                    sourceName);
            boolean resultsEqual = streaming.resultOrder().equals(nonStreaming.resultOrder())
                    && streaming.resultOrder().stream()
                            .allMatch(callId -> numericJsonEquals(
                                    streaming.results().get(callId),
                                    nonStreaming.results().get(callId)));
            requireExpectedBoolean(expected, "logicalResultsEqual", resultsEqual, sourceName);
            requireExpectedBoolean(
                    expected,
                    "finalTextEqual",
                    streaming.assistantText().equals(nonStreaming.assistantText()),
                    sourceName);
            requireExpectedCount(expected, "streamingInvocationCount", streaming.totalInvocationCount(), sourceName);
            requireExpectedCount(
                    expected, "nonStreamingInvocationCount", nonStreaming.totalInvocationCount(), sourceName);
            requireExpectedCount(expected, "terminalCountPerMode", streaming.terminalCount(), sourceName);
            if (streaming.terminalCount() != nonStreaming.terminalCount()) {
                throw JsonSchemaV1.invalid(sourceName + " terminalCountPerMode must match every execution mode.");
            }
            return;
        }

        EventSchemaV1.ToolLaneSummary lane = history.requireLane("default");
        switch (caseId) {
            case "JCF-TOOLS-002" -> {
                requireExpectedCount(expected, "invocationCount", lane.totalInvocationCount(), sourceName);
                requireExpectedCount(expected, "functionResultCount", lane.functionResultCount(), sourceName);
                requireExpectedJson(expected, "callResultOrder", strings(lane.resultOrder()), sourceName);
                requireExpectedText(expected, "assistantText", lane.assistantText(), sourceName);
                requireExpectedCount(expected, "terminalCount", lane.terminalCount(), sourceName);
            }
            case "JCF-TOOLS-003" -> {
                requireExpectedJson(expected, "invocationCounts", integers(lane.invocationCounts()), sourceName);
                requireExpectedJson(expected, "resultOrder", strings(lane.resultOrder()), sourceName);
                requireExpectedCount(expected, "terminalCount", lane.terminalCount(), sourceName);
            }
            case "JCF-TOOLS-004" -> {
                requireExpectedCount(expected, "invocationCount", lane.totalInvocationCount(), sourceName);
                requireExpectedCount(expected, "functionResultCount", lane.functionResultCount(), sourceName);
                requireExpectedText(expected, "logicalRunId", onlyLogicalRunId(lane), sourceName);
                requireExpectedCount(expected, "terminalCount", lane.terminalCount(), sourceName);
            }
            case "JCF-TOOLS-005" -> {
                requireExpectedCount(expected, "invocationCount", lane.totalInvocationCount(), sourceName);
                requireExpectedCount(expected, "functionResultCount", lane.functionResultCount(), sourceName);
                requireExpectedText(expected, "resultCallId", only(lane.resultOrder(), "result call"), sourceName);
                requireExpectedCount(expected, "orphanCalls", 0, sourceName);
            }
            case "JCF-TOOLS-007" -> {
                requireExpectedCount(expected, "invocationsBeforeApproval", 0, sourceName);
                requireExpectedCount(expected, "invocationsAfterApproval", lane.invocationsAfterApproval(), sourceName);
                requireExpectedBoolean(
                        expected, "resultPrecedesAssistantText", lane.resultPrecedesAssistantText(), sourceName);
                requireExpectedBoolean(expected, "callerInputMutated", false, sourceName);
                requireExpectedBoolean(
                        expected,
                        "logicalRunResumed",
                        lane.terminalOutcomes().contains("inputRequired")
                                && "success"
                                        .equals(lane.terminalOutcomes()
                                                .get(lane.terminalOutcomes().size() - 1)),
                        sourceName);
            }
            case "JCF-TOOLS-008" -> {
                requireExpectedCount(expected, "invocationCount", lane.totalInvocationCount(), sourceName);
                requireExpectedCount(
                        expected, "functionCallOccurrences", lane.callOrder().size(), sourceName);
                requireExpectedCount(expected, "functionResultOccurrences", lane.functionResultCount(), sourceName);
                requireExpectedBoolean(
                        expected, "staleDecisionAccepted", lane.approvalReplayRejectionCount() == 0, sourceName);
                requireExpectedCount(expected, "approvalControlItemsInLaterModelHistory", 0, sourceName);
            }
            case "JCF-TOOLS-009" -> {
                requireExpectedBoolean(
                        expected, "duplicateCallRejected", lane.duplicateCallRejectionCount() == 1, sourceName);
                requireExpectedCount(expected, "functionResultCount", lane.functionResultCount(), sourceName);
                requireExpectedCount(expected, "invocationCount", lane.totalInvocationCount(), sourceName);
                requireExpectedCount(expected, "terminalCount", lane.terminalCount(), sourceName);
            }
            case "JCF-TOOLS-010" -> {
                requireExpectedBoolean(
                        expected,
                        "primaryDecisionApproved",
                        lane.approvalDecisions().values().stream().anyMatch(Boolean.TRUE::equals),
                        sourceName);
                requireExpectedBoolean(
                        expected, "duplicateDecisionAccepted", lane.approvalReplayRejectionCount() == 0, sourceName);
                requireExpectedCount(expected, "functionResultCount", lane.functionResultCount(), sourceName);
                requireExpectedCount(expected, "invocationCount", lane.totalInvocationCount(), sourceName);
                requireExpectedCount(expected, "terminalCount", lane.terminalCount(), sourceName);
            }
            case "JCF-TOOLS-011" -> {
                requireExpectedBoolean(
                        expected,
                        "rejectionAccepted",
                        lane.approvalDecisions().values().stream().anyMatch(Boolean.FALSE::equals),
                        sourceName);
                requireExpectedBoolean(expected, "toolExecuted", lane.totalInvocationCount() > 0, sourceName);
                requireExpectedCount(expected, "functionResultCount", lane.functionResultCount(), sourceName);
                requireExpectedCount(expected, "invocationCount", lane.totalInvocationCount(), sourceName);
                requireExpectedCount(expected, "terminalCount", lane.terminalCount(), sourceName);
            }
            case "JCF-TOOLS-012" -> {
                String callId = only(lane.callOrder(), "shared call");
                requireExpectedJson(
                        expected,
                        "executionViews",
                        strings(lane.executionViews().get(callId)),
                        sourceName);
                requireExpectedText(
                        expected, "logicalRunId", lane.logicalRunIds().get(callId), sourceName);
                requireExpectedText(
                        expected, "sharedInvocationId", lane.invocationIds().get(callId), sourceName);
                requireExpectedCount(expected, "totalInvocationCount", lane.totalInvocationCount(), sourceName);
                requireExpectedCount(expected, "functionResultCount", lane.functionResultCount(), sourceName);
                requireExpectedCount(expected, "terminalCount", lane.terminalCount(), sourceName);
            }
            default -> {
                // Shape-only tool contracts have no event history.
            }
        }
    }

    private static void crossCheckWorkflowExpected(
            String caseId, JsonNode expected, String sourceName, EventSchemaV1.WorkflowSummary summary) {
        switch (caseId) {
            case "JCF-WORKFLOWS-001" -> {
                requireExpectedJson(expected, "executorOrder", strings(summary.executorOrder()), sourceName);
                requireExpectedJson(expected, "outputs", nodes(summary.outputs()), sourceName);
                requireExpectedCount(expected, "terminalCount", summary.terminalCount(), sourceName);
                requireExpectedText(expected, "terminalOutcome", summary.terminalOutcome(), sourceName);
            }
            case "JCF-WORKFLOWS-002" -> {
                requireExpectedJson(expected, "fanOutDeliveries", integers(summary.fanOutDeliveries()), sourceName);
                requireExpectedCount(expected, "fanInReleaseCount", summary.fanInReleaseCount(), sourceName);
                requireExpectedJson(expected, "fanInValues", nodes(summary.fanInValues()), sourceName);
                requireExpectedJson(expected, "outputs", nodes(summary.outputs()), sourceName);
                requireExpectedCount(expected, "terminalCount", summary.terminalCount(), sourceName);
            }
            case "JCF-WORKFLOWS-003" -> {
                requireExpectedText(
                        expected, "failedExecutor", only(summary.failedExecutors(), "failed executor"), sourceName);
                requireExpectedJson(expected, "cancelledExecutors", strings(summary.cancelledExecutors()), sourceName);
                requireExpectedJson(expected, "outputs", nodes(summary.outputs()), sourceName);
                requireExpectedCount(expected, "terminalCount", summary.terminalCount(), sourceName);
                requireExpectedText(expected, "terminalOutcome", summary.terminalOutcome(), sourceName);
                requireExpectedBoolean(expected, "successAfterTerminal", summary.successAfterTerminal(), sourceName);
            }
            case "JCF-WORKFLOWS-004" -> {
                requireExpectedText(expected, "restoredCheckpointId", summary.loadedCheckpointId(), sourceName);
                String parent = summary.checkpointParents().values().stream()
                        .filter(java.util.Objects::nonNull)
                        .reduce((first, second) -> second)
                        .orElse(null);
                requireExpectedText(expected, "nextCheckpointParent", parent, sourceName);
                requireExpectedJson(expected, "fanInValues", nodes(summary.fanInValues()), sourceName);
                requireExpectedCount(
                        expected, "duplicateBufferedValues", summary.duplicateBufferedValues(), sourceName);
                requireExpectedCount(expected, "terminalCount", summary.terminalCount(), sourceName);
            }
            default -> {
                // The schema switch rejects unknown workflow cases before reaching this point.
            }
        }
    }

    private static void crossCheckSessionExpected(
            String caseId, JsonNode expected, String sourceName, JsonNode envelope, JsonNode operations) {
        JsonNode payload = envelope.get("payload");
        JsonNode state = payload.get("state");
        switch (caseId) {
            case "JCF-SESSIONS-001" -> {
                requireExpectedText(
                        expected,
                        "observableSessionId",
                        payload.get("sessionId").textValue(),
                        sourceName);
                StringBuilder text = new StringBuilder();
                payload.path("messages")
                        .forEach(message -> message.path("contents").forEach(content -> {
                            if ("text".equals(content.path("kind").textValue())) {
                                text.append(content.path("text").textValue());
                            }
                        }));
                requireExpectedText(expected, "observableMessageText", text.toString(), sourceName);
                requireExpectedBoolean(expected, "roundTripWithinJavaV1", true, sourceName);
                requireExpectedBoolean(expected, "crossLanguageWireCompatible", false, sourceName);
                requireExpectedBoolean(expected, "unknownDocumentKindRejected", true, sourceName);
                requireExpectedBoolean(expected, "unknownPayloadVersionRejected", true, sourceName);
            }
            case "JCF-SESSIONS-002" -> {
                JsonNode successfulSave = operations.get(0);
                JsonNode conflictingSave = operations.get(1);
                requireExpectedCount(
                        expected,
                        "loadedRevision",
                        successfulSave.get("returnedRevision").intValue(),
                        sourceName);
                requireExpectedJson(expected, "loadedValue", state.get("value"), sourceName);
                requireExpectedBoolean(
                        expected,
                        "lastWriterWins",
                        !"storageConflict"
                                .equals(conflictingSave.path("outcome").textValue()),
                        sourceName);
                requireExpectedBoolean(
                        expected,
                        "failedWritePreservesSnapshot",
                        "storageConflict".equals(conflictingSave.path("outcome").textValue()),
                        sourceName);
            }
            case "JCF-SESSIONS-003" -> {
                JsonNode values = state.get("values");
                requireExpectedJson(expected, "firstLoadValues", values, sourceName);
                requireExpectedJson(expected, "secondLoadValues", values, sourceName);
                long loadCount = java.util.stream.StreamSupport.stream(operations.spliterator(), false)
                        .filter(operation ->
                                "load".equals(operation.path("operation").textValue()))
                        .count();
                boolean detached = loadCount == 2
                        && java.util.stream.StreamSupport.stream(operations.spliterator(), false)
                                        .map(operation ->
                                                operation.path("operation").textValue())
                                        .filter(operation -> operation.startsWith("mutate"))
                                        .count()
                                == 2;
                requireExpectedBoolean(expected, "storedStateDetached", detached, sourceName);
            }
            default -> {
                // The schema switch rejects unknown session cases before reaching this point.
            }
        }
    }

    private static String onlyLogicalRunId(EventSchemaV1.ToolLaneSummary lane) {
        if (!lane.logicalRunIds().isEmpty()) {
            return only(new ArrayList<>(lane.logicalRunIds().values()), "logical run");
        }
        String invocationId = only(new ArrayList<>(lane.invocationIds().values()), "invocation");
        int separator = invocationId.indexOf(':');
        return separator < 0 ? invocationId : invocationId.substring(0, separator);
    }

    private static <T> T only(List<T> values, String description) {
        if (values.size() != 1) {
            throw JsonSchemaV1.invalid("Expected exactly one " + description + " but found " + values.size() + ".");
        }
        return values.get(0);
    }

    private static void requireExpectedCount(JsonNode expected, String field, int actual, String sourceName) {
        int declared = JsonSchemaV1.requireNonNegativeInteger(expected, field, sourceName);
        if (declared != actual) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(sourceName, field)
                    + " must match derived value "
                    + actual
                    + " but was "
                    + declared
                    + ".");
        }
    }

    private static void requireExpectedBoolean(JsonNode expected, String field, boolean actual, String sourceName) {
        boolean declared = JsonSchemaV1.requireBoolean(expected, field, sourceName);
        if (declared != actual) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(sourceName, field)
                    + " must match derived value "
                    + actual
                    + " but was "
                    + declared
                    + ".");
        }
    }

    private static void requireExpectedText(JsonNode expected, String field, String actual, String sourceName) {
        String declared = JsonSchemaV1.requireString(expected, field, sourceName);
        if (!java.util.Objects.equals(declared, actual)) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(sourceName, field)
                    + " must match derived value '"
                    + actual
                    + "' but was '"
                    + declared
                    + "'.");
        }
    }

    private static void requireExpectedJson(JsonNode expected, String field, JsonNode actual, String sourceName) {
        JsonNode declared = JsonSchemaV1.require(expected, field, sourceName);
        if (!numericJsonEquals(declared, actual)) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(sourceName, field)
                    + " must match event-derived value "
                    + actual
                    + " but was "
                    + declared
                    + ".");
        }
    }

    private static JsonNode strings(List<String> values) {
        var array = JsonNodeFactory.instance.arrayNode();
        values.forEach(array::add);
        return array;
    }

    private static List<String> requireStringArray(JsonNode object, String field, String sourceName) {
        JsonNode array = JsonSchemaV1.requireArray(object, field, sourceName, false);
        ArrayList<String> result = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            JsonNode element = array.get(index);
            if (!element.isTextual()) {
                throw JsonSchemaV1.invalid(JsonSchemaV1.path(sourceName, field) + "[" + index + "] must be a string.");
            }
            result.add(element.textValue());
        }
        return List.copyOf(result);
    }

    private static JsonNode booleans(List<Boolean> values) {
        var array = JsonNodeFactory.instance.arrayNode();
        values.forEach(array::add);
        return array;
    }

    private static JsonNode nodes(List<JsonNode> values) {
        var array = JsonNodeFactory.instance.arrayNode();
        values.forEach(value -> array.add(value.deepCopy()));
        return array;
    }

    private static JsonNode integers(Map<String, Integer> values) {
        ObjectNode object = JsonNodeFactory.instance.objectNode();
        values.forEach(object::put);
        return object;
    }

    private static void validateRunOptionsContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "options", "executionViews");
        validateRunOptions(
                JsonSchemaV1.requireObject(input, "options", inputSource), JsonSchemaV1.path(inputSource, "options"));
        JsonSchemaV1.requireTextArray(input, "executionViews", inputSource, true, true);
        JsonSchemaV1.exactObject(expected, expectedSource, "effectiveOptions", "callerOptionsUnchanged");
        validateRunOptions(
                JsonSchemaV1.requireObject(expected, "effectiveOptions", expectedSource),
                JsonSchemaV1.path(expectedSource, "effectiveOptions"));
        requireBooleanFields(expected, expectedSource, "callerOptionsUnchanged");
    }

    private static void validateRunOptions(JsonNode options, String sourceName) {
        JsonSchemaV1.exactObject(options, sourceName, "maxIterations", "maxFunctionCalls", "metadata");
        JsonSchemaV1.requirePositiveInteger(options, "maxIterations", sourceName);
        JsonSchemaV1.requirePositiveInteger(options, "maxFunctionCalls", sourceName);
        JsonNode metadata = JsonSchemaV1.requireObject(options, "metadata", sourceName);
        JsonSchemaV1.exactObject(metadata, JsonSchemaV1.path(sourceName, "metadata"), "tenant");
        JsonSchemaV1.requireText(metadata, "tenant", JsonSchemaV1.path(sourceName, "metadata"));
    }

    private static void validateChatOptionsContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "options", "providerFinishReason");
        validateChatOptions(
                JsonSchemaV1.requireObject(input, "options", inputSource), JsonSchemaV1.path(inputSource, "options"));
        JsonSchemaV1.requireText(input, "providerFinishReason", inputSource);
        JsonSchemaV1.exactObject(expected, expectedSource, "normalized", "finishReason");
        validateChatOptions(
                JsonSchemaV1.requireObject(expected, "normalized", expectedSource),
                JsonSchemaV1.path(expectedSource, "normalized"));
        JsonSchemaV1.requireText(expected, "finishReason", expectedSource);
    }

    private static void validateChatOptions(JsonNode options, String sourceName) {
        JsonSchemaV1.exactObject(options, sourceName, "temperature", "maxTokens", "toolChoice");
        JsonSchemaV1.requireNumber(options, "temperature", sourceName);
        JsonSchemaV1.requirePositiveInteger(options, "maxTokens", sourceName);
        JsonSchemaV1.requireText(options, "toolChoice", sourceName);
    }

    private static void validateAgentLifecycleContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "agent", "operations");
        JsonNode agent = JsonSchemaV1.requireObject(input, "agent", inputSource);
        JsonSchemaV1.exactObject(agent, JsonSchemaV1.path(inputSource, "agent"), "id", "name");
        JsonSchemaV1.requireText(agent, "id", JsonSchemaV1.path(inputSource, "agent"));
        JsonSchemaV1.requireText(agent, "name", JsonSchemaV1.path(inputSource, "agent"));
        JsonSchemaV1.requireTextArray(input, "operations", inputSource, true, true);
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "agentContract",
                "baseAgentOptional",
                "finiteAsyncType",
                "streamingType",
                "executionCoreCountPerRun");
        JsonSchemaV1.requireText(expected, "agentContract", expectedSource);
        JsonSchemaV1.requireBoolean(expected, "baseAgentOptional", expectedSource);
        JsonSchemaV1.requireText(expected, "finiteAsyncType", expectedSource);
        JsonSchemaV1.requireText(expected, "streamingType", expectedSource);
        JsonSchemaV1.requirePositiveInteger(expected, "executionCoreCountPerRun", expectedSource);
    }

    private static void validateAgentContextContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "providerResults", "metadata");
        JsonNode providers = JsonSchemaV1.requireArray(input, "providerResults", inputSource, true);
        Set<String> providerIds = new HashSet<>();
        for (int index = 0; index < providers.size(); index++) {
            JsonNode provider = providers.get(index);
            String providerSource = JsonSchemaV1.indexed(JsonSchemaV1.path(inputSource, "providerResults"), index);
            JsonSchemaV1.exactObject(provider, providerSource, "providerId", "messages");
            String providerId = JsonSchemaV1.requireText(provider, "providerId", providerSource);
            if (!providerIds.add(providerId)) {
                throw JsonSchemaV1.invalid(providerSource + " declares duplicate providerId '" + providerId + "'.");
            }
            JsonNode messages = JsonSchemaV1.requireArray(provider, "messages", providerSource, true);
            for (int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
                JsonNode message = messages.get(messageIndex);
                String messageSource =
                        JsonSchemaV1.indexed(JsonSchemaV1.path(providerSource, "messages"), messageIndex);
                JsonSchemaV1.exactObject(message, messageSource, "role", "text");
                JsonSchemaV1.requireText(message, "role", messageSource);
                JsonSchemaV1.requireString(message, "text", messageSource);
            }
        }
        validateAgentMetadata(
                JsonSchemaV1.requireObject(input, "metadata", inputSource), JsonSchemaV1.path(inputSource, "metadata"));
        JsonSchemaV1.exactObject(
                expected, expectedSource, "providerOrder", "messageOrder", "metadata", "callerMetadataUnchanged");
        JsonSchemaV1.requireTextArray(expected, "providerOrder", expectedSource, true, true);
        JsonSchemaV1.requireTextArray(expected, "messageOrder", expectedSource, true, false);
        validateAgentMetadata(
                JsonSchemaV1.requireObject(expected, "metadata", expectedSource),
                JsonSchemaV1.path(expectedSource, "metadata"));
        JsonSchemaV1.requireBoolean(expected, "callerMetadataUnchanged", expectedSource);
    }

    private static void validateAgentMetadata(JsonNode metadata, String sourceName) {
        JsonSchemaV1.exactObject(metadata, sourceName, "agentId", "runId");
        JsonSchemaV1.requireText(metadata, "agentId", sourceName);
        JsonSchemaV1.requireText(metadata, "runId", sourceName);
    }

    private static void validateMiddlewareContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "pipeline", "terminationAt");
        JsonSchemaV1.requireTextArray(input, "pipeline", inputSource, true, true);
        JsonSchemaV1.requireText(input, "terminationAt", inputSource);
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "normalOrder",
                "terminatedOrder",
                "modelCallsAfterTermination",
                "toolInvocationsAfterTermination");
        JsonSchemaV1.requireTextArray(expected, "normalOrder", expectedSource, true, true);
        JsonSchemaV1.requireTextArray(expected, "terminatedOrder", expectedSource, true, true);
        requireCountFields(expected, expectedSource, "modelCallsAfterTermination", "toolInvocationsAfterTermination");
    }

    private static void validateToolCapabilitiesContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "tools", "toolMode");
        JsonNode tools = JsonSchemaV1.requireArray(input, "tools", inputSource, true);
        Set<String> names = new HashSet<>();
        for (int index = 0; index < tools.size(); index++) {
            JsonNode tool = tools.get(index);
            String toolSource = JsonSchemaV1.indexed(JsonSchemaV1.path(inputSource, "tools"), index);
            JsonSchemaV1.exactObject(tool, toolSource, "name", "capabilities", "approvalMode");
            String name = JsonSchemaV1.requireText(tool, "name", toolSource);
            if (!names.add(name)) {
                throw JsonSchemaV1.invalid(toolSource + " declares duplicate tool name '" + name + "'.");
            }
            JsonSchemaV1.requireTextArray(tool, "capabilities", toolSource, true, true);
            JsonSchemaV1.requireText(tool, "approvalMode", toolSource);
        }
        JsonSchemaV1.requireText(input, "toolMode", inputSource);
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "normalizedNames",
                "duplicateNamesRejected",
                "toolMode",
                "providerTypesExposed");
        JsonSchemaV1.requireTextArray(expected, "normalizedNames", expectedSource, true, true);
        requireBooleanFields(expected, expectedSource, "duplicateNamesRejected", "providerTypesExposed");
        JsonSchemaV1.requireText(expected, "toolMode", expectedSource);
    }

    private static void validateOpenAiProviderContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "request", "provider");
        JsonSchemaV1.requireText(input, "provider", inputSource);
        JsonNode request = JsonSchemaV1.requireObject(input, "request", inputSource);
        String requestSource = JsonSchemaV1.path(inputSource, "request");
        JsonSchemaV1.exactObject(request, requestSource, "messages", "tools", "stream");
        validateMessages(
                JsonSchemaV1.requireArray(request, "messages", requestSource, true),
                JsonSchemaV1.path(requestSource, "messages"));
        JsonNode tools = JsonSchemaV1.requireArray(request, "tools", requestSource, true);
        for (int index = 0; index < tools.size(); index++) {
            JsonNode tool = tools.get(index);
            String toolSource = JsonSchemaV1.indexed(JsonSchemaV1.path(requestSource, "tools"), index);
            JsonSchemaV1.exactObject(tool, toolSource, "name");
            JsonSchemaV1.requireText(tool, "name", toolSource);
        }
        JsonSchemaV1.requireBoolean(request, "stream", requestSource);
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "requestRoleOrder",
                "toolNames",
                "streamingUpdatesAggregate",
                "typedErrorsPreserved",
                "cancellationPropagated",
                "providerTypesInSharedApi");
        JsonSchemaV1.requireTextArray(expected, "requestRoleOrder", expectedSource, true, false);
        JsonSchemaV1.requireTextArray(expected, "toolNames", expectedSource, true, true);
        requireBooleanFields(
                expected,
                expectedSource,
                "streamingUpdatesAggregate",
                "typedErrorsPreserved",
                "cancellationPropagated",
                "providerTypesInSharedApi");
    }

    private static void validateFoundryProviderContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "request", "provider");
        JsonSchemaV1.requireText(input, "provider", inputSource);
        JsonNode request = JsonSchemaV1.requireObject(input, "request", inputSource);
        String requestSource = JsonSchemaV1.path(inputSource, "request");
        JsonSchemaV1.exactObject(request, requestSource, "messages", "continuation");
        validateMessages(
                JsonSchemaV1.requireArray(request, "messages", requestSource, true),
                JsonSchemaV1.path(requestSource, "messages"));
        JsonNode continuation = JsonSchemaV1.requireObject(request, "continuation", requestSource);
        JsonSchemaV1.exactObject(continuation, JsonSchemaV1.path(requestSource, "continuation"), "conversationId");
        JsonSchemaV1.requireText(continuation, "conversationId", JsonSchemaV1.path(requestSource, "continuation"));
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "requestRoleOrder",
                "conversationIdPreservedBetweenToolIterations",
                "frameworkSessionIsolation",
                "providerTypesInSharedApi",
                "crossLanguageSessionWireCompatible");
        JsonSchemaV1.requireTextArray(expected, "requestRoleOrder", expectedSource, true, false);
        requireBooleanFields(
                expected,
                expectedSource,
                "conversationIdPreservedBetweenToolIterations",
                "frameworkSessionIsolation",
                "providerTypesInSharedApi",
                "crossLanguageSessionWireCompatible");
        requireFalse(expected, "crossLanguageSessionWireCompatible", expectedSource);
    }

    private static void validateHostingContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(
                input, inputSource, "fixtureSchemaVersion", "executionMode", "applicationResponsibilities");
        JsonSchemaV1.requirePositiveInteger(input, "fixtureSchemaVersion", inputSource);
        JsonSchemaV1.requireText(input, "executionMode", inputSource);
        JsonSchemaV1.requireTextArray(input, "applicationResponsibilities", inputSource, true, true);
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "manifestIndexed",
                "fixturesValidated",
                "runtimeDependencyOfPublishedModules",
                "publishedArtifact",
                "routesOwnedByHarness");
        requireBooleanFields(
                expected,
                expectedSource,
                "manifestIndexed",
                "fixturesValidated",
                "runtimeDependencyOfPublishedModules",
                "publishedArtifact",
                "routesOwnedByHarness");
    }

    private static JsonNode requiredNonEmptyObject(JsonNode root, String field, String sourceName) {
        JsonNode value = JsonSchemaV1.requireObject(root, field, sourceName);
        if (value.isEmpty()) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(sourceName, field) + " must not be empty.");
        }
        return value;
    }

    private static void requireCountFields(JsonNode object, String sourceName, String... fields) {
        for (String field : fields) {
            JsonSchemaV1.requireNonNegativeInteger(object, field, sourceName);
        }
    }

    private static void requireBooleanFields(JsonNode object, String sourceName, String... fields) {
        for (String field : fields) {
            JsonSchemaV1.requireBoolean(object, field, sourceName);
        }
    }

    private static void requireTrue(JsonNode object, String field, String sourceName) {
        if (!JsonSchemaV1.requireBoolean(object, field, sourceName)) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(sourceName, field) + " must be true.");
        }
    }

    private static void requireFalse(JsonNode object, String field, String sourceName) {
        if (JsonSchemaV1.requireBoolean(object, field, sourceName)) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(sourceName, field) + " must be false.");
        }
    }

    private static String optionalText(JsonNode object, String field, String sourceName) {
        return JsonSchemaV1.optionalText(object, field, sourceName);
    }

    private static final class MessageState {
        private final List<String> roles = new ArrayList<>();

        private final List<String> contentKinds = new ArrayList<>();

        private final List<String> messageTexts = new ArrayList<>();

        private final StringBuilder assistantText = new StringBuilder();

        private final Set<String> contentIds = new HashSet<>();

        private final Set<String> calls = new HashSet<>();

        private final Map<String, Integer> resultCounts = new HashMap<>();
    }
}
