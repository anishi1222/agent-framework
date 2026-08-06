// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class FixtureSchemaV1Test {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CORE_MESSAGE = "conformance/v1/core/jcf-core-001-message-content.json";

    private static final String CORE_RESPONSE = "conformance/v1/core/jcf-core-002-response-aggregation.json";

    private static final String CORE_CONTRACT = "conformance/v1/core/jcf-core-003-run-options.json";

    private static final String RUN_SIGNAL = "conformance/v1/core/jcf-core-005-cancellation-terminal.json";

    private static final String SESSION = "conformance/v1/sessions/jcf-sessions-001-java-v1-envelope.json";

    private static final String TOOL_NORMAL = "conformance/v1/tools/jcf-tools-002-normal-call.json";

    private static final String TOOL_MULTIPLE = "conformance/v1/tools/jcf-tools-003-multiple-calls.json";

    private static final String TOOL_APPROVAL = "conformance/v1/tools/jcf-tools-007-approval-resume.json";

    private static final String TOOL_IN_FLIGHT = "conformance/v1/tools/jcf-tools-009-in-flight-duplicate.json";

    private static final String TOOL_DUPLICATE_APPROVAL =
            "conformance/v1/tools/jcf-tools-010-duplicate-pending-approval.json";

    private static final String TOOL_REJECTED = "conformance/v1/tools/jcf-tools-011-approval-rejected.json";

    private static final String TOOL_MIXED_APPROVAL = "conformance/v1/tools/jcf-tools-013-mixed-approval-batch.json";

    private static final String WORKFLOW_CORE = "conformance/v1/workflows/jcf-workflows-001-core-events.json";

    private static final String WORKFLOW_FAN_IN = "conformance/v1/workflows/jcf-workflows-002-fan-out-in.json";

    private static final String WORKFLOW_RESUME = "conformance/v1/workflows/jcf-workflows-004-checkpoint-resume.json";

    private static final String WORKFLOW_CHECKPOINT =
            "conformance/v1/workflows/jcf-workflows-005-checkpoint-envelope.json";

    private final ConformanceFixtureLoader loader = new ConformanceFixtureLoader();

    @Test
    void responseAggregation_shouldSequentiallyFoldIntegralUsageLikePython() throws IOException {
        // Arrange
        BehaviorFixture fixture = (BehaviorFixture) loader.loadDefault().requireCase("JCF-CORE-002");
        ObjectNode authored = readFixture(CORE_RESPONSE);

        // Act
        ConformanceValue.ObjectValue usage =
                (ConformanceValue.ObjectValue) fixture.expected().require("usage");

        // Assert
        assertThat(usage.require("inputTokens")).isEqualTo(number("10"));
        assertThat(usage.require("outputTokens")).isEqualTo(number("2"));
        assertThat(usage.require("nullAndMissing")).isEqualTo(number("0"));
        assertThat(usage.require("missingThenNumber")).isEqualTo(number("4"));
        assertThat(usage.require("reintroduced")).isEqualTo(number("7"));
        assertThat(usage.require("scaledNumber")).isEqualTo(number("9"));
        assertThat(usage.require("largeTokens")).isEqualTo(number("9223372036854775811"));
        assertThat(usage.values())
                .doesNotContainKeys("alwaysDropped", "fractional", "nested")
                .containsOnlyKeys(
                        "inputTokens",
                        "outputTokens",
                        "nullAndMissing",
                        "missingThenNumber",
                        "reintroduced",
                        "scaledNumber",
                        "largeTokens");
        assertThat(authored.path("input")
                        .path("updates")
                        .get(0)
                        .path("usage")
                        .path("scaledNumber")
                        .isFloatingPointNumber())
                .isTrue();
        assertThat(authored.path("expected").path("usage").path("largeTokens").isBigInteger())
                .isTrue();
    }

    @Test
    void crossViewExactlyOnce_shouldShareOneInvocationOwner() {
        // Arrange
        EventHistoryFixture fixture = (EventHistoryFixture) loader.loadDefault().requireCase("JCF-TOOLS-012");

        // Act and assert
        assertThat(fixture.events()).hasSize(4);
        assertThat(fixture.expected().require("sharedInvocationId"))
                .isEqualTo(new ConformanceValue.StringValue("logical-run-012:call-shared-view"));
        assertThat(fixture.expected().require("totalInvocationCount"))
                .isEqualTo(new ConformanceValue.NumberValue(BigDecimal.ONE));
    }

    @Test
    void mixedApprovalBatch_shouldRequireOneResultPerCallAndNoRejectedInvocation() {
        // Arrange
        EventHistoryFixture fixture = (EventHistoryFixture) loader.loadDefault().requireCase("JCF-TOOLS-013");

        // Act and assert
        assertThat(fixture.events()).hasSize(11);
        assertThat(fixture.expected().require("approvedInvocationCount"))
                .isEqualTo(new ConformanceValue.NumberValue(BigDecimal.ONE));
        assertThat(fixture.expected().require("rejectedInvocationCount"))
                .isEqualTo(new ConformanceValue.NumberValue(BigDecimal.ZERO));
        assertThat(fixture.expected().require("functionResultCount"))
                .isEqualTo(new ConformanceValue.NumberValue(BigDecimal.valueOf(2)));
    }

    @Test
    void workflowCheckpointGolden_shouldExposeDeterministicJavaV1EncodingAndResume() throws IOException {
        // Arrange
        ConformanceFixture fixture = loader.loadDefault().requireCase("JCF-WORKFLOWS-005");
        ObjectNode authored = readFixture(WORKFLOW_CHECKPOINT);

        // Act
        WorkflowCheckpointFixture checkpoint = (WorkflowCheckpointFixture) fixture;

        // Assert
        assertThat(authored.path("envelope").fieldNames().next()).isEqualTo("payload");
        assertThat(authored.path("envelope")
                        .path("payload")
                        .path("pendingExecutors")
                        .get(0)
                        .textValue())
                .isEqualTo("right");
        assertThat(authored.path("envelope")
                        .path("payload")
                        .path("bufferedInputs")
                        .get(0)
                        .path("sourceId")
                        .textValue())
                .isEqualTo("middle");
        assertThat(authored.path("envelope")
                        .path("payload")
                        .path("fanInNextEpochs")
                        .path("join")
                        .longValue())
                .isEqualTo(4);
        assertThat(checkpoint.encoded())
                .isEqualTo("{\"documentKind\":\"workflow-checkpoint\",\"format\":\"agent-framework-java-state\","
                        + "\"payload\":{\"bufferedInputs\":[{\"sourceId\":\"left\",\"targetId\":\"join\","
                        + "\"value\":{\"a\":1,\"z\":2}},{\"sourceId\":\"middle\","
                        + "\"targetId\":\"join\",\"value\":\"middle\"},{\"sourceId\":\"early\","
                        + "\"targetId\":\"join0\",\"value\":\"prefixed\"}],"
                        + "\"checkpointId\":\"checkpoint-001\",\"fanInNextEpochs\":{\"join\":4},"
                        + "\"pendingExecutors\":[\"audit\",\"right\"],"
                        + "\"previousCheckpointId\":null,\"revision\":1,\"status\":\"inputRequired\","
                        + "\"workflowId\":\"workflow-checkpoint-001\"},\"payloadVersion\":1}");
        List<String> tupleOrder =
                CheckpointCanonicalizer.orderedBufferedInputs(
                                authored.path("envelope").path("payload").path("bufferedInputs"))
                        .stream()
                        .map(input -> input.path("targetId").textValue() + ":"
                                + input.path("sourceId").textValue())
                        .toList();
        ArrayList<String> joinedStringOrder = new ArrayList<>(tupleOrder);
        joinedStringOrder.sort(String::compareTo);
        assertThat(tupleOrder).containsExactly("join:left", "join:middle", "join0:early");
        assertThat(joinedStringOrder).containsExactly("join0:early", "join:left", "join:middle");
        assertThat(checkpoint.resumeEvents()).hasSize(10);
        assertThat(checkpoint.expected().values()).doesNotContainKey("crossLanguageWireCompatible");
    }

    @TestFactory
    Stream<DynamicTest> nestedSchemaAndSemanticViolations_shouldBeRejected() {
        return invalidCases()
                .map(invalid -> DynamicTest.dynamicTest(invalid.name(), () -> {
                    ObjectNode fixture = readFixture(invalid.resource());
                    invalid.mutation().apply(fixture);

                    assertThatThrownBy(() -> loader.loadFixture(
                                    new ByteArrayInputStream(MAPPER.writeValueAsBytes(fixture)), invalid.name()))
                            .isInstanceOf(ConformanceValidationException.class)
                            .hasMessageContaining(invalid.expectedMessage());
                }));
    }

    private static Stream<InvalidFixture> invalidCases() {
        return Stream.of(
                invalid("message-required-field", CORE_MESSAGE, "missing required field 'text'", root -> content(
                                root, 0, 0)
                        .remove("text")),
                invalid("message-content-type", CORE_MESSAGE, "unknown content kind", root -> content(root, 0, 0)
                        .put("kind", "unknown")),
                invalid("message-content-unknown-field", CORE_MESSAGE, "unknown field 'provider'", root -> content(
                                root, 0, 0)
                        .put("provider", "leak")),
                invalid("message-orphan-result", CORE_MESSAGE, "orphan callId", root -> content(root, 2, 0)
                        .put("callId", "missing")),
                invalid(
                        "message-call-result-pair-coverage",
                        CORE_MESSAGE,
                        "must cover every declared callId exactly once",
                        root -> ((ArrayNode) root.path("expected").path("callResultPairs")).removeAll()),
                invalid("response-sequence-order", CORE_RESPONSE, "must be 1 but was 0", root -> event(
                                root, "input", "updates", 1)
                        .put("sequence", 0)),
                invalid(
                        "response-required-type",
                        CORE_RESPONSE,
                        "usage' must equal sequentially folded integral usage",
                        root -> ((ObjectNode) event(root, "input", "updates", 2).get("usage"))
                                .put("inputTokens", "three")),
                invalid(
                        "response-negative-usage",
                        CORE_RESPONSE,
                        "must be non-negative when numeric",
                        root -> ((ObjectNode) event(root, "input", "updates", 0).get("usage")).put("inputTokens", -1)),
                invalid(
                        "response-terminal-order",
                        CORE_RESPONSE,
                        "finishReason must occur once on the final update",
                        root -> event(root, "input", "updates", 1).put("finishReason", "stop")),
                invalid(
                        "response-aggregate-mismatch",
                        CORE_RESPONSE,
                        "text' must match aggregated updates",
                        root -> ((ObjectNode) root.path("expected")).put("text", "wrong")),
                invalid(
                        "response-usage-sum-mismatch",
                        CORE_RESPONSE,
                        "usage' must equal sequentially folded integral usage",
                        root -> ((ObjectNode) root.path("expected").path("usage")).put("outputTokens", 99)),
                invalid(
                        "contract-nested-type",
                        CORE_CONTRACT,
                        "maxIterations' must be an integer",
                        root -> ((ObjectNode) root.path("input").path("options")).put("maxIterations", "four")),
                invalid(
                        "contract-nested-unknown-field",
                        CORE_CONTRACT,
                        "unknown field 'unversioned'",
                        root -> ((ObjectNode) root.path("input").path("options")).put("unversioned", true)),
                invalid("empty-event-object", TOOL_NORMAL, "must not be an empty event object", root -> events(root)
                        .set(0, MAPPER.createObjectNode())),
                invalid("tool-sequence-duplicate", TOOL_NORMAL, "must be 1 but was 0", root -> ((ObjectNode)
                                events(root).get(1))
                        .put("sequence", 0)),
                invalid("tool-call-id-duplicate", TOOL_MULTIPLE, "duplicate callId", root -> ((ObjectNode)
                                events(root).get(1))
                        .put("callId", "call-a")),
                invalid(
                        "tool-invocation-id-duplicate",
                        TOOL_IN_FLIGHT,
                        "duplicate invocationId",
                        FixtureSchemaV1Test::insertDuplicateInvocation),
                invalid(
                        "tool-result-before-invocation",
                        TOOL_NORMAL,
                        "result is not correlated to an active invocation",
                        FixtureSchemaV1Test::removeToolInvocation),
                invalid(
                        "tool-expected-invocation-count-mismatch",
                        TOOL_NORMAL,
                        "invocationCount' must match derived value 1",
                        root -> ((ObjectNode) root.path("expected")).put("invocationCount", 2)),
                invalid("tool-orphan-result", TOOL_NORMAL, "orphan callId", root -> ((ObjectNode)
                                events(root).get(3))
                        .put("callId", "missing")),
                invalid("tool-orphan-approval", TOOL_APPROVAL, "orphan approvalId", root -> ((ObjectNode)
                                events(root).get(3))
                        .put("approvalId", "missing")),
                invalid(
                        "tool-invocation-before-approval",
                        TOOL_APPROVAL,
                        "before an affirmative approval decision",
                        root -> ((ObjectNode) events(root).get(3)).put("approved", false)),
                invalid(
                        "tool-duplicate-primary-approval-decision",
                        TOOL_DUPLICATE_APPROVAL,
                        "duplicate primary approval decision",
                        root -> ((ObjectNode) events(root).get(4)).put("type", "approvalDecision")),
                invalid(
                        "tool-duplicate-approval-id",
                        TOOL_DUPLICATE_APPROVAL,
                        "duplicate approvalId",
                        FixtureSchemaV1Test::insertDuplicateApproval),
                invalid(
                        "tool-executes-after-rejection",
                        TOOL_REJECTED,
                        "before an affirmative approval decision",
                        FixtureSchemaV1Test::replaceRejectionMessageWithInvocation),
                invalid(
                        "tool-rejected-result-missing",
                        TOOL_REJECTED,
                        "completes successfully with unresolved call",
                        FixtureSchemaV1Test::removeRejectedResult),
                invalid(
                        "tool-rejected-result-wrong-outcome",
                        TOOL_REJECTED,
                        "must use function-result outcome 'rejected'",
                        root -> ((ObjectNode) events(root).get(4)).put("outcome", "succeeded")),
                invalid(
                        "tool-mixed-rejected-invocation-count",
                        TOOL_MIXED_APPROVAL,
                        "rejectedInvocationCount' must match derived value 0",
                        root -> ((ObjectNode) root.path("expected")).put("rejectedInvocationCount", 1)),
                invalid("tool-unknown-event-type", TOOL_NORMAL, "unknown tool-loop event type", root -> ((ObjectNode)
                                events(root).get(0))
                        .put("type", "providerEvent")),
                invalid("tool-unknown-event-field", TOOL_NORMAL, "unknown field 'provider'", root -> ((ObjectNode)
                                events(root).get(0))
                        .put("provider", "leak")),
                invalid(
                        "tool-event-after-terminal",
                        TOOL_NORMAL,
                        "appears after the lane's final terminal event",
                        FixtureSchemaV1Test::appendToolEventAfterTerminal),
                invalid(
                        "tool-missing-final-terminal",
                        TOOL_NORMAL,
                        "must contain one final terminal event",
                        root -> events(root).remove(events(root).size() - 1)),
                invalid(
                        "tool-unrejected-duplicate",
                        TOOL_IN_FLIGHT,
                        "without rejection",
                        FixtureSchemaV1Test::removeDuplicateCallRejection),
                invalid(
                        "tool-duplicate-before-invocation",
                        TOOL_IN_FLIGHT,
                        "is not an in-flight duplicate call",
                        FixtureSchemaV1Test::moveDuplicateBeforeInvocation),
                invalid("run-signal-sequence", RUN_SIGNAL, "must be 2 but was 1", root -> ((ObjectNode)
                                events(root).get(2))
                        .put("sequence", 1)),
                invalid("run-signal-demand-type", RUN_SIGNAL, "count' must be positive", root -> ((ObjectNode)
                                events(root).get(1))
                        .put("count", 0)),
                invalid(
                        "run-signal-update-after-cancel",
                        RUN_SIGNAL,
                        "must not update after cancellation",
                        FixtureSchemaV1Test::replaceSecondCancellationWithUpdate),
                invalid(
                        "run-signal-terminal-outcome",
                        RUN_SIGNAL,
                        "must be cancelled after cancellation was requested",
                        root -> ((ObjectNode) events(root).get(5)).put("outcome", "success")),
                invalid(
                        "run-signal-expected-terminal-mismatch",
                        RUN_SIGNAL,
                        "terminalOutcome' must match derived value 'cancelled'",
                        root -> ((ObjectNode) root.path("expected")).put("terminalOutcome", "success")),
                invalid(
                        "session-wrong-document-kind",
                        SESSION,
                        "documentKind' must be 'agent-session'",
                        root -> envelope(root).put("documentKind", "workflow-checkpoint")),
                invalid("session-unsupported-payload-version", SESSION, "unsupported value 2", root -> envelope(root)
                        .put("payloadVersion", 2)),
                invalid("session-envelope-unknown-field", SESSION, "unknown field 'className'", root -> envelope(root)
                        .put("className", "example.Session")),
                invalid("session-operation-type", SESSION, "unknown session operation", root -> ((ObjectNode)
                                root.path("operations").get(0))
                        .put("operation", "deserialize")),
                invalid(
                        "session-expected-id-mismatch",
                        SESSION,
                        "observableSessionId' must match derived value 'session-001'",
                        root -> ((ObjectNode) root.path("expected")).put("observableSessionId", "session-wrong")),
                invalid("workflow-sequence-duplicate", WORKFLOW_CORE, "must be 2 but was 1", root -> ((ObjectNode)
                                events(root).get(2))
                        .put("sequence", 1)),
                invalid(
                        "workflow-orphan-executor-completion",
                        WORKFLOW_CORE,
                        "inactive executorId",
                        root -> ((ObjectNode) events(root).get(2)).put("executorId", "missing")),
                invalid("workflow-duplicate-executor-id", WORKFLOW_FAN_IN, "duplicate executorId", root -> ((ObjectNode)
                                events(root).get(6))
                        .put("executorId", "left")),
                invalid(
                        "workflow-fan-in-correlation",
                        WORKFLOW_FAN_IN,
                        "fan-in sources do not match buffered order",
                        FixtureSchemaV1Test::reverseFanInSources),
                invalid(
                        "workflow-expected-fan-in-count-mismatch",
                        WORKFLOW_FAN_IN,
                        "fanInReleaseCount' must match derived value 1",
                        root -> ((ObjectNode) root.path("expected")).put("fanInReleaseCount", 2)),
                invalid(
                        "workflow-checkpoint-id-duplicate",
                        WORKFLOW_RESUME,
                        "duplicate checkpointId",
                        root -> ((ObjectNode) events(root).get(7)).put("checkpointId", "checkpoint-001")),
                invalid(
                        "workflow-checkpoint-load-correlation",
                        WORKFLOW_RESUME,
                        "loads unknown checkpoint or revision",
                        root -> ((ObjectNode) events(root).get(3)).put("checkpointId", "checkpoint-missing")),
                invalid(
                        "workflow-unknown-event-type",
                        WORKFLOW_CORE,
                        "unknown workflow event type",
                        root -> ((ObjectNode) events(root).get(1)).put("type", "executorStarted")),
                invalid("workflow-unknown-event-field", WORKFLOW_CORE, "unknown field 'thread'", root -> ((ObjectNode)
                                events(root).get(1))
                        .put("thread", "worker")),
                invalid(
                        "workflow-terminal-not-last",
                        WORKFLOW_CORE,
                        "terminal event must be last",
                        FixtureSchemaV1Test::appendWorkflowEventAfterTerminal),
                invalid(
                        "checkpoint-wrong-document-kind",
                        WORKFLOW_CHECKPOINT,
                        "documentKind' must be 'workflow-checkpoint'",
                        root -> envelope(root).put("documentKind", "agent-session")),
                invalid(
                        "checkpoint-unsupported-payload-version",
                        WORKFLOW_CHECKPOINT,
                        "unsupported value 2",
                        root -> envelope(root).put("payloadVersion", 2)),
                invalid(
                        "checkpoint-nondeterministic-encoding",
                        WORKFLOW_CHECKPOINT,
                        "must exactly match canonical envelope encoding",
                        root -> root.put("encoded", "{}")),
                invalid(
                        "checkpoint-resume-load-correlation",
                        WORKFLOW_CHECKPOINT,
                        "loads unknown checkpoint or revision",
                        root -> ((ObjectNode) root.path("resumeEvents").get(0))
                                .put("checkpointId", "checkpoint-missing")),
                invalid(
                        "checkpoint-resume-buffer-order",
                        WORKFLOW_CHECKPOINT,
                        "fan-in sources do not match buffered order",
                        FixtureSchemaV1Test::reverseCheckpointFanInSources),
                invalid(
                        "checkpoint-expected-fan-in-mismatch",
                        WORKFLOW_CHECKPOINT,
                        "resumeFanInValues' must match event-derived value",
                        root -> ((ArrayNode) root.path("expected").path("resumeFanInValues")).remove(0)));
    }

    private static InvalidFixture invalid(String name, String resource, String expectedMessage, JsonMutation mutation) {
        return new InvalidFixture(name, resource, expectedMessage, mutation);
    }

    private static ObjectNode readFixture(String resource) throws IOException {
        ClassLoader loader = FixtureSchemaV1Test.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing test fixture " + resource);
            }
            return (ObjectNode) MAPPER.readTree(input);
        }
    }

    private static ObjectNode content(ObjectNode root, int messageIndex, int contentIndex) {
        return (ObjectNode) root.path("input")
                .path("messages")
                .get(messageIndex)
                .path("contents")
                .get(contentIndex);
    }

    private static ObjectNode event(ObjectNode root, String objectField, String arrayField, int index) {
        return (ObjectNode) root.path(objectField).path(arrayField).get(index);
    }

    private static ArrayNode events(ObjectNode root) {
        return (ArrayNode) root.get("events");
    }

    private static ObjectNode envelope(ObjectNode root) {
        return (ObjectNode) root.get("envelope");
    }

    private static void insertDuplicateInvocation(ObjectNode root) {
        ObjectNode duplicate = MAPPER.createObjectNode();
        duplicate.put("sequence", 1);
        duplicate.put("type", "functionCall");
        duplicate.put("callId", "call-other");
        duplicate.put("invocationId", "run-009:call-in-flight");
        events(root).insert(1, duplicate);
        resequence(events(root));
    }

    private static void insertDuplicateApproval(ObjectNode root) {
        ObjectNode secondCall = MAPPER.createObjectNode();
        secondCall.put("sequence", 1);
        secondCall.put("type", "functionCall");
        secondCall.put("callId", "call-second");
        events(root).insert(1, secondCall);

        ObjectNode duplicateApproval = MAPPER.createObjectNode();
        duplicateApproval.put("sequence", 3);
        duplicateApproval.put("type", "approvalRequested");
        duplicateApproval.put("approvalId", "approval-decision");
        duplicateApproval.put("callId", "call-second");
        events(root).insert(3, duplicateApproval);
        resequence(events(root));
    }

    private static void replaceRejectionMessageWithInvocation(ObjectNode root) {
        ObjectNode invocation = MAPPER.createObjectNode();
        invocation.put("sequence", 4);
        invocation.put("type", "toolInvoked");
        invocation.put("callId", "call-rejected");
        events(root).set(4, invocation);
    }

    private static void removeRejectedResult(ObjectNode root) {
        events(root).remove(4);
        resequence(events(root));
    }

    private static void appendToolEventAfterTerminal(ObjectNode root) {
        ObjectNode afterTerminal = MAPPER.createObjectNode();
        afterTerminal.put("sequence", events(root).size());
        afterTerminal.put("type", "assistantMessage");
        afterTerminal.put("text", "late");
        events(root).add(afterTerminal);
    }

    private static void removeDuplicateCallRejection(ObjectNode root) {
        events(root).remove(3);
        resequence(events(root));
    }

    private static void removeToolInvocation(ObjectNode root) {
        events(root).remove(2);
        resequence(events(root));
    }

    private static void moveDuplicateBeforeInvocation(ObjectNode root) {
        JsonNode duplicate = events(root).remove(2);
        events(root).insert(1, duplicate);
        resequence(events(root));
    }

    private static void replaceSecondCancellationWithUpdate(ObjectNode root) {
        ObjectNode update = MAPPER.createObjectNode();
        update.put("sequence", 4);
        update.put("type", "update");
        update.put("value", "late");
        events(root).set(4, update);
    }

    private static void reverseFanInSources(ObjectNode root) {
        ArrayNode sources = MAPPER.createArrayNode();
        sources.add("right");
        sources.add("left");
        ((ObjectNode) events(root).get(9)).set("sourceIds", sources);
    }

    private static void appendWorkflowEventAfterTerminal(ObjectNode root) {
        ObjectNode lateOutput = MAPPER.createObjectNode();
        lateOutput.put("sequence", events(root).size());
        lateOutput.put("type", "workflowOutput");
        lateOutput.put("value", "late");
        events(root).add(lateOutput);
    }

    private static void reverseCheckpointFanInSources(ObjectNode root) {
        ArrayNode sources = MAPPER.createArrayNode();
        sources.add("right");
        sources.add("left");
        ((ObjectNode) root.path("resumeEvents").get(7)).set("sourceIds", sources);
    }

    private static ConformanceValue.NumberValue number(String value) {
        return new ConformanceValue.NumberValue(new BigDecimal(value));
    }

    private static void resequence(ArrayNode events) {
        for (int index = 0; index < events.size(); index++) {
            ((ObjectNode) events.get(index)).put("sequence", index);
        }
    }

    private record InvalidFixture(String name, String resource, String expectedMessage, JsonMutation mutation) {}

    @FunctionalInterface
    private interface JsonMutation {
        void apply(ObjectNode root);
    }
}
