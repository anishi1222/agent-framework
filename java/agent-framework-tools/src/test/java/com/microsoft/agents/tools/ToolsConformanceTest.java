// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.conformance.BehaviorFixture;
import com.microsoft.agents.conformance.ConformanceFixtureCatalog;
import com.microsoft.agents.conformance.ConformanceFixtureLoader;
import com.microsoft.agents.conformance.ConformanceValue;
import com.microsoft.agents.conformance.EventHistoryFixture;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToolsConformanceTest {
    private final ConformanceFixtureCatalog catalog = new ConformanceFixtureLoader().loadDefault();

    @Test
    void jcfTools001_shouldBindCapabilitiesModeAndNormalizationToProductionContracts() {
        // Arrange
        BehaviorFixture fixture = behavior("JCF-TOOLS-001");
        List<FunctionTool> tools = array(fixture.input().require("tools")).values().stream()
                .map(ToolsConformanceTest::object)
                .map(tool -> fixtureTool(
                        string(tool.require("name")),
                        ToolApprovalMode.fromValue(string(tool.require("approvalMode"))),
                        StateValue.string("unused"),
                        new AtomicInteger()))
                .toList();

        // Act
        List<Tool> normalized = FunctionTools.normalize(tools);
        ToolMode mode = ToolMode.fromValue(string(fixture.input().require("toolMode")));

        // Assert
        assertThat(normalized)
                .extracting(Tool::name)
                .containsExactlyElementsOf(strings(array(fixture.expected().require("normalizedNames"))));
        assertThat(mode.value()).isEqualTo(string(fixture.expected().require("toolMode")));
        assertThatThrownBy(() -> FunctionTools.normalize(List.of(
                        tools.getFirst(),
                        fixtureTool(
                                tools.getFirst().name(),
                                ToolApprovalMode.NEVER_REQUIRE,
                                StateValue.string("duplicate"),
                                new AtomicInteger()))))
                .isInstanceOf(ToolBindingException.class);
        assertThat(normalized.stream()
                        .flatMap(tool -> tool.capabilities().stream())
                        .map(ToolCapability::value))
                .containsOnly("function");
        assertThat(bool(fixture.expected().require("providerTypesExposed"))).isFalse();
    }

    @Test
    void jcfTools002_shouldBindNormalCallToProductionLoop() {
        // Arrange
        EventHistoryFixture fixture = events("JCF-TOOLS-002");
        ConformanceValue.ObjectValue callEvent = event(fixture, "functionCall");
        StateValue resultValue = state(event(fixture, "functionResult").require("result"));
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = fixtureTool(
                textOr(callEvent, "name", "lookup"), ToolApprovalMode.NEVER_REQUIRE, resultValue, invocations);
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(call(callEvent, tool.name())))
                .enqueue(response(Message.text(
                        Role.ASSISTANT,
                        string(event(fixture, "assistantMessage").require("text")))));

        // Act
        FunctionLoopResult result = run("run-001", source, List.of(tool));

        // Assert
        assertThat(invocations).hasValue(integer(fixture.expected().require("invocationCount")));
        assertThat(functionResults(result)).hasSize(integer(fixture.expected().require("functionResultCount")));
        assertThat(functionResults(result))
                .extracting(FunctionResultContent::callId)
                .containsExactlyElementsOf(strings(array(fixture.expected().require("callResultOrder"))));
        assertThat(result.assistantText()).endsWith(string(fixture.expected().require("assistantText")));
    }

    @Test
    void jcfTools003_shouldBindMultipleCallsToOrderedConcurrentProductionResults() {
        // Arrange
        EventHistoryFixture fixture = events("JCF-TOOLS-003");
        List<ConformanceValue.ObjectValue> callEvents = eventsOf(fixture, "functionCall");
        Map<String, StateValue> results = resultValues(fixture);
        List<AtomicInteger> counts =
                callEvents.stream().map(ignored -> new AtomicInteger()).toList();
        List<FunctionTool> tools = new ArrayList<>();
        for (int index = 0; index < callEvents.size(); index++) {
            ConformanceValue.ObjectValue event = callEvents.get(index);
            tools.add(fixtureTool(
                    string(event.require("name")),
                    ToolApprovalMode.NEVER_REQUIRE,
                    results.get(string(event.require("callId"))),
                    counts.get(index)));
        }
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(new Message(
                        Role.ASSISTANT,
                        callEvents.stream()
                                .map(event -> call(event, string(event.require("name"))))
                                .toList())))
                .enqueue(emptyResponse());

        // Act
        FunctionLoopResult result = run("run-003", source, tools);

        // Assert
        assertThat(counts).extracting(AtomicInteger::get).containsOnly(1);
        assertThat(functionResults(result))
                .extracting(FunctionResultContent::callId)
                .containsExactlyElementsOf(strings(array(fixture.expected().require("resultOrder"))));
        assertThat(bool(fixture.expected().require("parallelExecutionPermitted")))
                .isTrue();
    }

    @Test
    void jcfTools004_shouldRejectCompletedReplayWithinUninterruptedProductionRun() {
        // Arrange
        EventHistoryFixture fixture = events("JCF-TOOLS-004");
        ConformanceValue.ObjectValue callEvent = event(fixture, "functionCall");
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = fixtureTool(
                "write",
                ToolApprovalMode.NEVER_REQUIRE,
                state(event(fixture, "functionResult").require("result")),
                invocations);
        FunctionCallContent call = call(callEvent, tool.name());
        ScriptedToolTurnSource source =
                new ScriptedToolTurnSource().enqueue(response(call)).enqueue(response(call));

        // Act
        FunctionLoopResult result = run(string(fixture.expected().require("logicalRunId")), source, List.of(tool));

        // Assert
        assertThat(invocations).hasValue(integer(fixture.expected().require("invocationCount")));
        assertThat(functionResults(result)).hasSize(integer(fixture.expected().require("functionResultCount")));
        assertThat(result.history().stream()
                        .flatMap(message -> message.contents().stream())
                        .filter(FunctionCallContent.class::isInstance))
                .hasSize(1);
        assertThat(bool(fixture.expected().require("externalExactlyOnceAfterCrashClaimed")))
                .isFalse();
    }

    @Test
    void jcfTools005_shouldMapToolFailureToOneSanitizedCorrelatedResult() {
        // Arrange
        EventHistoryFixture fixture = events("JCF-TOOLS-005");
        ConformanceValue.ObjectValue callEvent = event(fixture, "functionCall");
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = FunctionTool.create(
                fixtureMetadata(string(callEvent.require("name")), ToolApprovalMode.NEVER_REQUIRE),
                (context, arguments) -> {
                    invocations.incrementAndGet();
                    throw new ToolUserException("sensitive detail");
                });
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(call(callEvent, tool.name())))
                .enqueue(emptyResponse());

        // Act
        FunctionLoopResult result = run("run-005", source, List.of(tool));

        // Assert
        assertThat(invocations).hasValue(integer(fixture.expected().require("invocationCount")));
        assertThat(functionResults(result))
                .hasSize(integer(fixture.expected().require("functionResultCount")))
                .first()
                .satisfies(functionResult -> {
                    assertThat(functionResult.callId())
                            .isEqualTo(string(fixture.expected().require("resultCallId")));
                    assertThat(functionResult.result())
                            .isEqualTo(state(event(fixture, "functionResult").require("result")));
                    assertThat(functionResult.error()).doesNotContain("sensitive detail");
                });
    }

    @Test
    void jcfTools006_shouldBindStreamingAndFiniteViewsToEquivalentProductionHistory() {
        // Arrange
        EventHistoryFixture fixture = events("JCF-TOOLS-006");
        ConformanceValue.ObjectValue finiteCall = event(fixture, "functionCall", "nonStreaming");
        List<ConformanceValue.ObjectValue> streamingCalls = fixture.events().stream()
                .filter(value -> string(value.require("type")).equals("functionCallDelta"))
                .filter(value -> textOr(value, "mode", "").equals("streaming"))
                .toList();
        StateValue expectedResult =
                state(event(fixture, "functionResult", "nonStreaming").require("result"));
        AtomicInteger finiteCount = new AtomicInteger();
        AtomicInteger streamingCount = new AtomicInteger();
        FunctionTool finiteTool = fixtureTool("double", ToolApprovalMode.NEVER_REQUIRE, expectedResult, finiteCount);
        FunctionTool streamingTool =
                fixtureTool("double", ToolApprovalMode.NEVER_REQUIRE, expectedResult, streamingCount);
        FunctionCallContent call = new FunctionCallContent(
                string(finiteCall.require("callId")), "double", state(finiteCall.require("arguments")));
        String finalText =
                string(event(fixture, "assistantMessage", "nonStreaming").require("text"));
        ScriptedToolTurnSource finiteSource = new ScriptedToolTurnSource()
                .enqueue(response(call))
                .enqueue(response(Message.text(Role.ASSISTANT, finalText)));
        ScriptedToolTurnSource streamingSource = new ScriptedToolTurnSource()
                .enqueueStreaming(List.of(
                        ChatResponseUpdate.builder()
                                .role(Role.ASSISTANT)
                                .contents(List.of(new FunctionCallContent(
                                        string(streamingCalls.get(0).require("callId")),
                                        "double",
                                        StateValue.string(
                                                string(streamingCalls.get(0).require("argumentsDelta"))))))
                                .build(),
                        ChatResponseUpdate.builder()
                                .role(Role.ASSISTANT)
                                .contents(List.of(new FunctionCallContent(
                                        string(streamingCalls.get(1).require("callId")),
                                        "double",
                                        StateValue.string(
                                                string(streamingCalls.get(1).require("argumentsDelta"))))))
                                .finishReason(FinishReason.TOOL_CALLS)
                                .build()))
                .enqueueStreaming(List.of(ChatResponseUpdate.builder()
                        .role(Role.ASSISTANT)
                        .contents(List.of(new TextContent(
                                string(event(fixture, "textDelta", "streaming").require("text")))))
                        .finishReason(FinishReason.STOP)
                        .build()));

        // Act
        FunctionLoopResult finite = run("finite-006", finiteSource, List.of(finiteTool));
        FunctionLoopResult streaming;
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(streamingSource, List.of(streamingTool))) {
            FunctionInvocationRun run = loop.startStreaming(
                    new FunctionInvocationRequest("streaming-006", List.of(Message.text(Role.USER, "double"))));
            collect(run.updates()).join();
            streaming = run.resultAsync().toCompletableFuture().join();
        }

        // Assert
        assertThat(functionResults(streaming).getFirst().callId())
                .isEqualTo(functionResults(finite).getFirst().callId());
        assertThat(functionResults(streaming).getFirst().result())
                .isEqualTo(functionResults(finite).getFirst().result());
        assertThat(streaming.assistantText()).endsWith(finite.assistantText());
        assertThat(streamingCount).hasValue(integer(fixture.expected().require("streamingInvocationCount")));
        assertThat(finiteCount).hasValue(integer(fixture.expected().require("nonStreamingInvocationCount")));
    }

    @Test
    void jcfTools007_shouldInterruptBeforeApprovalAndResumeProductionRunExactlyOnce() {
        // Arrange
        EventHistoryFixture fixture = events("JCF-TOOLS-007");
        ConformanceValue.ObjectValue callEvent = event(fixture, "functionCall");
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = fixtureTool(
                string(callEvent.require("name")),
                ToolApprovalMode.ALWAYS_REQUIRE,
                state(event(fixture, "functionResult").require("result")),
                invocations);
        List<Message> callerInput = List.of(Message.text(Role.USER, "write"));
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(call(callEvent, tool.name())))
                .enqueue(response(Message.text(
                        Role.ASSISTANT,
                        string(event(fixture, "assistantMessage").require("text")))));

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(tool))) {
            FunctionLoopResult suspended = loop.run(new FunctionInvocationRequest("run-007", callerInput));
            int before = invocations.get();
            FunctionLoopResult resumed = loop.resume(
                            suspended,
                            List.of(ToolApprovalDecision.approve(
                                    suspended.approvalRequests().getFirst())))
                    .result();

            // Assert
            assertThat(before).isEqualTo(integer(fixture.expected().require("invocationsBeforeApproval")));
            assertThat(invocations).hasValue(integer(fixture.expected().require("invocationsAfterApproval")));
            assertThat(callerInput).containsExactly(Message.text(Role.USER, "write"));
            assertThat(resultPrecedesAssistantText(resumed)).isTrue();
        }
    }

    @Test
    void jcfTools008_shouldRejectStaleApprovalReplayWithoutSecondProductionInvocation() {
        // Arrange
        EventHistoryFixture fixture = events("JCF-TOOLS-008");
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = fixtureTool(
                "write",
                ToolApprovalMode.ALWAYS_REQUIRE,
                state(event(fixture, "functionResult").require("result")),
                invocations);
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(new FunctionCallContent("call-stale", "write", StateValue.object(Map.of()))))
                .enqueue(emptyResponse());

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(tool))) {
            FunctionLoopResult suspended =
                    loop.run(new FunctionInvocationRequest("run-008", List.of(Message.text(Role.USER, "write"))));
            ToolApprovalDecision decision =
                    ToolApprovalDecision.approve(suspended.approvalRequests().getFirst());
            FunctionLoopResult completed =
                    loop.resume(suspended, List.of(decision)).result();

            // Assert
            assertThatThrownBy(() -> loop.resume(suspended, List.of(decision))
                            .resultAsync()
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(ToolInvocationException.class);
            assertThat(invocations).hasValue(integer(fixture.expected().require("invocationCount")));
            assertThat(functionResults(completed))
                    .hasSize(integer(fixture.expected().require("functionResultOccurrences")));
            assertThat(completed.approvalRequests()).isEmpty();
        }
    }

    @Test
    void jcfTools009_shouldRejectDuplicateInFlightObservationWithoutSecondExecution() {
        // Arrange
        EventHistoryFixture fixture = events("JCF-TOOLS-009");
        AtomicInteger invocations = new AtomicInteger();
        CompletableFuture<StateValue> pending = new CompletableFuture<>();
        FunctionTool tool =
                FunctionTool.create(fixtureMetadata("write", ToolApprovalMode.NEVER_REQUIRE), (context, arguments) -> {
                    invocations.incrementAndGet();
                    return pending;
                });
        FunctionCallContent call = new FunctionCallContent(
                "call-in-flight", "write", StateValue.object(Map.of("value", StateValue.string("one"))));
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(new Message(Role.ASSISTANT, List.of(call, call))))
                .enqueue(emptyResponse());

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(tool))) {
            FunctionInvocationRun run =
                    loop.start(new FunctionInvocationRequest("run-009", List.of(Message.text(Role.USER, "write"))));
            CompletableFuture<FunctionLoopResult> result = run.resultAsync().toCompletableFuture();
            pending.complete(StateValue.string("written"));
            FunctionLoopResult completed = result.join();

            // Assert
            assertThat(invocations).hasValue(integer(fixture.expected().require("invocationCount")));
            assertThat(functionResults(completed))
                    .hasSize(integer(fixture.expected().require("functionResultCount")));
            assertThat(bool(fixture.expected().require("duplicateCallRejected")))
                    .isTrue();
        }
    }

    @Test
    void jcfTools010_shouldRejectDuplicatePendingDecisionAndExecutePrimaryOnce() {
        // Arrange
        EventHistoryFixture fixture = events("JCF-TOOLS-010");
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = fixtureTool(
                "write",
                ToolApprovalMode.ALWAYS_REQUIRE,
                state(event(fixture, "functionResult").require("result")),
                invocations);
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(new FunctionCallContent(
                        "call-decision", "write", StateValue.object(Map.of("value", StateValue.string("one"))))))
                .enqueue(emptyResponse());

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(tool))) {
            FunctionLoopResult suspended =
                    loop.run(new FunctionInvocationRequest("run-010", List.of(Message.text(Role.USER, "write"))));
            ToolApprovalRequest request = suspended.approvalRequests().getFirst();
            FunctionLoopResult completed = loop.resume(
                            suspended,
                            List.of(
                                    ToolApprovalDecision.approve(request),
                                    ToolApprovalDecision.reject(request, "duplicate")))
                    .result();

            // Assert
            assertThat(invocations).hasValue(integer(fixture.expected().require("invocationCount")));
            assertThat(completed.rejectedDecisions())
                    .extracting(ToolApprovalDecisionRejection::reason)
                    .containsExactly(ToolApprovalDecisionRejectionReason.DECISION_ALREADY_PENDING);
            assertThat(functionResults(completed))
                    .hasSize(integer(fixture.expected().require("functionResultCount")));
        }
    }

    @Test
    void jcfTools011_shouldProduceCorrelatedRejectedResultWithoutInvoking() {
        // Arrange
        EventHistoryFixture fixture = events("JCF-TOOLS-011");
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool =
                fixtureTool("write", ToolApprovalMode.ALWAYS_REQUIRE, StateValue.string("unused"), invocations);
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(new FunctionCallContent("call-rejected", "write", StateValue.object(Map.of()))));

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(tool))) {
            FunctionLoopResult suspended =
                    loop.run(new FunctionInvocationRequest("run-011", List.of(Message.text(Role.USER, "write"))));
            FunctionLoopResult rejected = loop.resume(
                            suspended,
                            List.of(ToolApprovalDecision.reject(
                                    suspended.approvalRequests().getFirst(), "no")))
                    .result();

            // Assert
            assertThat(invocations).hasValue(integer(fixture.expected().require("invocationCount")));
            assertThat(functionResults(rejected))
                    .hasSize(integer(fixture.expected().require("functionResultCount")))
                    .first()
                    .satisfies(functionResult -> {
                        assertThat(functionResult.callId())
                                .isEqualTo(string(fixture.expected().require("resultCallId")));
                        assertThat(functionResult.metadata())
                                .containsEntry(
                                        "invocationId", state(fixture.expected().require("resultInvocationId")))
                                .containsEntry(
                                        "outcome", state(fixture.expected().require("resultOutcome")));
                    });
            assertThat(bool(fixture.expected().require("toolExecuted"))).isFalse();
        }
    }

    @Test
    void jcfTools012_shouldShareOneInvocationOwnerAcrossAllProductionViews() {
        // Arrange
        EventHistoryFixture fixture = events("JCF-TOOLS-012");
        ConformanceValue.ObjectValue callEvent = event(fixture, "functionCall");
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = fixtureTool(
                string(callEvent.require("name")),
                ToolApprovalMode.NEVER_REQUIRE,
                state(event(fixture, "functionResult").require("result")),
                invocations);
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(call(callEvent, tool.name())))
                .enqueue(emptyResponse());

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(tool))) {
            FunctionInvocationRun run = loop.start(new FunctionInvocationRequest(
                    string(fixture.expected().require("logicalRunId")), List.of(Message.text(Role.USER, "write"))));
            CompletableFuture<FunctionLoopResult> asynchronous =
                    run.resultAsync().toCompletableFuture();
            List<ChatResponseUpdate> updates = collect(run.updates()).join();
            FunctionLoopResult synchronous = run.result();

            // Assert
            assertThat(asynchronous.join()).isSameAs(synchronous);
            assertThat(updates).isNotEmpty();
            assertThat(invocations).hasValue(integer(fixture.expected().require("totalInvocationCount")));
            assertThat(functionResults(synchronous))
                    .hasSize(integer(fixture.expected().require("functionResultCount")));
            assertThat(synchronous.logicalRunId())
                    .isEqualTo(string(fixture.expected().require("logicalRunId")));
            assertThat(synchronous.logicalRunId() + ":"
                            + functionResults(synchronous).getFirst().callId())
                    .isEqualTo(string(fixture.expected().require("sharedInvocationId")));
        }
    }

    @Test
    void jcfTools013_shouldExecuteOnlyApprovedBodyAndStreamEveryCorrelatedResultInModelOrder() {
        // Arrange
        EventHistoryFixture fixture = events("JCF-TOOLS-013");
        AtomicInteger approvedInvocations = new AtomicInteger();
        AtomicInteger rejectedInvocations = new AtomicInteger();
        FunctionTool approvedTool = fixtureTool(
                "approved",
                ToolApprovalMode.ALWAYS_REQUIRE,
                state(eventByCallId(fixture, "functionResult", "call-approved").require("result")),
                approvedInvocations);
        FunctionTool rejectedTool = fixtureTool(
                "rejected", ToolApprovalMode.ALWAYS_REQUIRE, StateValue.string("unused"), rejectedInvocations);
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(new Message(
                        Role.ASSISTANT,
                        List.of(
                                new FunctionCallContent(
                                        "call-approved", approvedTool.name(), StateValue.object(Map.of())),
                                new FunctionCallContent(
                                        "call-rejected", rejectedTool.name(), StateValue.object(Map.of()))))))
                .enqueueStreaming(List.of(ChatResponseUpdate.builder()
                        .role(Role.ASSISTANT)
                        .finishReason(FinishReason.STOP)
                        .build()));

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(approvedTool, rejectedTool))) {
            FunctionLoopResult suspended =
                    loop.run(new FunctionInvocationRequest("run-013", List.of(Message.text(Role.USER, "mixed"))));
            FunctionInvocationRun run = loop.resumeStreaming(
                    suspended,
                    List.of(
                            ToolApprovalDecision.approve(
                                    suspended.approvalRequests().get(0)),
                            ToolApprovalDecision.reject(
                                    suspended.approvalRequests().get(1), "declined")));
            List<ChatResponseUpdate> updates = collect(run.updates()).join();
            FunctionLoopResult completed = run.result();

            // Assert
            assertThat(approvedInvocations).hasValue(integer(fixture.expected().require("approvedInvocationCount")));
            assertThat(rejectedInvocations).hasValue(integer(fixture.expected().require("rejectedInvocationCount")));
            assertThat(functionResults(completed))
                    .extracting(FunctionResultContent::callId)
                    .containsExactlyElementsOf(strings(array(fixture.expected().require("resultOrder"))));
            assertThat(functionResults(completed))
                    .extracting(result -> result.metadata().get("outcome"))
                    .containsExactlyElementsOf(states(array(fixture.expected().require("resultOutcomes"))));
            assertThat(updates.stream()
                            .flatMap(update -> update.contents().stream())
                            .filter(FunctionResultContent.class::isInstance)
                            .map(FunctionResultContent.class::cast))
                    .extracting(FunctionResultContent::callId)
                    .containsExactlyElementsOf(strings(array(fixture.expected().require("resultOrder"))));
        }
    }

    private FunctionLoopResult run(String runId, ScriptedToolTurnSource source, List<? extends Tool> tools) {
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, tools)) {
            return loop.run(new FunctionInvocationRequest(runId, List.of(Message.text(Role.USER, "fixture input"))));
        }
    }

    private BehaviorFixture behavior(String caseId) {
        return (BehaviorFixture) catalog.requireCase(caseId);
    }

    private EventHistoryFixture events(String caseId) {
        return (EventHistoryFixture) catalog.requireCase(caseId);
    }

    private static FunctionTool fixtureTool(
            String name, ToolApprovalMode approvalMode, StateValue result, AtomicInteger invocations) {
        return FunctionTool.create(fixtureMetadata(name, approvalMode), (context, arguments) -> {
            invocations.incrementAndGet();
            return CompletableFuture.completedFuture(result);
        });
    }

    private static ToolMetadata fixtureMetadata(String name, ToolApprovalMode approvalMode) {
        return new ToolMetadata(
                name,
                "Fixture tool " + name,
                Set.of(ToolCapability.FUNCTION),
                approvalMode,
                StateValue.object(Map.of("type", StateValue.string("object"))),
                StateValue.object(Map.of()));
    }

    private static FunctionCallContent call(ConformanceValue.ObjectValue event, String fallbackName) {
        return new FunctionCallContent(
                string(event.require("callId")),
                textOr(event, "name", fallbackName),
                event.values().containsKey("arguments")
                        ? state(event.require("arguments"))
                        : StateValue.object(Map.of()));
    }

    private static ChatResponse response(FunctionCallContent call) {
        return response(new Message(Role.ASSISTANT, List.of(call)));
    }

    private static ChatResponse response(Message message) {
        return ChatResponse.builder().messages(List.of(message)).build();
    }

    private static ChatResponse emptyResponse() {
        return ChatResponse.builder().messages(List.of()).build();
    }

    private static List<FunctionResultContent> functionResults(FunctionLoopResult result) {
        return result.history().stream()
                .flatMap(message -> message.contents().stream())
                .filter(FunctionResultContent.class::isInstance)
                .map(FunctionResultContent.class::cast)
                .toList();
    }

    private static boolean resultPrecedesAssistantText(FunctionLoopResult result) {
        int resultIndex = -1;
        int textIndex = -1;
        for (int index = 0; index < result.history().size(); index++) {
            Message message = result.history().get(index);
            if (message.contents().stream().anyMatch(FunctionResultContent.class::isInstance)) {
                resultIndex = index;
            }
            if (index > resultIndex
                    && message.role().equals(Role.ASSISTANT)
                    && !message.text().isEmpty()) {
                textIndex = index;
                break;
            }
        }
        return resultIndex >= 0 && textIndex > resultIndex;
    }

    private static Map<String, StateValue> resultValues(EventHistoryFixture fixture) {
        LinkedHashMap<String, StateValue> results = new LinkedHashMap<>();
        eventsOf(fixture, "functionResult")
                .forEach(event -> results.put(string(event.require("callId")), state(event.require("result"))));
        return results;
    }

    private static ConformanceValue.ObjectValue event(EventHistoryFixture fixture, String type) {
        return eventsOf(fixture, type).getFirst();
    }

    private static ConformanceValue.ObjectValue event(EventHistoryFixture fixture, String type, String mode) {
        return fixture.events().stream()
                .filter(value -> string(value.require("type")).equals(type))
                .filter(value -> textOr(value, "mode", "").equals(mode))
                .findFirst()
                .orElseThrow();
    }

    private static ConformanceValue.ObjectValue eventByCallId(EventHistoryFixture fixture, String type, String callId) {
        return fixture.events().stream()
                .filter(value -> string(value.require("type")).equals(type))
                .filter(value -> textOr(value, "callId", "").equals(callId))
                .findFirst()
                .orElseThrow();
    }

    private static List<ConformanceValue.ObjectValue> eventsOf(EventHistoryFixture fixture, String type) {
        return fixture.events().stream()
                .filter(value -> string(value.require("type")).equals(type))
                .toList();
    }

    private static String textOr(ConformanceValue.ObjectValue object, String field, String fallback) {
        ConformanceValue value = object.values().get(field);
        return value instanceof ConformanceValue.StringValue stringValue ? stringValue.value() : fallback;
    }

    private static CompletableFuture<List<ChatResponseUpdate>> collect(Flow.Publisher<ChatResponseUpdate> publisher) {
        CompletableFuture<List<ChatResponseUpdate>> result = new CompletableFuture<>();
        List<ChatResponseUpdate> updates = new ArrayList<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ChatResponseUpdate item) {
                updates.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.complete(List.copyOf(updates));
            }
        });
        return result;
    }

    private static StateValue state(ConformanceValue value) {
        return switch (value) {
            case ConformanceValue.ObjectValue object -> {
                LinkedHashMap<String, StateValue> members = new LinkedHashMap<>();
                object.values().forEach((key, member) -> members.put(key, state(member)));
                yield StateValue.object(members);
            }
            case ConformanceValue.ArrayValue array ->
                StateValue.array(
                        array.values().stream().map(ToolsConformanceTest::state).toList());
            case ConformanceValue.StringValue string -> StateValue.string(string.value());
            case ConformanceValue.NumberValue number -> StateValue.number(number.value());
            case ConformanceValue.BooleanValue bool -> StateValue.bool(bool.value());
            case ConformanceValue.NullValue nullValue -> {
                assertThat(nullValue).isSameAs(ConformanceValue.NullValue.INSTANCE);
                yield StateValue.nullValue();
            }
        };
    }

    private static ConformanceValue.ObjectValue object(ConformanceValue value) {
        return (ConformanceValue.ObjectValue) value;
    }

    private static ConformanceValue.ArrayValue array(ConformanceValue value) {
        return (ConformanceValue.ArrayValue) value;
    }

    private static String string(ConformanceValue value) {
        return ((ConformanceValue.StringValue) value).value();
    }

    private static boolean bool(ConformanceValue value) {
        return ((ConformanceValue.BooleanValue) value).value();
    }

    private static int integer(ConformanceValue value) {
        return ((ConformanceValue.NumberValue) value).value().intValueExact();
    }

    private static List<String> strings(ConformanceValue.ArrayValue value) {
        return value.values().stream().map(ToolsConformanceTest::string).toList();
    }

    private static List<StateValue> states(ConformanceValue.ArrayValue value) {
        return value.values().stream().map(ToolsConformanceTest::state).toList();
    }
}
