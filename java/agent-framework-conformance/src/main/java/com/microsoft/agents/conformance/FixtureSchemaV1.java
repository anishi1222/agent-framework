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
                "pendingExecutors",
                "fanInNextEpochs");
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
        JsonNode fanInNextEpochs = JsonSchemaV1.requireObject(payload, "fanInNextEpochs", payloadSource);
        fanInNextEpochs.properties().forEach(entry -> {
            if (entry.getKey().isBlank()) {
                throw JsonSchemaV1.invalid(
                        JsonSchemaV1.path(payloadSource, "fanInNextEpochs") + " keys must be non-blank target ids.");
            }
            JsonSchemaV1.requireNonNegativeInteger(
                    fanInNextEpochs, entry.getKey(), JsonSchemaV1.path(payloadSource, "fanInNextEpochs"));
        });

        JsonNode bufferedInputs = JsonSchemaV1.requireArray(payload, "bufferedInputs", payloadSource, true);
        LinkedHashMap<String, List<String>> bufferedSources = new LinkedHashMap<>();
        Map<String, ArrayList<String>> mutableSources = new LinkedHashMap<>();
        Set<List<String>> bufferIds = new HashSet<>();
        for (int index = 0; index < bufferedInputs.size(); index++) {
            JsonNode buffered = bufferedInputs.get(index);
            String bufferedSource = JsonSchemaV1.indexed(JsonSchemaV1.path(payloadSource, "bufferedInputs"), index);
            JsonSchemaV1.exactObject(buffered, bufferedSource, "sourceId", "targetId", "value");
            String sourceId = JsonSchemaV1.requireText(buffered, "sourceId", bufferedSource);
            String targetId = JsonSchemaV1.requireText(buffered, "targetId", bufferedSource);
            JsonSchemaV1.require(buffered, "value", bufferedSource);
            if (!bufferIds.add(List.of(targetId, sourceId))) {
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
                "resumeFanInEpoch",
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
        int expectedResumeEpoch = JsonSchemaV1.requireNonNegativeInteger(expected, "resumeFanInEpoch", expectedSource);
        JsonSchemaV1.requireText(expected, "terminalOutcome", expectedSource);
        requireTrue(expected, "deterministicEncoding", expectedSource);
        requireTrue(expected, "roundTripWithinJavaV1", expectedSource);
        requireTrue(expected, "wrongDocumentKindRejected", expectedSource);
        requireTrue(expected, "unsupportedPayloadVersionRejected", expectedSource);
        requireExpectedJson(expected, "resumeFanInValues", nodes(resumeSummary.fanInValues()), expectedSource);
        if (resumeSummary.fanInEpochs().isEmpty() || resumeSummary.fanInEpochs().getLast() != expectedResumeEpoch) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(expectedSource, "resumeFanInEpoch")
                    + " must match the final resumed fan-in epoch.");
        }
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
            case "JCF-CORE-006" -> validateStructuredOutputContract(input, expected, inputSource, expectedSource);
            case "JCF-CORE-007" -> validateEmbeddingContract(input, expected, inputSource, expectedSource);
            case "JCF-TELEMETRY-001" -> validateFeatureStageContract(input, expected, inputSource, expectedSource);
            case "JCF-TELEMETRY-002" ->
                validateUserAgentFeatureUsageContract(input, expected, inputSource, expectedSource);
            case "JCF-TELEMETRY-003" -> validateOpenTelemetryContract(input, expected, inputSource, expectedSource);
            case "JCF-AGENTS-001" -> validateAgentLifecycleContract(input, expected, inputSource, expectedSource);
            case "JCF-AGENTS-002" -> validateAgentContextContract(input, expected, inputSource, expectedSource);
            case "JCF-AGENTS-003" -> validateMiddlewareContract(input, expected, inputSource, expectedSource);
            case "JCF-AGENTS-004" -> validateDelegatingAgentContract(input, expected, inputSource, expectedSource);
            case "JCF-AGENTS-005" -> validateMessageInjectionContract(input, expected, inputSource, expectedSource);
            case "JCF-AGENTS-006" -> validateAgentExtensionsContract(input, expected, inputSource, expectedSource);
            case "JCF-CONTEXT-001" -> validateContextCompactionContract(input, expected, inputSource, expectedSource);
            case "JCF-TOOLS-001" -> validateToolCapabilitiesContract(input, expected, inputSource, expectedSource);
            case "JCF-TOOLS-014" -> validateShellToolContract(input, expected, inputSource, expectedSource);
            case "JCF-SKILLS-001" -> validateSkillsProviderContract(input, expected, inputSource, expectedSource);
            case "JCF-SKILLS-002" -> validateSkillTypesContract(input, expected, inputSource, expectedSource);
            case "JCF-SKILLS-003" -> validateMcpSkillsContract(input, expected, inputSource, expectedSource);
            case "JCF-HARNESS-001" -> validateHarnessAgentContract(input, expected, inputSource, expectedSource);
            case "JCF-HARNESS-002" -> validateBackgroundAgentsContract(input, expected, inputSource, expectedSource);
            case "JCF-HARNESS-003" -> validateHarnessWorkspaceContract(input, expected, inputSource, expectedSource);
            case "JCF-PROVIDERS-001" -> validateOpenAiProviderContract(input, expected, inputSource, expectedSource);
            case "JCF-PROVIDERS-002" -> validateFoundryProviderContract(input, expected, inputSource, expectedSource);
            case "JCF-PROVIDERS-003",
                    "JCF-PROVIDERS-004",
                    "JCF-PROVIDERS-005",
                    "JCF-PROVIDERS-006",
                    "JCF-PROVIDERS-007",
                    "JCF-PROVIDERS-008" ->
                validateExternalProviderContract(input, expected, inputSource, expectedSource);
            case "JCF-PROVIDERS-009" ->
                validatePersistentProviderContract(input, expected, inputSource, expectedSource);
            case "JCF-PROVIDERS-010" ->
                validateGitHubCopilotProviderContract(input, expected, inputSource, expectedSource);
            case "JCF-PROVIDERS-011" ->
                validateCopilotStudioProviderContract(input, expected, inputSource, expectedSource);
            case "JCF-HOSTING-001" -> validateHostingContract(input, expected, inputSource, expectedSource);
            case "JCF-PROTOCOLS-001" -> validateMcpClientContract(input, expected, inputSource, expectedSource);
            case "JCF-PROTOCOLS-002" -> validateMcpHostingContract(input, expected, inputSource, expectedSource);
            case "JCF-PROTOCOLS-003" -> validateA2AClientContract(input, expected, inputSource, expectedSource);
            case "JCF-PROTOCOLS-004" -> validateAguiProtocolContract(input, expected, inputSource, expectedSource);
            case "JCF-HOSTING-002" -> validateA2AHostingContract(input, expected, inputSource, expectedSource);
            case "JCF-HOSTING-003" -> validateAguiHostingContract(input, expected, inputSource, expectedSource);
            case "JCF-HOSTING-004" -> validateFoundryHostingContract(input, expected, inputSource, expectedSource);
            case "JCF-HOSTING-TRANSPORT-001" ->
                validateGenericHostingTransportContract(input, expected, inputSource, expectedSource);
            case "JCF-INTEGRATIONS-001" ->
                validateContentUnderstandingContract(input, expected, inputSource, expectedSource);
            case "JCF-INTEGRATIONS-002" -> validatePurviewContract(input, expected, inputSource, expectedSource);
            case "JCF-INTEGRATIONS-003" ->
                validateFoundryEvaluationsContract(input, expected, inputSource, expectedSource);
            case "JCF-SESSIONS-004" -> validateCosmosStorageContract(input, expected, inputSource, expectedSource);
            case "JCF-SESSIONS-005" -> validateValkeyHistoryContract(input, expected, inputSource, expectedSource);
            case "JCF-WORKFLOWS-006" -> validateCosmosCheckpointContract(input, expected, inputSource, expectedSource);
            case "JCF-WORKFLOWS-007" ->
                validateWorkflowVisualizationContract(input, expected, inputSource, expectedSource);
            case "JCF-WORKFLOWS-008" ->
                validateFunctionalWorkflowContract(input, expected, inputSource, expectedSource);
            case "JCF-INTEGRATIONS-004" -> validateCosmosMemoryContract(input, expected, inputSource, expectedSource);
            case "JCF-INTEGRATIONS-005" -> validateMem0Contract(input, expected, inputSource, expectedSource);
            case "JCF-INTEGRATIONS-006" -> validateAzureAISearchContract(input, expected, inputSource, expectedSource);
            case "JCF-ORCHESTRATIONS-001" ->
                validateOrchestrationContract(input, expected, inputSource, expectedSource);
            default ->
                throw JsonSchemaV1.invalid(
                        sourceName + " has no contract schema for caseId '" + caseId + "' in schemaVersion 1.");
        }
    }

    private static void validatePersistentProviderContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(
                input, inputSource, "sdk", "serviceApiVersion", "runStatuses", "supportedTools", "unsupportedTools");
        requireExpectedText(input, "sdk", "com.azure:azure-ai-agents-persistent:1.0.0-beta.2", inputSource);
        requireExpectedText(input, "serviceApiVersion", "2025-05-15-preview", inputSource);
        requireExactTextArray(
                input,
                "runStatuses",
                List.of(
                        "queued",
                        "in_progress",
                        "requires_action",
                        "cancelling",
                        "cancelled",
                        "failed",
                        "completed",
                        "expired"),
                inputSource);
        requireExactTextArray(
                input,
                "supportedTools",
                List.of("code_interpreter", "file_search", "function", "openapi"),
                inputSource);
        requireExactTextArray(input, "unsupportedTools", List.of("mcp"), inputSource);

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "frameworkOwnedPublicApi",
                "sessionPersistsAgentThreadRunIds",
                "stableMessageIdsDeduplicated",
                "boundedScheduledPolling",
                "cancellationPropagated",
                "unknownStatusNeverSucceeds",
                "requiresActionExplicit",
                "callerResourcesNeverAutoDeleted");
        requireAllTrue(expected, expectedSource);
    }

    private static void validateGitHubCopilotProviderContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(
                input,
                inputSource,
                "sdk",
                "officialDocumentation",
                "canonicalSource",
                "protocolVersionSource",
                "minimumCliVersion",
                "transports",
                "supportedTokenPrefixes",
                "unsupportedTokenPrefix");
        requireExpectedText(input, "sdk", "com.github:copilot-sdk-java:1.0.9", inputSource);
        requireExpectedText(
                input, "officialDocumentation", "https://github.github.io/copilot-sdk-java/latest/", inputSource);
        requireExpectedText(
                input, "canonicalSource", "https://github.com/github/copilot-sdk/tree/main/java", inputSource);
        requireExpectedText(input, "protocolVersionSource", "com.github.copilot.SdkProtocolVersion", inputSource);
        requireExpectedText(input, "minimumCliVersion", "1.0.55", inputSource);
        requireExactTextArray(input, "transports", List.of("stdio", "loopback-tcp"), inputSource);
        requireExactTextArray(input, "supportedTokenPrefixes", List.of("gho_", "ghu_", "github_pat_"), inputSource);
        requireExpectedText(input, "unsupportedTokenPrefix", "ghp_", inputSource);

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "frameworkOwnedPublicApi",
                "officialSdkTypesPublic",
                "officialSdkOwnsProtocol",
                "frameworkProtocolImplementation",
                "protocolMismatchFails",
                "permissionsDenyByDefault",
                "classicPatSupported",
                "remotePlaintextSupported",
                "sessionStorageExternal",
                "cancellationPropagated",
                "boundedEvents",
                "callerResourcesPreserved",
                "mcpCommandsCallerDeclared");
        requireTrue(expected, "frameworkOwnedPublicApi", expectedSource);
        requireFalse(expected, "officialSdkTypesPublic", expectedSource);
        requireTrue(expected, "officialSdkOwnsProtocol", expectedSource);
        requireFalse(expected, "frameworkProtocolImplementation", expectedSource);
        requireTrue(expected, "protocolMismatchFails", expectedSource);
        requireTrue(expected, "permissionsDenyByDefault", expectedSource);
        requireFalse(expected, "classicPatSupported", expectedSource);
        requireFalse(expected, "remotePlaintextSupported", expectedSource);
        requireTrue(expected, "sessionStorageExternal", expectedSource);
        requireTrue(expected, "cancellationPropagated", expectedSource);
        requireTrue(expected, "boundedEvents", expectedSource);
        requireTrue(expected, "callerResourcesPreserved", expectedSource);
        requireTrue(expected, "mcpCommandsCallerDeclared", expectedSource);
    }

    private static void validateCopilotStudioProviderContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(
                input, inputSource, "protocol", "serviceApiVersion", "verifiedClients", "transport", "resumeHeader");
        requireExpectedText(input, "protocol", "power-platform-direct-to-engine", inputSource);
        requireExpectedText(input, "serviceApiVersion", "2022-03-01-preview", inputSource);
        requireExactTextArray(input, "verifiedClients", List.of("python:1.3.0", "dotnet:1.3.171-beta"), inputSource);
        requireExpectedText(input, "transport", "http-sse", inputSource);
        requireExpectedText(input, "resumeHeader", "Last-Event-ID", inputSource);

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "frameworkOwnedPublicApi",
                "officialJavaSdkAvailable",
                "redirectsFollowed",
                "tokensInUriOrLogs",
                "cursorUsesLastEventId",
                "directLineWatermarkUsed",
                "duplicatesDelivered",
                "oauthActionsAutoExecuted",
                "sessionCasMetadata",
                "conversationIdAuthorizes",
                "cancellationPropagated",
                "callerResourcesPreserved");
        requireTrue(expected, "frameworkOwnedPublicApi", expectedSource);
        requireFalse(expected, "officialJavaSdkAvailable", expectedSource);
        requireFalse(expected, "redirectsFollowed", expectedSource);
        requireFalse(expected, "tokensInUriOrLogs", expectedSource);
        requireTrue(expected, "cursorUsesLastEventId", expectedSource);
        requireFalse(expected, "directLineWatermarkUsed", expectedSource);
        requireFalse(expected, "duplicatesDelivered", expectedSource);
        requireFalse(expected, "oauthActionsAutoExecuted", expectedSource);
        requireTrue(expected, "sessionCasMetadata", expectedSource);
        requireFalse(expected, "conversationIdAuthorizes", expectedSource);
        requireTrue(expected, "cancellationPropagated", expectedSource);
        requireTrue(expected, "callerResourcesPreserved", expectedSource);
    }

    private static void validateFoundryHostingContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "executionEngine", "sessionPartition", "continuation", "surfaces");
        requireExpectedText(input, "executionEngine", "agent-framework-hosting", inputSource);
        requireExactTextArray(
                input,
                "sessionPartition",
                List.of("routeId", "principalId", "isolationId", "conversationId"),
                inputSource);
        requireExpectedText(input, "continuation", "opaque-one-time-process-local", inputSource);
        requireExactTextArray(input, "surfaces", List.of("responses", "persistent"), inputSource);

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "duplicateExecutionEngine",
                "threadRunConversationIdsAuthorize",
                "principalIsolationEnforced",
                "sessionStoreBoundedAndCasAware",
                "continuationsPrincipalBound",
                "callerResourcesRemainCallerOwned",
                "persistentStreamingLimitationExplicit");
        requireFalse(expected, "duplicateExecutionEngine", expectedSource);
        requireFalse(expected, "threadRunConversationIdsAuthorize", expectedSource);
        requireTrue(expected, "principalIsolationEnforced", expectedSource);
        requireTrue(expected, "sessionStoreBoundedAndCasAware", expectedSource);
        requireTrue(expected, "continuationsPrincipalBound", expectedSource);
        requireTrue(expected, "callerResourcesRemainCallerOwned", expectedSource);
        requireTrue(expected, "persistentStreamingLimitationExplicit", expectedSource);
    }

    private static void validateContentUnderstandingContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "sdk", "serviceApiVersion", "service", "inputs");
        requireExpectedText(input, "sdk", "com.azure:azure-ai-contentunderstanding:1.0.0", inputSource);
        requireExpectedText(input, "serviceApiVersion", "2025-11-01", inputSource);
        requireExpectedText(input, "service", "Azure AI Content Understanding", inputSource);
        requireExactTextArray(input, "inputs", List.of("https-url", "bytes"), inputSource);

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "documentIntelligenceConflated",
                "frameworkOwnedPublicApi",
                "querySecretsRedacted",
                "localUrlTargetsRejected",
                "payloadsAndPagesBounded",
                "pollingCancellationPropagated",
                "remoteCancelClaimed",
                "resourcesNeverAutoDeleted");
        requireFalse(expected, "documentIntelligenceConflated", expectedSource);
        requireTrue(expected, "frameworkOwnedPublicApi", expectedSource);
        requireTrue(expected, "querySecretsRedacted", expectedSource);
        requireTrue(expected, "localUrlTargetsRejected", expectedSource);
        requireTrue(expected, "payloadsAndPagesBounded", expectedSource);
        requireTrue(expected, "pollingCancellationPropagated", expectedSource);
        requireFalse(expected, "remoteCancelClaimed", expectedSource);
        requireTrue(expected, "resourcesNeverAutoDeleted", expectedSource);
    }

    private static void validatePurviewContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "graphVersion", "operations", "hookPoints", "executionModes");
        requireExpectedText(input, "graphVersion", "v1.0", inputSource);
        requireExactTextArray(
                input,
                "operations",
                List.of("protectionScopes.compute", "processContent", "contentActivities"),
                inputSource);
        requireExactTextArray(input, "hookPoints", List.of("agent", "chat"), inputSource);
        requireExactTextArray(input, "executionModes", List.of("evaluateInline", "evaluateOffline"), inputSource);

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "promptAndFiniteResponseEnforced",
                "failModeExplicit",
                "etagForwarded",
                "mostRestrictiveScopeWins",
                "backgroundWorkBounded",
                "streamingEgressLimitationExplicit",
                "telemetryContainsContentOrIdentity",
                "errorsSanitized");
        requireTrue(expected, "promptAndFiniteResponseEnforced", expectedSource);
        requireTrue(expected, "failModeExplicit", expectedSource);
        requireTrue(expected, "etagForwarded", expectedSource);
        requireTrue(expected, "mostRestrictiveScopeWins", expectedSource);
        requireTrue(expected, "backgroundWorkBounded", expectedSource);
        requireTrue(expected, "streamingEgressLimitationExplicit", expectedSource);
        requireFalse(expected, "telemetryContainsContentOrIdentity", expectedSource);
        requireTrue(expected, "errorsSanitized", expectedSource);
    }

    private static void validateFoundryEvaluationsContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "sdk", "serviceApiVersion", "evalPath", "discovery");
        requireExpectedText(input, "sdk", "com.azure:azure-ai-projects:2.3.0", inputSource);
        requireExpectedText(input, "serviceApiVersion", "v1", inputSource);
        requireExpectedText(input, "evalPath", "/openai/v1/evals", inputSource);
        requireExactTextArray(
                input,
                "discovery",
                List.of("connections", "datasets", "deployments", "indexes", "evaluators"),
                inputSource);

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "frameworkOwnedPublicApi",
                "runHandleAndCancellation",
                "pollingAndPaginationBounded",
                "unknownStatusNeverSucceeds",
                "evaluatorManagementMarkedPreview",
                "providerNeutralEvaluatorClaimed",
                "callerExecutorsRemainCallerOwned");
        requireTrue(expected, "frameworkOwnedPublicApi", expectedSource);
        requireTrue(expected, "runHandleAndCancellation", expectedSource);
        requireTrue(expected, "pollingAndPaginationBounded", expectedSource);
        requireTrue(expected, "unknownStatusNeverSucceeds", expectedSource);
        requireTrue(expected, "evaluatorManagementMarkedPreview", expectedSource);
        requireFalse(expected, "providerNeutralEvaluatorClaimed", expectedSource);
        requireTrue(expected, "callerExecutorsRemainCallerOwned", expectedSource);
    }

    private static void validateCosmosStorageContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "sdk", "partitionKeyPath", "authentication", "documentKinds");
        requireExpectedText(input, "sdk", "com.azure:azure-cosmos:4.81.0", inputSource);
        requireExpectedText(input, "partitionKeyPath", "/partitionKey", inputSource);
        requireExactTextArray(input, "authentication", List.of("rbac", "redacted-account-key"), inputSource);
        requireExactTextArray(
                input, "documentKinds", List.of("agent-session", "history-head", "history-message"), inputSource);
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "frameworkOwnedPublicApi",
                "etagCas",
                "monotonicSoftDeleteRevision",
                "exactlyOnceMessageIds",
                "pointReadsPartitionBound",
                "crossPartitionScanDefault",
                "payloadVersioned",
                "unknownFutureVersionRejected",
                "diagnosticsSanitized");
        requireTrue(expected, "frameworkOwnedPublicApi", expectedSource);
        requireTrue(expected, "etagCas", expectedSource);
        requireTrue(expected, "monotonicSoftDeleteRevision", expectedSource);
        requireTrue(expected, "exactlyOnceMessageIds", expectedSource);
        requireTrue(expected, "pointReadsPartitionBound", expectedSource);
        requireFalse(expected, "crossPartitionScanDefault", expectedSource);
        requireTrue(expected, "payloadVersioned", expectedSource);
        requireTrue(expected, "unknownFutureVersionRejected", expectedSource);
        requireTrue(expected, "diagnosticsSanitized", expectedSource);
    }

    private static void validateValkeyHistoryContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(
                input, inputSource, "sdk", "topology", "keyStructures", "documentKind", "appendPrimitive");
        requireExpectedText(input, "sdk", "io.valkey:valkey-glide:2.5.1", inputSource);
        requireExpectedText(input, "topology", "standalone", inputSource);
        requireExactTextArray(
                input, "keyStructures", List.of("messages-list", "dedup-hash", "dedup-order-list"), inputSource);
        requireExpectedText(input, "documentKind", "history-message", inputSource);
        requireExpectedText(input, "appendPrimitive", "same-slot-lua", inputSource);
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "frameworkOwnedPublicApi",
                "identifiersHashed",
                "hashSlotStable",
                "appendAtomic",
                "replayIdempotent",
                "conflictingDigestRejected",
                "retainedMessagesBounded",
                "dedupMetadataBounded",
                "ttlRefreshedAtomically",
                "corruptEntriesRejected",
                "cancellationAndDeadlineBounded",
                "externalClientCallerOwned",
                "crossLanguageWireCompatibilityClaimed");
        requireTrue(expected, "frameworkOwnedPublicApi", expectedSource);
        requireTrue(expected, "identifiersHashed", expectedSource);
        requireTrue(expected, "hashSlotStable", expectedSource);
        requireTrue(expected, "appendAtomic", expectedSource);
        requireTrue(expected, "replayIdempotent", expectedSource);
        requireTrue(expected, "conflictingDigestRejected", expectedSource);
        requireTrue(expected, "retainedMessagesBounded", expectedSource);
        requireTrue(expected, "dedupMetadataBounded", expectedSource);
        requireTrue(expected, "ttlRefreshedAtomically", expectedSource);
        requireTrue(expected, "corruptEntriesRejected", expectedSource);
        requireTrue(expected, "cancellationAndDeadlineBounded", expectedSource);
        requireTrue(expected, "externalClientCallerOwned", expectedSource);
        requireFalse(expected, "crossLanguageWireCompatibilityClaimed", expectedSource);
    }

    private static void validateCosmosCheckpointContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "sdk", "partitionStrategy", "capabilities", "documentKinds");
        requireExpectedText(input, "sdk", "com.azure:azure-cosmos:4.81.0", inputSource);
        requireExactTextArray(input, "partitionStrategy", List.of("workflowId", "checkpointId"), inputSource);
        requireExactTextArray(input, "capabilities", List.of("ATOMIC_CHECKPOINT_AND_LEDGER"), inputSource);
        requireExactTextArray(
                input,
                "documentKinds",
                List.of("checkpoint-head", "workflow-checkpoint", "invocation-ledger"),
                inputSource);
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "durableBackend",
                "etagCas",
                "duplicateCheckpointConflicts",
                "checkpointLedgerAtomic",
                "previousRevisionFingerprintPreserved",
                "fanInStateResumable",
                "boundedPartitionListing",
                "deterministicSnapshotSortKey",
                "boundedKeyScopedPurge",
                "purgePreservesOtherKeysAndLedger",
                "purgePartialReport",
                "purgeIdempotent",
                "crossLanguageWireCompatibilityClaimed");
        requireTrue(expected, "durableBackend", expectedSource);
        requireTrue(expected, "etagCas", expectedSource);
        requireTrue(expected, "duplicateCheckpointConflicts", expectedSource);
        requireTrue(expected, "checkpointLedgerAtomic", expectedSource);
        requireTrue(expected, "previousRevisionFingerprintPreserved", expectedSource);
        requireTrue(expected, "fanInStateResumable", expectedSource);
        requireTrue(expected, "boundedPartitionListing", expectedSource);
        requireTrue(expected, "deterministicSnapshotSortKey", expectedSource);
        requireTrue(expected, "boundedKeyScopedPurge", expectedSource);
        requireTrue(expected, "purgePreservesOtherKeysAndLedger", expectedSource);
        requireTrue(expected, "purgePartialReport", expectedSource);
        requireTrue(expected, "purgeIdempotent", expectedSource);
        requireFalse(expected, "crossLanguageWireCompatibilityClaimed", expectedSource);
    }

    private static void validateCosmosMemoryContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "sdk", "vectorPath", "fullTextPath", "searchModes", "filterModel");
        requireExpectedText(input, "sdk", "com.azure:azure-cosmos:4.81.0", inputSource);
        requireExpectedText(input, "vectorPath", "/vector", inputSource);
        requireExpectedText(input, "fullTextPath", "/content", inputSource);
        requireExactTextArray(input, "searchModes", List.of("full-text", "vector", "hybrid"), inputSource);
        requireExpectedText(input, "filterModel", "parameterized-metadata-equality", inputSource);
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "providerNeutralContracts",
                "tenantScopeMandatory",
                "etagCas",
                "vectorContractValidated",
                "queriesParameterized",
                "legalRankGrammar",
                "completeTopKPageConsumption",
                "serviceOrderPreserved",
                "sharedCursorCompatibility",
                "primitiveScoreSentinel",
                "stableListCompositeIndex",
                "fallbackExplicitAndBounded",
                "retrievedContentUntrusted",
                "citationsPresent");
        requireAllTrue(expected, expectedSource);
    }

    private static void validateMem0Contract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(
                input,
                inputSource,
                "transport",
                "openApi",
                "addPath",
                "searchPath",
                "listPath",
                "clearPath",
                "eventPath");
        requireExpectedText(input, "transport", "jdk-http", inputSource);
        requireExpectedText(input, "openApi", "https://docs.mem0.ai/openapi.json", inputSource);
        requireExpectedText(input, "addPath", "POST /v3/memories/add/", inputSource);
        requireExpectedText(input, "searchPath", "POST /v3/memories/search/", inputSource);
        requireExpectedText(input, "listPath", "POST /v3/memories/", inputSource);
        requireExpectedText(input, "clearPath", "DELETE /v1/memories/", inputSource);
        requireExpectedText(input, "eventPath", "GET /v1/event/{event_id}/", inputSource);

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "frameworkOwnedPublicApi",
                "officialJavaSdkClaimed",
                "contextProviderNotMemoryStore",
                "explicitScopeRequired",
                "batchedAdd",
                "sideEffectingAddRetried",
                "untrustedCitedRetrieval",
                "redirectsFollowed",
                "strictBoundedJson",
                "cancellationAndDeadlineBounded",
                "partialEventFailuresExplicit",
                "partitionedUserAgentScopes",
                "loopbackHttpSyntacticOnly",
                "continuePolicyTransientOnly",
                "unscopedItemOperationsExposed",
                "unscopedDeleteAllExposed",
                "historyEndpointExposed",
                "universalOssParityClaimed");
        requireTrue(expected, "frameworkOwnedPublicApi", expectedSource);
        requireFalse(expected, "officialJavaSdkClaimed", expectedSource);
        requireTrue(expected, "contextProviderNotMemoryStore", expectedSource);
        requireTrue(expected, "explicitScopeRequired", expectedSource);
        requireTrue(expected, "batchedAdd", expectedSource);
        requireFalse(expected, "sideEffectingAddRetried", expectedSource);
        requireTrue(expected, "untrustedCitedRetrieval", expectedSource);
        requireFalse(expected, "redirectsFollowed", expectedSource);
        requireTrue(expected, "strictBoundedJson", expectedSource);
        requireTrue(expected, "cancellationAndDeadlineBounded", expectedSource);
        requireTrue(expected, "partialEventFailuresExplicit", expectedSource);
        requireTrue(expected, "partitionedUserAgentScopes", expectedSource);
        requireTrue(expected, "loopbackHttpSyntacticOnly", expectedSource);
        requireTrue(expected, "continuePolicyTransientOnly", expectedSource);
        requireFalse(expected, "unscopedItemOperationsExposed", expectedSource);
        requireFalse(expected, "unscopedDeleteAllExposed", expectedSource);
        requireFalse(expected, "historyEndpointExposed", expectedSource);
        requireFalse(expected, "universalOssParityClaimed", expectedSource);
    }

    private static void validateAzureAISearchContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(
                input,
                inputSource,
                "sdk",
                "serviceApiVersion",
                "searchPath",
                "agenticPath",
                "queryModes",
                "authentication",
                "filterModel");
        requireExpectedText(input, "sdk", "com.azure:azure-search-documents:12.0.1", inputSource);
        requireExpectedText(input, "serviceApiVersion", "2026-04-01", inputSource);
        requireExpectedText(input, "searchPath", "POST /indexes('{indexName}')/docs/search.post.search", inputSource);
        requireExpectedText(input, "agenticPath", "POST /knowledgebases('{knowledgeBaseName}')/retrieve", inputSource);
        requireExactTextArray(
                input,
                "queryModes",
                List.of("full-text", "vector", "hybrid", "semantic", "semantic-hybrid", "agentic"),
                inputSource);
        requireExactTextArray(input, "authentication", List.of("azure-rbac", "api-key"), inputSource);
        requireExpectedText(input, "filterModel", "mandatory-tenant-and-scope-pre-filter", inputSource);

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "frameworkOwnedPublicApi",
                "sdkTypesPubliclyExposed",
                "contextProviderNotMemoryStore",
                "explicitScopeRequired",
                "tenantScopeFilterCannotBeOverridden",
                "staticFilterOnlyNarrows",
                "vectorPreFilter",
                "semanticHybridMinimumCandidates",
                "indexSchemaValidated",
                "existingKnowledgeBaseOnly",
                "knowledgeBaseSourcesSearchIndexOnly",
                "sourceFilterAddOnEverySource",
                "serverSideVectorizationValidated",
                "clientEmbeddingDimensionValidated",
                "captionsPreferred",
                "agenticSourceDataPreferred",
                "retrievedContentUntrusted",
                "citationsPresent",
                "cancellationAndDeadlineBounded",
                "continuePolicyTransientOnly",
                "sideEffectingDocumentApisExposed");
        requireTrue(expected, "frameworkOwnedPublicApi", expectedSource);
        requireFalse(expected, "sdkTypesPubliclyExposed", expectedSource);
        requireTrue(expected, "contextProviderNotMemoryStore", expectedSource);
        requireTrue(expected, "explicitScopeRequired", expectedSource);
        requireTrue(expected, "tenantScopeFilterCannotBeOverridden", expectedSource);
        requireTrue(expected, "staticFilterOnlyNarrows", expectedSource);
        requireTrue(expected, "vectorPreFilter", expectedSource);
        if (JsonSchemaV1.requireInteger(expected, "semanticHybridMinimumCandidates", expectedSource) != 50) {
            throw JsonSchemaV1.invalid(
                    JsonSchemaV1.path(expectedSource, "semanticHybridMinimumCandidates") + " must be 50.");
        }
        requireTrue(expected, "indexSchemaValidated", expectedSource);
        requireTrue(expected, "existingKnowledgeBaseOnly", expectedSource);
        requireTrue(expected, "knowledgeBaseSourcesSearchIndexOnly", expectedSource);
        requireTrue(expected, "sourceFilterAddOnEverySource", expectedSource);
        requireTrue(expected, "serverSideVectorizationValidated", expectedSource);
        requireTrue(expected, "clientEmbeddingDimensionValidated", expectedSource);
        requireTrue(expected, "captionsPreferred", expectedSource);
        requireTrue(expected, "agenticSourceDataPreferred", expectedSource);
        requireTrue(expected, "retrievedContentUntrusted", expectedSource);
        requireTrue(expected, "citationsPresent", expectedSource);
        requireTrue(expected, "cancellationAndDeadlineBounded", expectedSource);
        requireTrue(expected, "continuePolicyTransientOnly", expectedSource);
        requireFalse(expected, "sideEffectingDocumentApisExposed", expectedSource);
    }

    private static void requireExactTextArray(JsonNode object, String field, List<String> expected, String source) {
        List<String> actual = JsonSchemaV1.requireTextArray(object, field, source, true, false);
        if (!actual.equals(expected)) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(source, field) + " must equal " + expected + ".");
        }
    }

    private static void requireAllTrue(JsonNode object, String source) {
        object.fieldNames().forEachRemaining(field -> requireTrue(object, field, source));
    }

    private static void validateAguiProtocolContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(
                input,
                inputSource,
                "typescriptCore",
                "typescriptEncoder",
                "typescriptClient",
                "dotnetPackages",
                "communityJava",
                "officialEventCount",
                "transport",
                "runInputRequired");
        requireExpectedText(input, "typescriptCore", "@ag-ui/core@0.0.57", inputSource);
        requireExpectedText(input, "typescriptEncoder", "@ag-ui/encoder@0.0.57", inputSource);
        requireExpectedText(input, "typescriptClient", "@ag-ui/client@0.0.57", inputSource);
        requireExpectedText(input, "dotnetPackages", "AGUI.*@0.0.5", inputSource);
        requireExpectedText(input, "communityJava", "com.ag-ui.community:java-core:0.1.0", inputSource);
        if (JsonSchemaV1.requirePositiveInteger(input, "officialEventCount", inputSource) != 33) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(inputSource, "officialEventCount") + " must be 33.");
        }
        JsonSchemaV1.requireText(input, "transport", inputSource);
        JsonSchemaV1.requireTextArray(input, "runInputRequired", inputSource, true, true);

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "frameworkOwnedPublicApi",
                "exactScreamingSnakeCaseDiscriminators",
                "unknownStandardEventsRejected",
                "rawAndCustomPayloadsPreserved",
                "duplicateKeysTrailingContentAndNonfiniteRejected",
                "jsonPatchPointersValidated",
                "eventStateMachineEnforced",
                "officialEncoderFixtureAccepted",
                "webSocketClaimed",
                "lastEventIdReplayClaimed");
        requireTrue(expected, "frameworkOwnedPublicApi", expectedSource);
        requireTrue(expected, "exactScreamingSnakeCaseDiscriminators", expectedSource);
        requireTrue(expected, "unknownStandardEventsRejected", expectedSource);
        requireTrue(expected, "rawAndCustomPayloadsPreserved", expectedSource);
        requireTrue(expected, "duplicateKeysTrailingContentAndNonfiniteRejected", expectedSource);
        requireTrue(expected, "jsonPatchPointersValidated", expectedSource);
        requireTrue(expected, "eventStateMachineEnforced", expectedSource);
        requireTrue(expected, "officialEncoderFixtureAccepted", expectedSource);
        requireFalse(expected, "webSocketClaimed", expectedSource);
        requireFalse(expected, "lastEventIdReplayClaimed", expectedSource);
    }

    private static void validateAguiHostingContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(
                input,
                inputSource,
                "defaultPath",
                "requestMediaType",
                "responseMediaType",
                "routeKinds",
                "springAdapter",
                "capabilityDocument");
        requireExpectedText(input, "defaultPath", "/ag-ui", inputSource);
        requireExpectedText(input, "requestMediaType", "application/json", inputSource);
        requireExpectedText(input, "responseMediaType", "text/event-stream", inputSource);
        JsonSchemaV1.requireTextArray(input, "routeKinds", inputSource, true, true);
        JsonSchemaV1.requireText(input, "springAdapter", inputSource);
        JsonSchemaV1.requireText(input, "capabilityDocument", inputSource);

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "threadIdIsAuthorization",
                "principalAndIsolationBound",
                "threadStoreCasBoundedAndExpiring",
                "sameThreadConcurrentPolicy",
                "opaqueOneTimeResume",
                "crossPrincipalResumeRejected",
                "crossProcessResumeClaimed",
                "disconnectCancellation",
                "directTlsTerminationClaimed",
                "springGlobalSecurityOrCorsMutation",
                "springEmbeddedTomcatPresent");
        requireFalse(expected, "threadIdIsAuthorization", expectedSource);
        requireTrue(expected, "principalAndIsolationBound", expectedSource);
        requireTrue(expected, "threadStoreCasBoundedAndExpiring", expectedSource);
        requireExpectedText(expected, "sameThreadConcurrentPolicy", "reject", expectedSource);
        requireTrue(expected, "opaqueOneTimeResume", expectedSource);
        requireTrue(expected, "crossPrincipalResumeRejected", expectedSource);
        requireFalse(expected, "crossProcessResumeClaimed", expectedSource);
        requireTrue(expected, "disconnectCancellation", expectedSource);
        requireFalse(expected, "directTlsTerminationClaimed", expectedSource);
        requireFalse(expected, "springGlobalSecurityOrCorsMutation", expectedSource);
        requireFalse(expected, "springEmbeddedTomcatPresent", expectedSource);
    }

    private static void validateA2AClientContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(
                input,
                inputSource,
                "protocolRelease",
                "protocolVersion",
                "officialJavaSdk",
                "binding",
                "jsonRpcMediaType",
                "operations",
                "contentKinds");
        if (!"v1.0.1".equals(JsonSchemaV1.requireText(input, "protocolRelease", inputSource))) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(inputSource, "protocolRelease") + " must be v1.0.1.");
        }
        if (!"1.0".equals(JsonSchemaV1.requireText(input, "protocolVersion", inputSource))) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(inputSource, "protocolVersion") + " must be 1.0.");
        }
        if (!"org.a2aproject.sdk:1.2.0.Final".equals(JsonSchemaV1.requireText(input, "officialJavaSdk", inputSource))) {
            throw JsonSchemaV1.invalid(
                    JsonSchemaV1.path(inputSource, "officialJavaSdk") + " must pin the tested stable SDK.");
        }
        JsonSchemaV1.requireText(input, "binding", inputSource);
        if (!"application/json".equals(JsonSchemaV1.requireText(input, "jsonRpcMediaType", inputSource))) {
            throw JsonSchemaV1.invalid(
                    JsonSchemaV1.path(inputSource, "jsonRpcMediaType") + " must be application/json for JSON-RPC.");
        }
        JsonSchemaV1.requireTextArray(input, "operations", inputSource, true, true);
        JsonSchemaV1.requireTextArray(input, "contentKinds", inputSource, true, true);

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "frameworkOwnedPublicTypes",
                "sdkTypesInPublicApi",
                "officialSdkBidirectionalInterop",
                "boundedJsonAndSse",
                "explicitRunHandleCancellation",
                "protocolErrorsNeverSuccess",
                "lastEventIdReplayClaimed");
        JsonSchemaV1.requireBoolean(expected, "frameworkOwnedPublicTypes", expectedSource);
        JsonSchemaV1.requireBoolean(expected, "sdkTypesInPublicApi", expectedSource);
        JsonSchemaV1.requireBoolean(expected, "officialSdkBidirectionalInterop", expectedSource);
        JsonSchemaV1.requireBoolean(expected, "boundedJsonAndSse", expectedSource);
        JsonSchemaV1.requireBoolean(expected, "explicitRunHandleCancellation", expectedSource);
        JsonSchemaV1.requireBoolean(expected, "protocolErrorsNeverSuccess", expectedSource);
        JsonSchemaV1.requireBoolean(expected, "lastEventIdReplayClaimed", expectedSource);
    }

    private static void validateA2AHostingContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(
                input, inputSource, "adapters", "operations", "taskStates", "storeDimensions", "transport");
        JsonSchemaV1.requireTextArray(input, "adapters", inputSource, true, true);
        JsonSchemaV1.requireTextArray(input, "operations", inputSource, true, true);
        JsonSchemaV1.requireTextArray(input, "taskStates", inputSource, true, true);
        JsonSchemaV1.requireTextArray(input, "storeDimensions", inputSource, true, true);
        JsonSchemaV1.requireText(input, "transport", inputSource);

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "taskIdAuthorizes",
                "currentTaskFirstOnResubscribe",
                "artifactAppendAndLastChunk",
                "inputAndAuthBoundaries",
                "hostAndOriginValidated",
                "pushStored",
                "outboundPushDispatched",
                "crossLanguageStateClaimed");
        JsonSchemaV1.requireBoolean(expected, "taskIdAuthorizes", expectedSource);
        JsonSchemaV1.requireBoolean(expected, "currentTaskFirstOnResubscribe", expectedSource);
        JsonSchemaV1.requireBoolean(expected, "artifactAppendAndLastChunk", expectedSource);
        JsonSchemaV1.requireBoolean(expected, "inputAndAuthBoundaries", expectedSource);
        JsonSchemaV1.requireBoolean(expected, "hostAndOriginValidated", expectedSource);
        JsonSchemaV1.requireBoolean(expected, "pushStored", expectedSource);
        JsonSchemaV1.requireBoolean(expected, "outboundPushDispatched", expectedSource);
        JsonSchemaV1.requireBoolean(expected, "crossLanguageStateClaimed", expectedSource);
    }

    private static void validateGenericHostingTransportContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(
                input,
                inputSource,
                "wireVersion",
                "basePath",
                "collections",
                "operations",
                "webSocketPath",
                "webSocketSubprotocol",
                "webSocketClientFrames",
                "springAdapter");
        if (!"java-hosting-2026-08-01".equals(JsonSchemaV1.requireText(input, "wireVersion", inputSource))) {
            throw JsonSchemaV1.invalid(
                    JsonSchemaV1.path(inputSource, "wireVersion") + " must match the Java hosting wire version.");
        }
        if (!"/v1".equals(JsonSchemaV1.requireText(input, "basePath", inputSource))) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(inputSource, "basePath") + " must be /v1.");
        }
        JsonSchemaV1.requireTextArray(input, "collections", inputSource, true, true);
        JsonSchemaV1.requireTextArray(input, "operations", inputSource, true, true);
        if (!"/v1/ws".equals(JsonSchemaV1.requireText(input, "webSocketPath", inputSource))) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(inputSource, "webSocketPath") + " must be /v1/ws.");
        }
        if (!"agent-framework-hosting.v1"
                .equals(JsonSchemaV1.requireText(input, "webSocketSubprotocol", inputSource))) {
            throw JsonSchemaV1.invalid(
                    JsonSchemaV1.path(inputSource, "webSocketSubprotocol") + " must match the exact v1 subprotocol.");
        }
        JsonSchemaV1.requireTextArray(input, "webSocketClientFrames", inputSource, true, true);
        if (!"webflux-json-sse".equals(JsonSchemaV1.requireText(input, "springAdapter", inputSource))) {
            throw JsonSchemaV1.invalid(
                    JsonSchemaV1.path(inputSource, "springAdapter") + " must describe the supported Spring surface.");
        }

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "frameworkOwnedServerApi",
                "strictJsonAndMediaValidation",
                "hostOriginAndProxyValidated",
                "principalAndIsolationBound",
                "oneTimeProcessLocalResume",
                "boundedStreamingAndFrames",
                "disconnectCancellation",
                "lastEventIdReplayClaimed",
                "crossProcessResumeClaimed",
                "springWebSocketClaimed",
                "directTlsTerminationClaimed");
        requireTrue(expected, "frameworkOwnedServerApi", expectedSource);
        requireTrue(expected, "strictJsonAndMediaValidation", expectedSource);
        requireTrue(expected, "hostOriginAndProxyValidated", expectedSource);
        requireTrue(expected, "principalAndIsolationBound", expectedSource);
        requireTrue(expected, "oneTimeProcessLocalResume", expectedSource);
        requireTrue(expected, "boundedStreamingAndFrames", expectedSource);
        requireTrue(expected, "disconnectCancellation", expectedSource);
        requireFalse(expected, "lastEventIdReplayClaimed", expectedSource);
        requireFalse(expected, "crossProcessResumeClaimed", expectedSource);
        requireFalse(expected, "springWebSocketClaimed", expectedSource);
        requireFalse(expected, "directTlsTerminationClaimed", expectedSource);
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
                int expectedRevision = JsonSchemaV1.requireInteger(operation, "expectedRevision", sourceName);
                if (expectedRevision != -1 && expectedRevision <= 0) {
                    throw JsonSchemaV1.invalid(JsonSchemaV1.path(sourceName, "expectedRevision")
                            + " must be -1 for create or a positive opaque revision.");
                }
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
                        "resultCallId",
                        "resultInvocationId",
                        "resultOutcome",
                        "terminalCount");
                requireTrue(expected, "rejectionAccepted", sourceName);
                requireFalse(expected, "toolExecuted", sourceName);
                requireCountFields(expected, sourceName, "functionResultCount", "invocationCount", "terminalCount");
                JsonSchemaV1.requireText(expected, "resultCallId", sourceName);
                JsonSchemaV1.requireText(expected, "resultInvocationId", sourceName);
                JsonSchemaV1.requireText(expected, "resultOutcome", sourceName);
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
            case "JCF-TOOLS-013" -> {
                JsonSchemaV1.exactObject(
                        expected,
                        sourceName,
                        "approvedCallId",
                        "approvedInvocationCount",
                        "rejectedCallId",
                        "rejectedInvocationCount",
                        "functionResultCount",
                        "resultOrder",
                        "resultOutcomes",
                        "terminalCount");
                JsonSchemaV1.requireText(expected, "approvedCallId", sourceName);
                JsonSchemaV1.requireText(expected, "rejectedCallId", sourceName);
                requireCountFields(
                        expected,
                        sourceName,
                        "approvedInvocationCount",
                        "rejectedInvocationCount",
                        "functionResultCount",
                        "terminalCount");
                JsonSchemaV1.requireTextArray(expected, "resultOrder", sourceName, true, true);
                JsonSchemaV1.requireTextArray(expected, "resultOutcomes", sourceName, true, true);
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
                String resultCallId = only(lane.resultOrder(), "rejection result call");
                requireExpectedText(expected, "resultCallId", resultCallId, sourceName);
                requireExpectedText(
                        expected, "resultInvocationId", lane.invocationIds().get(resultCallId), sourceName);
                requireExpectedText(
                        expected, "resultOutcome", lane.resultOutcomes().get(resultCallId), sourceName);
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
            case "JCF-TOOLS-013" -> {
                String approvedCallId = only(
                        lane.resultOrder().stream()
                                .filter(callId ->
                                        !"rejected".equals(lane.resultOutcomes().get(callId)))
                                .toList(),
                        "approved result call");
                String rejectedCallId = only(
                        lane.resultOrder().stream()
                                .filter(callId ->
                                        "rejected".equals(lane.resultOutcomes().get(callId)))
                                .toList(),
                        "rejected result call");
                requireExpectedText(expected, "approvedCallId", approvedCallId, sourceName);
                requireExpectedText(expected, "rejectedCallId", rejectedCallId, sourceName);
                requireExpectedCount(
                        expected,
                        "approvedInvocationCount",
                        lane.invocationCounts().getOrDefault(approvedCallId, 0),
                        sourceName);
                requireExpectedCount(
                        expected,
                        "rejectedInvocationCount",
                        lane.invocationCounts().getOrDefault(rejectedCallId, 0),
                        sourceName);
                requireExpectedCount(expected, "functionResultCount", lane.functionResultCount(), sourceName);
                requireExpectedJson(expected, "resultOrder", strings(lane.resultOrder()), sourceName);
                requireExpectedJson(
                        expected,
                        "resultOutcomes",
                        strings(lane.resultOrder().stream()
                                .map(lane.resultOutcomes()::get)
                                .toList()),
                        sourceName);
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

    private static void validateStructuredOutputContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(
                input,
                inputSource,
                "options",
                "assistantMessages",
                "malformedText",
                "maxDocumentBytes",
                "oversizedText");
        JsonNode options = JsonSchemaV1.requireObject(input, "options", inputSource);
        String optionsSource = JsonSchemaV1.path(inputSource, "options");
        JsonSchemaV1.exactObject(options, optionsSource, "name", "description", "strict", "schema");
        JsonSchemaV1.requireText(options, "name", optionsSource);
        JsonSchemaV1.requireText(options, "description", optionsSource);
        JsonSchemaV1.requireBoolean(options, "strict", optionsSource);
        JsonNode schema = JsonSchemaV1.requireObject(options, "schema", optionsSource);
        if (schema.isEmpty()) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(optionsSource, "schema") + " must not be empty.");
        }
        JsonNode messages = JsonSchemaV1.requireArray(input, "assistantMessages", inputSource, true);
        String messagesSource = JsonSchemaV1.path(inputSource, "assistantMessages");
        for (int index = 0; index < messages.size(); index++) {
            JsonNode message = messages.get(index);
            String messageSource = JsonSchemaV1.indexed(messagesSource, index);
            JsonSchemaV1.exactObject(message, messageSource, "role", "text");
            requireExpectedText(message, "role", "assistant", messageSource);
            JsonSchemaV1.requireString(message, "text", messageSource);
        }
        JsonSchemaV1.requireText(input, "malformedText", inputSource);
        JsonSchemaV1.requirePositiveInteger(input, "maxDocumentBytes", inputSource);
        JsonSchemaV1.requireText(input, "oversizedText", inputSource);

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "decoded",
                "schemaPreserved",
                "lastNonEmptyAssistantText",
                "responseMetadataPreserved",
                "noAssistantTextProducesNull",
                "malformedJsonRejected",
                "boundedJsonEnforced",
                "providerTypesInPublicApi");
        requiredNonEmptyObject(expected, "decoded", expectedSource);
        requireBooleanFields(
                expected,
                expectedSource,
                "schemaPreserved",
                "lastNonEmptyAssistantText",
                "responseMetadataPreserved",
                "noAssistantTextProducesNull",
                "malformedJsonRejected",
                "boundedJsonEnforced",
                "providerTypesInPublicApi");
        requireTrue(expected, "schemaPreserved", expectedSource);
        requireTrue(expected, "lastNonEmptyAssistantText", expectedSource);
        requireTrue(expected, "responseMetadataPreserved", expectedSource);
        requireTrue(expected, "noAssistantTextProducesNull", expectedSource);
        requireTrue(expected, "malformedJsonRejected", expectedSource);
        requireTrue(expected, "boundedJsonEnforced", expectedSource);
        requireFalse(expected, "providerTypesInPublicApi", expectedSource);
    }

    private static void validateEmbeddingContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "values", "options", "vectors");
        List<String> values = JsonSchemaV1.requireTextArray(input, "values", inputSource, true, false);
        JsonNode options = JsonSchemaV1.requireObject(input, "options", inputSource);
        String optionsSource = JsonSchemaV1.path(inputSource, "options");
        JsonSchemaV1.exactObject(options, optionsSource, "model", "dimensions", "metadata");
        JsonSchemaV1.requireText(options, "model", optionsSource);
        int dimensions = JsonSchemaV1.requirePositiveInteger(options, "dimensions", optionsSource);
        JsonNode metadata = JsonSchemaV1.requireObject(options, "metadata", optionsSource);
        String metadataSource = JsonSchemaV1.path(optionsSource, "metadata");
        JsonSchemaV1.exactObject(metadata, metadataSource, "scope");
        JsonSchemaV1.requireText(metadata, "scope", metadataSource);
        JsonNode vectors = JsonSchemaV1.requireArray(input, "vectors", inputSource, true);
        if (vectors.size() != values.size()) {
            throw JsonSchemaV1.invalid(
                    JsonSchemaV1.path(inputSource, "vectors") + " must contain one vector per input value.");
        }
        String vectorsSource = JsonSchemaV1.path(inputSource, "vectors");
        for (int index = 0; index < vectors.size(); index++) {
            JsonNode vector = vectors.get(index);
            String vectorSource = JsonSchemaV1.indexed(vectorsSource, index);
            if (!vector.isArray() || vector.size() != dimensions) {
                throw JsonSchemaV1.invalid(vectorSource + " must contain exactly " + dimensions + " values.");
            }
            for (int component = 0; component < vector.size(); component++) {
                if (!vector.get(component).isNumber()) {
                    throw JsonSchemaV1.invalid(
                            JsonSchemaV1.indexed(vectorSource, component) + " must be a JSON number.");
                }
            }
        }

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "count",
                "dimensions",
                "model",
                "inputTokens",
                "orderPreserved",
                "callerCollectionsUnchanged",
                "invalidDimensionRejected",
                "nonFiniteVectorRejected",
                "cancelledRunRejected",
                "providerTypesInPublicApi");
        if (JsonSchemaV1.requirePositiveInteger(expected, "count", expectedSource) != values.size()) {
            throw JsonSchemaV1.invalid(
                    JsonSchemaV1.path(expectedSource, "count") + " must match the number of input values.");
        }
        JsonSchemaV1.requireIntegerArray(expected, "dimensions", expectedSource, true);
        JsonSchemaV1.requireText(expected, "model", expectedSource);
        JsonSchemaV1.requirePositiveInteger(expected, "inputTokens", expectedSource);
        requireBooleanFields(
                expected,
                expectedSource,
                "orderPreserved",
                "callerCollectionsUnchanged",
                "invalidDimensionRejected",
                "nonFiniteVectorRejected",
                "cancelledRunRejected",
                "providerTypesInPublicApi");
        requireTrue(expected, "orderPreserved", expectedSource);
        requireTrue(expected, "callerCollectionsUnchanged", expectedSource);
        requireTrue(expected, "invalidDimensionRejected", expectedSource);
        requireTrue(expected, "nonFiniteVectorRejected", expectedSource);
        requireTrue(expected, "cancelledRunRejected", expectedSource);
        requireFalse(expected, "providerTypesInPublicApi", expectedSource);
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

    private static void validateDelegatingAgentContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "agent", "closeInnerAgent");
        JsonNode agent = JsonSchemaV1.requireObject(input, "agent", inputSource);
        String agentSource = JsonSchemaV1.path(inputSource, "agent");
        JsonSchemaV1.exactObject(agent, agentSource, "id", "name");
        JsonSchemaV1.requireText(agent, "id", agentSource);
        JsonSchemaV1.requireText(agent, "name", agentSource);
        requireTrue(input, "closeInnerAgent", inputSource);
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "metadataForwarded",
                "runHandleForwarded",
                "streamPublisherForwarded",
                "callerOwnedByDefault",
                "ownedInnerClosed");
        requireAllTrue(expected, expectedSource);
    }

    private static void validateMessageInjectionContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "stateKey", "messages");
        requireExpectedText(input, "stateKey", "messageInjection.pendingMessages", inputSource);
        JsonNode messages = JsonSchemaV1.requireObject(input, "messages", inputSource);
        String messagesSource = JsonSchemaV1.path(inputSource, "messages");
        JsonSchemaV1.exactObject(messages, messagesSource, "initial", "prequeued", "duringResponse", "fromTool");
        JsonSchemaV1.requireText(messages, "initial", messagesSource);
        JsonSchemaV1.requireText(messages, "prequeued", messagesSource);
        JsonSchemaV1.requireText(messages, "duringResponse", messagesSource);
        JsonSchemaV1.requireText(messages, "fromTool", messagesSource);
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "queueStoredInSessionState",
                "prequeuedDeliveredOnce",
                "nonActionableResponseTriggersNextTurn",
                "informationalCallsDoNotBlock",
                "actionableCallsDeferUntilToolResult",
                "conversationIdPropagated",
                "streamingEquivalent",
                "sessionRequired");
        requireAllTrue(expected, expectedSource);
    }

    private static void validateAgentExtensionsContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "metadata", "messages");
        JsonNode metadata = JsonSchemaV1.requireObject(input, "metadata", inputSource);
        String metadataSource = JsonSchemaV1.path(inputSource, "metadata");
        JsonSchemaV1.exactObject(metadata, metadataSource, "key", "initial", "replacement");
        requireExpectedText(metadata, "key", "priority", metadataSource);
        requireExpectedText(metadata, "initial", "one", metadataSource);
        requireExpectedText(metadata, "replacement", "two", metadataSource);
        JsonNode messages = JsonSchemaV1.requireObject(input, "messages", inputSource);
        String messagesSource = JsonSchemaV1.path(inputSource, "messages");
        JsonSchemaV1.exactObject(
                messages,
                messagesSource,
                "history",
                "context",
                "external",
                "contextProviderId",
                "historySourceId",
                "callerSourceId");
        requireExpectedText(messages, "history", "history", messagesSource);
        requireExpectedText(messages, "context", "context", messagesSource);
        requireExpectedText(messages, "external", "external", messagesSource);
        requireExpectedText(messages, "contextProviderId", "context-1", messagesSource);
        requireExpectedText(messages, "historySourceId", "history", messagesSource);
        requireExpectedText(messages, "callerSourceId", "caller", messagesSource);
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "typedMetadataRoundTrips",
                "metadataCopyOnWrite",
                "existingMetadataPreserved",
                "existingMetadataAvoidsEncoding",
                "wrongMetadataTypeRejected",
                "typeNamedKeySupported",
                "responseFieldsPreserved",
                "updateFieldsPreserved",
                "streamAggregationPreserved",
                "sessionHistoryDetached",
                "defaultSourceType",
                "sourceOrder",
                "sameSourceReturnsOriginal",
                "malformedAttributionRejected");
        requireBooleanFields(
                expected,
                expectedSource,
                "typedMetadataRoundTrips",
                "metadataCopyOnWrite",
                "existingMetadataPreserved",
                "existingMetadataAvoidsEncoding",
                "wrongMetadataTypeRejected",
                "typeNamedKeySupported",
                "responseFieldsPreserved",
                "updateFieldsPreserved",
                "streamAggregationPreserved",
                "sessionHistoryDetached",
                "sameSourceReturnsOriginal",
                "malformedAttributionRejected");
        requireExpectedText(expected, "defaultSourceType", "External", expectedSource);
        requireExactTextArray(
                expected,
                "sourceOrder",
                List.of("ChatHistory:history", "AIContextProvider:context-1", "External:caller"),
                expectedSource);
    }

    private static void validateContextCompactionContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "keepLastToolCallGroups", "toolGroups", "contextWindow");
        if (JsonSchemaV1.requireNonNegativeInteger(input, "keepLastToolCallGroups", inputSource) != 1) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(inputSource, "keepLastToolCallGroups") + " must be 1.");
        }
        JsonNode groups = JsonSchemaV1.requireArray(input, "toolGroups", inputSource, true);
        if (groups.size() != 3) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(inputSource, "toolGroups") + " must contain 3 groups.");
        }
        List<String> expectedCallIds = List.of("old", "middle", "recent");
        List<String> expectedNames = List.of("weather", "search", "calendar");
        List<String> expectedResults = List.of("sunny", "three documents", "booked");
        for (int index = 0; index < groups.size(); index++) {
            JsonNode group = groups.get(index);
            String groupSource = JsonSchemaV1.indexed(JsonSchemaV1.path(inputSource, "toolGroups"), index);
            JsonSchemaV1.exactObject(
                    group, groupSource, "callId", "name", "result", "callMessageId", "resultMessageId");
            requireExpectedText(group, "callId", expectedCallIds.get(index), groupSource);
            requireExpectedText(group, "name", expectedNames.get(index), groupSource);
            requireExpectedText(group, "result", expectedResults.get(index), groupSource);
            requireExpectedText(group, "callMessageId", expectedCallIds.get(index) + "-call", groupSource);
            requireExpectedText(group, "resultMessageId", expectedCallIds.get(index) + "-result", groupSource);
        }
        JsonNode contextWindow = JsonSchemaV1.requireObject(input, "contextWindow", inputSource);
        String contextWindowSource = JsonSchemaV1.path(inputSource, "contextWindow");
        JsonSchemaV1.exactObject(
                contextWindow,
                contextWindowSource,
                "maxContextWindowTokens",
                "maxOutputTokens",
                "toolEvictionThreshold",
                "truncationThreshold");
        if (JsonSchemaV1.requirePositiveInteger(contextWindow, "maxContextWindowTokens", contextWindowSource) != 100) {
            throw JsonSchemaV1.invalid(
                    JsonSchemaV1.path(contextWindowSource, "maxContextWindowTokens") + " must be 100.");
        }
        if (JsonSchemaV1.requireNonNegativeInteger(contextWindow, "maxOutputTokens", contextWindowSource) != 0) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(contextWindowSource, "maxOutputTokens") + " must be 0.");
        }
        JsonSchemaV1.requireNumber(contextWindow, "toolEvictionThreshold", contextWindowSource);
        JsonSchemaV1.requireNumber(contextWindow, "truncationThreshold", contextWindowSource);
        if (contextWindow.get("toolEvictionThreshold").doubleValue() != 0.5
                || contextWindow.get("truncationThreshold").doubleValue() != 0.8) {
            throw JsonSchemaV1.invalid(contextWindowSource + " thresholds must be 0.5 and 0.8.");
        }

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "selectiveMessageIds",
                "toolSummaryTexts",
                "toolSummarySourceIds",
                "contextWindowSummaryCount",
                "contextWindowRetainedCallIds",
                "atomicFallbackMessageIds",
                "multipleSummaryIdsExposed",
                "resolvedPreambleProtected",
                "composedEarlyStop",
                "deterministic");
        requireExactTextArray(
                expected,
                "selectiveMessageIds",
                List.of("user", "recent-call", "recent-result", "done"),
                expectedSource);
        requireExactTextArray(
                expected,
                "toolSummaryTexts",
                List.of("[Tool results: weather: sunny]", "[Tool results: search: three documents]"),
                expectedSource);
        requireExactTextArray(
                expected,
                "toolSummarySourceIds",
                List.of("old-call", "old-result", "middle-call", "middle-result"),
                expectedSource);
        requireExpectedCount(expected, "contextWindowSummaryCount", 2, expectedSource);
        requireExactTextArray(expected, "contextWindowRetainedCallIds", List.of("recent"), expectedSource);
        requireExactTextArray(expected, "atomicFallbackMessageIds", List.of("latest"), expectedSource);
        requireBooleanFields(
                expected,
                expectedSource,
                "multipleSummaryIdsExposed",
                "resolvedPreambleProtected",
                "composedEarlyStop",
                "deterministic");
    }

    private static void validateFeatureStageContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "annotations", "experimentalFeatureIds");
        requireExactTextArray(input, "annotations", List.of("Experimental", "ReleaseCandidate"), inputSource);
        requireExactTextArray(
                input,
                "experimentalFeatureIds",
                List.of(
                        "DECLARATIVE_AGENTS",
                        "EVALS",
                        "FILE_HISTORY",
                        "FIDES",
                        "FOUNDRY_TOOLS",
                        "FOUNDRY_PREVIEW_TOOLS",
                        "FUNCTIONAL_WORKFLOWS",
                        "HARNESS",
                        "MCP_LONG_RUNNING_TASKS",
                        "MCP_SKILLS",
                        "PROGRESSIVE_TOOLS",
                        "SESSION_STORE",
                        "TO_PROMPT_AGENT"),
                inputSource);
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "runtimeRetention",
                "lifecycleTargetsPresent",
                "featureIdsMatch",
                "stageMetadataDiscoverable",
                "inheritedTypeMetadata",
                "warningDeduplicated",
                "conflictingStagesRejected",
                "releaseCandidateInventoryEmpty");
        requireAllTrue(expected, expectedSource);
    }

    private static void validateUserAgentFeatureUsageContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(
                input,
                inputSource,
                "registryVersion",
                "width",
                "marks",
                "invalidIndexes",
                "frameworkVersion",
                "prefixes",
                "existingUserAgent",
                "staleUserAgent",
                "approvedOrigin",
                "deniedOrigin",
                "approvedOriginSuffixes");
        if (JsonSchemaV1.requireInteger(input, "registryVersion", inputSource) != 1) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(inputSource, "registryVersion") + " must be 1.");
        }

        if (JsonSchemaV1.requireInteger(input, "width", inputSource) != 128) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(inputSource, "width") + " must be 128.");
        }
        JsonSchemaV1.requireIntegerArray(input, "marks", inputSource, true);
        JsonSchemaV1.requireIntegerArray(input, "invalidIndexes", inputSource, true);
        requireExpectedText(input, "frameworkVersion", "1.2.3", inputSource);
        requireExactTextArray(input, "prefixes", List.of("outer", "foundry-hosting", "outer"), inputSource);
        requireExpectedText(input, "existingUserAgent", "sdk-java/4.50.0", inputSource);
        requireExpectedText(
                input,
                "staleUserAgent",
                "foundry-hosting/outer/agent-framework-java/1.2.3 (custom=value) (feat=v1.1)",
                inputSource);
        requireExpectedText(
                input, "approvedOrigin", "https://resource.openai.azure.com/openai/v1/responses", inputSource);
        requireExpectedText(input, "deniedOrigin", "https://gateway.example.com/openai/v1/responses", inputSource);
        requireExactTextArray(
                input,
                "approvedOriginSuffixes",
                List.of("cognitiveservices.azure.com", "openai.azure.com", "services.ai.azure.com"),
                inputSource);
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "featureToken",
                "concurrentFeatureToken",
                "prefixedUserAgent",
                "prependedUserAgent",
                "refreshedUserAgent",
                "approvedOriginUserAgent",
                "deniedOriginUserAgent",
                "disabledUserAgent",
                "duplicateMarksDeduplicated",
                "invalidIndexesRejected",
                "featureTokenLiveAtRequestTime",
                "approvedOriginRequired",
                "baseUserAgentPreservedWhenMaskDisabled",
                "threadSafe");
        JsonSchemaV1.requireText(expected, "featureToken", expectedSource);
        JsonSchemaV1.requireText(expected, "concurrentFeatureToken", expectedSource);
        JsonSchemaV1.requireText(expected, "prefixedUserAgent", expectedSource);
        JsonSchemaV1.requireText(expected, "prependedUserAgent", expectedSource);
        JsonSchemaV1.requireText(expected, "refreshedUserAgent", expectedSource);
        JsonSchemaV1.requireText(expected, "approvedOriginUserAgent", expectedSource);
        JsonSchemaV1.requireText(expected, "deniedOriginUserAgent", expectedSource);
        JsonSchemaV1.requireText(expected, "disabledUserAgent", expectedSource);
        requireTrue(expected, "duplicateMarksDeduplicated", expectedSource);
        requireTrue(expected, "invalidIndexesRejected", expectedSource);
        requireTrue(expected, "featureTokenLiveAtRequestTime", expectedSource);
        requireTrue(expected, "approvedOriginRequired", expectedSource);
        requireTrue(expected, "baseUserAgentPreservedWhenMaskDisabled", expectedSource);
        requireTrue(expected, "threadSafe", expectedSource);
    }

    private static void validateOpenTelemetryContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "providerName", "operations", "cancelledOperation");
        requireExpectedText(input, "providerName", "openai", inputSource);
        requireExactTextArray(
                input, "operations", List.of("invoke_workflow", "invoke_agent", "chat", "execute_tool"), inputSource);
        requireExpectedText(input, "cancelledOperation", "execute_tool", inputSource);
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "spanCount",
                "workflowParentRoot",
                "agentParentWorkflow",
                "chatParentAgent",
                "toolParentAgent",
                "cancelledOutcome",
                "terminalIdempotent",
                "contentCapturedByDefault",
                "durationMetrics");
        if (JsonSchemaV1.requireInteger(expected, "spanCount", expectedSource) != 4) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(expectedSource, "spanCount") + " must be 4.");
        }
        JsonSchemaV1.requireBoolean(expected, "workflowParentRoot", expectedSource);
        JsonSchemaV1.requireBoolean(expected, "agentParentWorkflow", expectedSource);
        JsonSchemaV1.requireBoolean(expected, "chatParentAgent", expectedSource);
        JsonSchemaV1.requireBoolean(expected, "toolParentAgent", expectedSource);
        requireExpectedText(expected, "cancelledOutcome", "cancelled", expectedSource);
        JsonSchemaV1.requireBoolean(expected, "terminalIdempotent", expectedSource);
        JsonSchemaV1.requireBoolean(expected, "contentCapturedByDefault", expectedSource);
        requireExactTextArray(
                expected,
                "durationMetrics",
                List.of(
                        "gen_ai.invoke_workflow.duration",
                        "gen_ai.invoke_agent.duration",
                        "gen_ai.client.operation.duration",
                        "gen_ai.execute_tool.duration"),
                expectedSource);
    }

    private static void validateWorkflowVisualizationContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "formats", "entryId", "outputId", "nodeIds");
        requireExactTextArray(input, "formats", List.of("dot", "mermaid"), inputSource);
        requireExpectedText(input, "entryId", "1 start", inputSource);
        requireExpectedText(input, "outputId", "end", inputSource);
        requireExactTextArray(
                input, "nodeIds", List.of("1 start", "end", "join|quoted", "left side", "left-side"), inputSource);
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "deterministic",
                "startStyled",
                "outputStyled",
                "conditionalStyled",
                "fanOutRendered",
                "fanInJunctionRendered",
                "labelsEscaped",
                "aliasesCollisionSafe",
                "externalProcessInvoked",
                "utf8WriteRoundTrip");
        requireTrue(expected, "deterministic", expectedSource);
        requireTrue(expected, "startStyled", expectedSource);
        requireTrue(expected, "outputStyled", expectedSource);
        requireTrue(expected, "conditionalStyled", expectedSource);
        requireTrue(expected, "fanOutRendered", expectedSource);
        requireTrue(expected, "fanInJunctionRendered", expectedSource);
        requireTrue(expected, "labelsEscaped", expectedSource);
        requireTrue(expected, "aliasesCollisionSafe", expectedSource);
        requireFalse(expected, "externalProcessInvoked", expectedSource);
        requireTrue(expected, "utf8WriteRoundTrip", expectedSource);
    }

    private static void validateFunctionalWorkflowContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(
                input, inputSource, "workflowId", "signatureVersion", "checkpointKey", "steps", "responses");
        requireExpectedText(input, "workflowId", "functional-hitl", inputSource);
        requireExpectedText(input, "signatureVersion", "1", inputSource);
        requireExpectedText(input, "checkpointKey", "functional-hitl", inputSource);
        requireExactTextArray(input, "steps", List.of("first-review", "second-review"), inputSource);
        JsonNode responses = JsonSchemaV1.requireArray(input, "responses", inputSource, true);
        if (responses.size() != 2) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(inputSource, "responses") + " must contain 2 entries.");
        }
        for (int index = 0; index < responses.size(); index++) {
            JsonNode response = responses.get(index);
            String responseSource = JsonSchemaV1.indexed(JsonSchemaV1.path(inputSource, "responses"), index);
            JsonSchemaV1.exactObject(response, responseSource, "requestId", "value");
            requireExpectedText(response, "requestId", "auto::" + index, responseSource);
            requireExpectedText(response, "value", index == 0 ? "approved" : "published", responseSource);
        }

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "firstPendingRequest",
                "secondPendingRequest",
                "finalOutput",
                "completedStepBypassed",
                "autoRequestIdsStable",
                "multipleRequestsPerStepResume",
                "userStateRestored",
                "checkpointParentChained",
                "checkpointResumeAcrossInstances",
                "terminalCountPerInvocation",
                "concurrentRunRejected",
                "agentInputMapped",
                "agentOutputMapped",
                "agentRequestSurfaced",
                "agentResponseResumed",
                "agentStreamingMapped",
                "crossLanguageWireCompatible");
        requireExpectedText(expected, "firstPendingRequest", "auto::0", expectedSource);
        requireExpectedText(expected, "secondPendingRequest", "auto::1", expectedSource);
        requireExpectedText(expected, "finalOutput", "draft-approved-published", expectedSource);
        requireTrue(expected, "completedStepBypassed", expectedSource);
        requireTrue(expected, "autoRequestIdsStable", expectedSource);
        requireTrue(expected, "multipleRequestsPerStepResume", expectedSource);
        requireTrue(expected, "userStateRestored", expectedSource);
        requireTrue(expected, "checkpointParentChained", expectedSource);
        requireTrue(expected, "checkpointResumeAcrossInstances", expectedSource);
        requireExpectedCount(expected, "terminalCountPerInvocation", 1, expectedSource);
        requireTrue(expected, "concurrentRunRejected", expectedSource);
        requireTrue(expected, "agentInputMapped", expectedSource);
        requireTrue(expected, "agentOutputMapped", expectedSource);
        requireTrue(expected, "agentRequestSurfaced", expectedSource);
        requireTrue(expected, "agentResponseResumed", expectedSource);
        requireTrue(expected, "agentStreamingMapped", expectedSource);
        requireFalse(expected, "crossLanguageWireCompatible", expectedSource);
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
                "normalModelCalls",
                "normalToolInvocations",
                "modelCallsAfterTermination",
                "toolInvocationsAfterTermination",
                "contextIsolated",
                "doubleNextRejected",
                "doubleNextModelCalls",
                "doubleNextToolInvocations",
                "doubleNextError");
        JsonSchemaV1.requireTextArray(expected, "normalOrder", expectedSource, true, false);
        JsonSchemaV1.requireTextArray(expected, "terminatedOrder", expectedSource, true, true);
        requireCountFields(
                expected,
                expectedSource,
                "normalModelCalls",
                "normalToolInvocations",
                "modelCallsAfterTermination",
                "toolInvocationsAfterTermination",
                "doubleNextModelCalls",
                "doubleNextToolInvocations");
        requireBooleanFields(expected, expectedSource, "contextIsolated", "doubleNextRejected");
        JsonSchemaV1.requireText(expected, "doubleNextError", expectedSource);
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

    private static void validateShellToolContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "localDefaults", "dockerDefaults", "environmentProvider");

        JsonNode local = JsonSchemaV1.requireObject(input, "localDefaults", inputSource);
        String localSource = JsonSchemaV1.path(inputSource, "localDefaults");
        JsonSchemaV1.exactObject(local, localSource, "mode", "timeoutSeconds", "maxOutputBytes", "approvalMode");
        requireExpectedText(local, "mode", "persistent", localSource);
        requireExpectedCount(local, "timeoutSeconds", 30, localSource);
        requireExpectedCount(local, "maxOutputBytes", 65536, localSource);
        requireExpectedText(local, "approvalMode", "alwaysRequire", localSource);

        JsonNode docker = JsonSchemaV1.requireObject(input, "dockerDefaults", inputSource);
        String dockerSource = JsonSchemaV1.path(inputSource, "dockerDefaults");
        JsonSchemaV1.exactObject(
                docker,
                dockerSource,
                "network",
                "memoryBytes",
                "pidsLimit",
                "user",
                "readOnlyRoot",
                "capDropAll",
                "noNewPrivileges");
        requireExpectedText(docker, "network", "none", dockerSource);
        if (JsonSchemaV1.requirePositiveInteger(docker, "memoryBytes", dockerSource) != 536870912) {
            throw JsonSchemaV1.invalid(JsonSchemaV1.path(dockerSource, "memoryBytes") + " must be 536870912.");
        }
        requireExpectedCount(docker, "pidsLimit", 256, dockerSource);
        requireExpectedText(docker, "user", "65534:65534", dockerSource);
        requireTrue(docker, "readOnlyRoot", dockerSource);
        requireTrue(docker, "capDropAll", dockerSource);
        requireTrue(docker, "noNewPrivileges", dockerSource);

        JsonNode provider = JsonSchemaV1.requireObject(input, "environmentProvider", inputSource);
        String providerSource = JsonSchemaV1.path(inputSource, "environmentProvider");
        JsonSchemaV1.exactObject(provider, providerSource, "sourceId", "probeTools", "probeTimeoutSeconds");
        requireExpectedText(provider, "sourceId", "shell_environment", providerSource);
        requireExactTextArray(
                provider, "probeTools", List.of("git", "dotnet", "node", "python", "docker"), providerSource);
        requireExpectedCount(provider, "probeTimeoutSeconds", 5, providerSource);

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "toolCapabilities",
                "timeoutExitCode",
                "statelessExitCodePropagated",
                "persistentStatePreserved",
                "headTailOutputBounded",
                "approvalRequiredByDefault",
                "hostOptOutRequiresAcknowledgement",
                "containerOptOutExplicit",
                "cancellationPropagated",
                "policyDenyFirst",
                "commandArgumentsNotRetokenized",
                "environmentInstructionsCached");
        requireExactTextArray(expected, "toolCapabilities", List.of("function", "shell"), expectedSource);
        requireExpectedCount(expected, "timeoutExitCode", 124, expectedSource);
        requireBooleanFields(
                expected,
                expectedSource,
                "statelessExitCodePropagated",
                "persistentStatePreserved",
                "headTailOutputBounded",
                "approvalRequiredByDefault",
                "hostOptOutRequiresAcknowledgement",
                "containerOptOutExplicit",
                "cancellationPropagated",
                "policyDenyFirst",
                "commandArgumentsNotRetokenized",
                "environmentInstructionsCached");
        for (String field : List.of(
                "statelessExitCodePropagated",
                "persistentStatePreserved",
                "headTailOutputBounded",
                "approvalRequiredByDefault",
                "hostOptOutRequiresAcknowledgement",
                "containerOptOutExplicit",
                "cancellationPropagated",
                "policyDenyFirst",
                "commandArgumentsNotRetokenized",
                "environmentInstructionsCached")) {
            requireTrue(expected, field, expectedSource);
        }
    }

    private static void validateSkillsProviderContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(
                input, inputSource, "skillNames", "providerToolNames", "approvalMode", "resourceName", "scriptName");
        requireExactTextArray(input, "skillNames", List.of("zeta", "alpha", "alpha"), inputSource);
        requireExactTextArray(
                input,
                "providerToolNames",
                List.of("load_skill", "read_skill_resource", "run_skill_script"),
                inputSource);
        requireExpectedText(input, "approvalMode", "alwaysRequire", inputSource);
        requireExpectedText(input, "resourceName", "guide", inputSource);
        requireExpectedText(input, "scriptName", "echo", inputSource);

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "advertisedSkillNames",
                "providerToolNames",
                "duplicateNamesRemoved",
                "descriptionsXmlEscaped",
                "loadReturnsInstructions",
                "resourceLookupCaseInsensitive",
                "scriptLookupCaseInsensitive",
                "approvalRequiredByDefault",
                "callerSourceAutoCached");
        requireExactTextArray(expected, "advertisedSkillNames", List.of("alpha", "zeta"), expectedSource);
        requireExactTextArray(
                expected,
                "providerToolNames",
                List.of("load_skill", "read_skill_resource", "run_skill_script"),
                expectedSource);
        requireBooleanFields(
                expected,
                expectedSource,
                "duplicateNamesRemoved",
                "descriptionsXmlEscaped",
                "loadReturnsInstructions",
                "resourceLookupCaseInsensitive",
                "scriptLookupCaseInsensitive",
                "approvalRequiredByDefault",
                "callerSourceAutoCached");
        for (String field : List.of(
                "duplicateNamesRemoved",
                "descriptionsXmlEscaped",
                "loadReturnsInstructions",
                "resourceLookupCaseInsensitive",
                "scriptLookupCaseInsensitive",
                "approvalRequiredByDefault")) {
            requireTrue(expected, field, expectedSource);
        }
        requireFalse(expected, "callerSourceAutoCached", expectedSource);
    }

    private static void validateHarnessAgentContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        List<String> defaultProviders = List.of("history", "todo", "mode", "fileMemory");
        List<String> optInProviders = List.of("history", "fileAccess", "backgroundAgents");
        List<String> stream = List.of("iteration-1", "continue-2", "iteration-2", "continue-3", "iteration-3");
        JsonSchemaV1.exactObject(
                input,
                inputSource,
                "defaultProviderOrder",
                "optInProviderOrder",
                "maxIterations",
                "streamSequence",
                "evaluatorOrder");
        requireExactTextArray(input, "defaultProviderOrder", defaultProviders, inputSource);
        requireExactTextArray(input, "optInProviderOrder", optInProviders, inputSource);
        requireExpectedCount(input, "maxIterations", 3, inputSource);
        requireExactTextArray(input, "streamSequence", stream, inputSource);
        requireExactTextArray(input, "evaluatorOrder", List.of("primary", "secondary"), inputSource);

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "defaultProviderOrder",
                "optInProviderOrder",
                "loopInvocationCount",
                "streamSequence",
                "firstContinuingEvaluatorWins",
                "finiteFinalOnlyMessageCount",
                "streamingIgnoresFiniteFinalOnly",
                "judgeStructuredOutputRequested",
                "judgeMoreMarkerWins");
        requireExactTextArray(expected, "defaultProviderOrder", defaultProviders, expectedSource);
        requireExactTextArray(expected, "optInProviderOrder", optInProviders, expectedSource);
        requireExpectedCount(expected, "loopInvocationCount", 3, expectedSource);
        requireExactTextArray(expected, "streamSequence", stream, expectedSource);
        requireExpectedCount(expected, "finiteFinalOnlyMessageCount", 1, expectedSource);
        for (String field : List.of(
                "firstContinuingEvaluatorWins",
                "streamingIgnoresFiniteFinalOnly",
                "judgeStructuredOutputRequested",
                "judgeMoreMarkerWins")) {
            requireTrue(expected, field, expectedSource);
        }
    }

    private static void validateBackgroundAgentsContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(
                input,
                inputSource,
                "agentName",
                "operations",
                "initialStatus",
                "terminalStatus",
                "restoredMissingRuntimeStatus");
        requireExpectedText(input, "agentName", "researcher", inputSource);
        requireExactTextArray(
                input, "operations", List.of("start", "wait", "result", "continue", "clear"), inputSource);
        requireExpectedText(input, "initialStatus", "RUNNING", inputSource);
        requireExpectedText(input, "terminalStatus", "COMPLETED", inputSource);
        requireExpectedText(input, "restoredMissingRuntimeStatus", "LOST", inputSource);

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "taskIdsMonotonic",
                "waitReturnsCompletedTask",
                "firstResult",
                "continuedResult",
                "terminalTaskClearable",
                "restoredRunningBecomesLost",
                "lostTaskExcludedFromIncomplete");
        requireExpectedText(expected, "firstResult", "first-done", expectedSource);
        requireExpectedText(expected, "continuedResult", "second-done", expectedSource);
        for (String field : List.of(
                "taskIdsMonotonic",
                "waitReturnsCompletedTask",
                "terminalTaskClearable",
                "restoredRunningBecomesLost",
                "lostTaskExcludedFromIncomplete")) {
            requireTrue(expected, field, expectedSource);
        }
    }

    private static void validateHarnessWorkspaceContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(
                input,
                inputSource,
                "sharedApprovalMode",
                "memoryFile",
                "nestedMemoryFile",
                "defaultMode",
                "nextMode",
                "todoTitles");
        requireExpectedText(input, "sharedApprovalMode", "alwaysRequire", inputSource);
        requireExpectedText(input, "memoryFile", "plan.md", inputSource);
        requireExpectedText(input, "nestedMemoryFile", "nested/secret.md", inputSource);
        requireExpectedText(input, "defaultMode", "plan", inputSource);
        requireExpectedText(input, "nextMode", "execute", inputSource);
        requireExactTextArray(input, "todoTitles", List.of("first", "second"), inputSource);

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "fileAccessApprovalRequired",
                "fileAccessEditResult",
                "memorySessionIsolated",
                "memoryIndexDescribesVisibleFiles",
                "nestedMemoryRejected",
                "todoIdsUnique",
                "completedTodoExcludedFromRemaining",
                "normalizedMode",
                "externalModeNotificationOneShot",
                "filesystemTraversalRejected",
                "filesystemSymlinkRejected");
        requireExpectedText(expected, "fileAccessEditResult", "alpha\nBETA", expectedSource);
        requireExpectedText(expected, "normalizedMode", "execute", expectedSource);
        for (String field : List.of(
                "fileAccessApprovalRequired",
                "memorySessionIsolated",
                "memoryIndexDescribesVisibleFiles",
                "nestedMemoryRejected",
                "todoIdsUnique",
                "completedTodoExcludedFromRemaining",
                "externalModeNotificationOneShot",
                "filesystemTraversalRejected",
                "filesystemSymlinkRejected")) {
            requireTrue(expected, field, expectedSource);
        }
    }

    private static void validateSkillTypesContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(
                input,
                inputSource,
                "directoryName",
                "frontmatterName",
                "resourcePath",
                "scriptPath",
                "searchDepth",
                "resourceExtensions",
                "scriptExtensions");
        requireExpectedText(input, "directoryName", "file-skill", inputSource);
        requireExpectedText(input, "frontmatterName", "file-skill", inputSource);
        requireExpectedText(input, "resourcePath", "references/guide.md", inputSource);
        requireExpectedText(input, "scriptPath", "scripts/run.py", inputSource);
        requireExpectedCount(input, "searchDepth", 2, inputSource);
        requireExactTextArray(
                input,
                "resourceExtensions",
                List.of(".md", ".json", ".yaml", ".yml", ".csv", ".xml", ".txt"),
                inputSource);
        requireExactTextArray(input, "scriptExtensions", List.of(".py"), inputSource);

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "inlineContentDeterministic",
                "classSnapshotImmutable",
                "fileSkillDiscovered",
                "directoryNameRequired",
                "resourceReadable",
                "scriptRunnable",
                "lookupCaseInsensitive",
                "traversalRejected",
                "symlinksSkipped");
        requireAllTrue(expected, expectedSource);
    }

    private static void validateMcpSkillsContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(
                input,
                inputSource,
                "indexUri",
                "supportedEntryTypes",
                "deferredEntryTypes",
                "archiveMaxFileCount",
                "archiveMaxSizeBytes",
                "archiveMaxUncompressedBytes",
                "resourcePath");
        requireExpectedText(input, "indexUri", "skill://index.json", inputSource);
        requireExactTextArray(input, "supportedEntryTypes", List.of("skill-md", "archive"), inputSource);
        requireExactTextArray(input, "deferredEntryTypes", List.of("mcp-resource-template"), inputSource);
        requireExpectedCount(input, "archiveMaxFileCount", 20, inputSource);
        requireExpectedCount(input, "archiveMaxSizeBytes", 1048576, inputSource);
        requireExpectedCount(input, "archiveMaxUncompressedBytes", 1048576, inputSource);
        requireExpectedText(input, "resourcePath", "references/guide.md", inputSource);

        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "skillDocumentLazy",
                "skillDocumentCachedAfterSuccess",
                "missingIndexReturnsEmpty",
                "malformedIndexReturnsEmpty",
                "realReadErrorsPropagate",
                "unknownResourceReturnsNull",
                "resourceTraversalRejectedLocally",
                "binaryResourcesPreserved",
                "archiveMagicBytesPreferred",
                "archiveTraversalContained",
                "archiveLinksSkipped",
                "archiveLimitsEnforced",
                "staleArchivesPruned",
                "concurrentReconciliationSerialized",
                "archiveScriptsNeverExecutable",
                "templateEntriesDeferred");
        requireAllTrue(expected, expectedSource);
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

    private static void validateExternalProviderContract(
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
                "finiteResponseMapped",
                "streamingUpdatesAggregate",
                "fragmentedToolCallsAssembled",
                "usageFinishIdentifiersMapped",
                "typedErrorsPreserved",
                "cancellationPropagated",
                "providerTypesInSharedApi",
                "capabilities",
                "limitations");
        JsonSchemaV1.requireTextArray(expected, "requestRoleOrder", expectedSource, true, false);
        JsonSchemaV1.requireTextArray(expected, "toolNames", expectedSource, true, true);
        requireBooleanFields(
                expected,
                expectedSource,
                "finiteResponseMapped",
                "streamingUpdatesAggregate",
                "fragmentedToolCallsAssembled",
                "usageFinishIdentifiersMapped",
                "typedErrorsPreserved",
                "cancellationPropagated",
                "providerTypesInSharedApi");
        requireFalse(expected, "providerTypesInSharedApi", expectedSource);
        JsonNode capabilities = requiredNonEmptyObject(expected, "capabilities", expectedSource);
        capabilities.properties().forEach(entry -> {
            if (!entry.getValue().isBoolean()) {
                throw JsonSchemaV1.invalid(
                        JsonSchemaV1.path(JsonSchemaV1.path(expectedSource, "capabilities"), entry.getKey())
                                + " must be a Boolean.");
            }
        });
        JsonSchemaV1.requireTextArray(expected, "limitations", expectedSource, false, true);
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

    private static void validateMcpClientContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(
                input, inputSource, "transports", "remoteToolNames", "contentKinds", "cursor", "progressToken");
        JsonSchemaV1.requireTextArray(input, "transports", inputSource, true, true);
        JsonSchemaV1.requireTextArray(input, "remoteToolNames", inputSource, true, true);
        JsonSchemaV1.requireTextArray(input, "contentKinds", inputSource, true, true);
        JsonSchemaV1.requireText(input, "cursor", inputSource);
        JsonSchemaV1.requireText(input, "progressToken", inputSource);
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "sdkTypesInPublicApi",
                "normalizedToolNamesUnique",
                "exactRemoteNamesUsedForDispatch",
                "structuredContentPreserved",
                "cursorTraversalBounded",
                "childProcessOwnedAndClosed",
                "remoteToolApprovalRequiredByDefault");
        requireBooleanFields(
                expected,
                expectedSource,
                "sdkTypesInPublicApi",
                "normalizedToolNamesUnique",
                "exactRemoteNamesUsedForDispatch",
                "structuredContentPreserved",
                "cursorTraversalBounded",
                "childProcessOwnedAndClosed",
                "remoteToolApprovalRequiredByDefault");
        requireFalse(expected, "sdkTypesInPublicApi", expectedSource);
        requireTrue(expected, "normalizedToolNamesUnique", expectedSource);
        requireTrue(expected, "exactRemoteNamesUsedForDispatch", expectedSource);
        requireTrue(expected, "structuredContentPreserved", expectedSource);
        requireTrue(expected, "cursorTraversalBounded", expectedSource);
        requireTrue(expected, "childProcessOwnedAndClosed", expectedSource);
        requireTrue(expected, "remoteToolApprovalRequiredByDefault", expectedSource);
    }

    private static void validateMcpHostingContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "transports", "primitives", "approvalMode", "agentOutcome");
        JsonSchemaV1.requireTextArray(input, "transports", inputSource, true, true);
        JsonSchemaV1.requireTextArray(input, "primitives", inputSource, true, true);
        JsonSchemaV1.requireText(input, "approvalMode", inputSource);
        JsonSchemaV1.requireText(input, "agentOutcome", inputSource);
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "officialSdkServer",
                "schemaValidationEnabled",
                "approvalReturnedAsError",
                "inputRequiredReturnedAsError",
                "toolResultsTerminal",
                "crossProcessResumeClaimed",
                "payloadAndConcurrencyBounded",
                "remoteHttpRequiresTlsBoundary");
        requireBooleanFields(
                expected,
                expectedSource,
                "officialSdkServer",
                "schemaValidationEnabled",
                "approvalReturnedAsError",
                "inputRequiredReturnedAsError",
                "toolResultsTerminal",
                "crossProcessResumeClaimed",
                "payloadAndConcurrencyBounded",
                "remoteHttpRequiresTlsBoundary");
        requireTrue(expected, "officialSdkServer", expectedSource);
        requireTrue(expected, "schemaValidationEnabled", expectedSource);
        requireTrue(expected, "approvalReturnedAsError", expectedSource);
        requireTrue(expected, "inputRequiredReturnedAsError", expectedSource);
        requireTrue(expected, "toolResultsTerminal", expectedSource);
        requireFalse(expected, "crossProcessResumeClaimed", expectedSource);
        requireTrue(expected, "payloadAndConcurrencyBounded", expectedSource);
        requireTrue(expected, "remoteHttpRequiresTlsBoundary", expectedSource);
    }

    private static void validateOrchestrationContract(
            JsonNode input, JsonNode expected, String inputSource, String expectedSource) {
        JsonSchemaV1.exactObject(input, inputSource, "patterns", "resumeBoundary");
        JsonSchemaV1.requireTextArray(input, "patterns", inputSource, true, true);
        JsonSchemaV1.requireText(input, "resumeBoundary", inputSource);
        JsonSchemaV1.exactObject(
                expected,
                expectedSource,
                "deterministicEvents",
                "boundedStreaming",
                "cancellationPropagated",
                "inputRequiredExplicit",
                "crossProcessResumeClaimed");
        requireBooleanFields(
                expected,
                expectedSource,
                "deterministicEvents",
                "boundedStreaming",
                "cancellationPropagated",
                "inputRequiredExplicit",
                "crossProcessResumeClaimed");
        requireTrue(expected, "deterministicEvents", expectedSource);
        requireTrue(expected, "boundedStreaming", expectedSource);
        requireTrue(expected, "cancellationPropagated", expectedSource);
        requireTrue(expected, "inputRequiredExplicit", expectedSource);
        requireFalse(expected, "crossProcessResumeClaimed", expectedSource);
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
