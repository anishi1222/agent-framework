// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.ReasoningContent;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FunctionInvocationLoopTest {
    @Test
    void finiteConvenienceRun_shouldDiscardMoreUpdatesThanStreamingBufferLimitAndPreserveHistory() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool lookup = stringTool("lookup", ToolApprovalMode.NEVER_REQUIRE, arguments -> {
            invocations.incrementAndGet();
            return StateValue.string("found");
        });
        Message calls = new Message(
                Role.ASSISTANT,
                List.of(
                        call("call-1", "lookup", "value", StateValue.string("one")),
                        call("call-2", "lookup", "value", StateValue.string("two")),
                        call("call-3", "lookup", "value", StateValue.string("three"))));
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(calls))
                .enqueue(response(Message.text(Role.ASSISTANT, "done")));
        FunctionInvocationOptions options = new FunctionInvocationOptions(4, null, ToolMode.AUTO, false, 2);
        FunctionInvocationRequest request = new FunctionInvocationRequest(
                "finite-discard",
                List.of(Message.text(Role.USER, "lookup all")),
                options,
                new DefaultRunCancellation(),
                Map.of());

        // Act
        FunctionLoopResult result;
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(lookup))) {
            result = loop.runAsync(request).toCompletableFuture().join();
        }

        // Assert
        assertThat(invocations).hasValue(3);
        assertThat(result.outcome()).isEqualTo(FunctionLoopOutcome.SUCCESS);
        assertThat(result.history())
                .extracting(Message::role)
                .containsExactly(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT);
        assertThat(functionResults(result)).hasSize(3);
        assertThat(result.assistantText()).contains("done");
    }

    @Test
    void run_shouldInvokeOneToolAndPreserveCallResultAssistantHistoryOrder() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool lookup = stringTool("lookup", ToolApprovalMode.NEVER_REQUIRE, arguments -> {
            invocations.incrementAndGet();
            return StateValue.integer(7);
        });
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(call("call-001", "lookup", "key", StateValue.string("alpha"))))
                .enqueue(response(Message.text(Role.ASSISTANT, "The value is 7.")));

        // Act
        FunctionLoopResult result;
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(lookup))) {
            result = loop.run(
                    new FunctionInvocationRequest("run-001", List.of(Message.text(Role.USER, "lookup alpha"))));
        }

        // Assert
        assertThat(result.outcome()).isEqualTo(FunctionLoopOutcome.SUCCESS);
        assertThat(invocations).hasValue(1);
        assertThat(result.history())
                .extracting(Message::role)
                .containsExactly(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT);
        FunctionResultContent functionResult = result.history().stream()
                .flatMap(message -> message.contents().stream())
                .filter(FunctionResultContent.class::isInstance)
                .map(FunctionResultContent.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(functionResult.callId()).isEqualTo("call-001");
        assertThat(functionResult.result()).isEqualTo(StateValue.integer(7));
        assertThat(result.assistantText()).contains("The value is 7.");
    }

    @Test
    void multipleCalls_shouldExecuteConcurrentlyButAppendResultsInModelOrder() throws Exception {
        // Arrange
        CompletableFuture<StateValue> firstResult = new CompletableFuture<>();
        CompletableFuture<StateValue> secondResult = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(2);
        FunctionTool first = asyncStringTool("first", arguments -> {
            started.countDown();
            return firstResult;
        });
        FunctionTool second = asyncStringTool("second", arguments -> {
            started.countDown();
            return secondResult;
        });
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(new Message(
                        Role.ASSISTANT,
                        List.of(
                                call("call-a", "first", "value", StateValue.integer(1)),
                                call("call-b", "second", "value", StateValue.integer(2))))))
                .enqueue(response(Message.text(Role.ASSISTANT, "done")));

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(first, second))) {
            FunctionInvocationRun run =
                    loop.start(new FunctionInvocationRequest("run-multiple", List.of(Message.text(Role.USER, "both"))));
            CompletableFuture<FunctionLoopResult> result = run.resultAsync().toCompletableFuture();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            secondResult.complete(StateValue.integer(4));
            firstResult.complete(StateValue.integer(2));

            // Assert
            List<FunctionResultContent> results = result.join().history().stream()
                    .flatMap(message -> message.contents().stream())
                    .filter(FunctionResultContent.class::isInstance)
                    .map(FunctionResultContent.class::cast)
                    .toList();
            assertThat(results).extracting(FunctionResultContent::callId).containsExactly("call-a", "call-b");
            assertThat(results)
                    .extracting(FunctionResultContent::result)
                    .containsExactly(StateValue.integer(2), StateValue.integer(4));
        }
    }

    @Test
    void duplicateInFlightAndCompletedCalls_shouldInvokeAndEmitExactlyOnce() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        CompletableFuture<StateValue> toolResult = new CompletableFuture<>();
        FunctionTool tool = asyncStringTool("write", arguments -> {
            invocations.incrementAndGet();
            return toolResult;
        });
        FunctionCallContent call = call("call-once", "write", "value", StateValue.string("one"));
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(new Message(Role.ASSISTANT, List.of(call, call))))
                .enqueue(response(call));

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(tool))) {
            FunctionInvocationRun run =
                    loop.start(new FunctionInvocationRequest("run-once", List.of(Message.text(Role.USER, "write"))));
            toolResult.complete(StateValue.string("written"));
            FunctionLoopResult result = run.resultAsync().toCompletableFuture().join();

            // Assert
            assertThat(invocations).hasValue(1);
            assertThat(result.history().stream()
                            .flatMap(message -> message.contents().stream())
                            .filter(FunctionCallContent.class::isInstance))
                    .hasSize(1);
            assertThat(result.history().stream()
                            .flatMap(message -> message.contents().stream())
                            .filter(FunctionResultContent.class::isInstance))
                    .hasSize(1);
        }
    }

    @Test
    void invocationIdReuseWithDifferentArguments_shouldFailWithoutSecondSideEffect() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = stringTool("write", ToolApprovalMode.NEVER_REQUIRE, arguments -> {
            invocations.incrementAndGet();
            return StateValue.string("written");
        });
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(call("call-same", "write", "value", StateValue.string("first"))))
                .enqueue(response(call("call-same", "write", "value", StateValue.string("changed"))));

        // Act / Assert
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(tool))) {
            FunctionInvocationRun run = loop.start(
                    new FunctionInvocationRequest("run-collision", List.of(Message.text(Role.USER, "write"))));
            assertThatThrownBy(() -> run.resultAsync().toCompletableFuture().join())
                    .rootCause()
                    .isInstanceOf(ToolInvocationException.class)
                    .hasMessageContaining("different tool, schema, or argument digest");
            assertThat(invocations).hasValue(1);
        }
    }

    @Test
    void argumentAndRecoverableToolUserErrors_shouldBecomeCorrelatedSanitizedResults() {
        // Arrange
        AtomicInteger bodies = new AtomicInteger();
        FunctionTool failing =
                FunctionTool.create(metadata("fail", ToolApprovalMode.NEVER_REQUIRE), (context, arguments) -> {
                    bodies.incrementAndGet();
                    throw new ToolUserException("secret failure detail");
                });
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(call("call-bind", "fail", StateValue.string("not-an-object"))))
                .enqueue(response(call("call-fail", "fail", "value", StateValue.string("x"))))
                .enqueue(response(Message.text(Role.ASSISTANT, "done")));

        // Act
        FunctionLoopResult result;
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(failing))) {
            result = loop.run(
                    new FunctionInvocationRequest("run-errors", List.of(Message.text(Role.USER, "fail twice"))));
        }

        // Assert
        assertThat(bodies).hasValue(1);
        List<FunctionResultContent> results = functionResults(result);
        assertThat(results).extracting(FunctionResultContent::callId).containsExactly("call-bind", "call-fail");
        assertThat(results.get(0).result()).isEqualTo(StateValue.string("Error: Argument parsing failed."));
        assertThat(results.get(1).result()).isEqualTo(StateValue.string("Error: Function failed."));
        assertThat(results.get(1).error()).doesNotContain("secret failure detail");
    }

    @Test
    void outputSchemaValidationFailure_shouldUseDedicatedCorrelatedOutcomeAndMessage() {
        // Arrange
        AtomicInteger bodies = new AtomicInteger();
        FunctionTool invalidOutput = FunctionTool.create(
                metadata("invalid-output", ToolApprovalMode.NEVER_REQUIRE), (context, arguments) -> {
                    bodies.incrementAndGet();
                    return CompletableFuture.completedFuture(StateValue.bool(true));
                });
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(call("call-output", "invalid-output", "value", StateValue.string("input"))))
                .enqueue(emptyResponse());

        // Act
        FunctionLoopResult result;
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(invalidOutput))) {
            result = loop.run(new FunctionInvocationRequest(
                    "run-output-validation", List.of(Message.text(Role.USER, "validate"))));
        }

        // Assert
        assertThat(bodies).hasValue(1);
        assertThat(functionResults(result)).singleElement().satisfies(functionResult -> {
            assertThat(functionResult.callId()).isEqualTo("call-output");
            assertThat(functionResult.result())
                    .isEqualTo(StateValue.string("Error: Tool output schema validation failed."));
            assertThat(functionResult.error()).isEqualTo("Error: Tool output schema validation failed.");
            assertThat(functionResult.metadata())
                    .containsEntry("invocationId", StateValue.string("run-output-validation:call-output"))
                    .containsEntry("outcome", StateValue.string("outputValidationFailed"));
        });
    }

    @Test
    void encodedArguments_shouldRejectDuplicateJsonKeysBeforeToolBody() {
        // Arrange
        AtomicInteger bodies = new AtomicInteger();
        FunctionTool tool = stringTool("write", ToolApprovalMode.NEVER_REQUIRE, arguments -> {
            bodies.incrementAndGet();
            return StateValue.string("written");
        });
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(new FunctionCallContent(
                        "call-duplicate-json", "write", StateValue.string("{\"value\":\"one\",\"value\":\"two\"}"))))
                .enqueue(emptyResponse());

        // Act
        FunctionLoopResult result;
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(tool))) {
            result = loop.run(
                    new FunctionInvocationRequest("run-duplicate-json", List.of(Message.text(Role.USER, "write"))));
        }

        // Assert
        assertThat(bodies).hasValue(0);
        assertThat(functionResults(result))
                .singleElement()
                .extracting(FunctionResultContent::result)
                .isEqualTo(StateValue.string("Error: Argument parsing failed."));
    }

    @Test
    void unknownFunction_shouldProduceCorrelatedResultAndNeverOrphanCall() {
        // Arrange
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(call("call-unknown", "missing", StateValue.object(Map.of()))))
                .enqueue(response(Message.text(Role.ASSISTANT, "handled")));

        // Act
        FunctionLoopResult result;
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of())) {
            result = loop.run(
                    new FunctionInvocationRequest("run-unknown", List.of(Message.text(Role.USER, "call missing"))));
        }

        // Assert
        List<FunctionResultContent> results = functionResults(result);
        assertThat(results)
                .singleElement()
                .extracting(FunctionResultContent::callId)
                .isEqualTo("call-unknown");
        assertThat(results.getFirst().result())
                .isEqualTo(StateValue.string("Error: Requested function was not found."));
    }

    @Test
    void providerAndFrameworkFailures_shouldPropagateInsteadOfBecomingToolResults() {
        // Arrange
        IllegalStateException providerFailure = new IllegalStateException("provider unavailable");
        ScriptedToolTurnSource providerSource =
                new ScriptedToolTurnSource().enqueue(CompletableFuture.failedFuture(providerFailure));

        // Act / Assert
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(providerSource, List.of())) {
            assertThatThrownBy(() -> loop.runAsync(new FunctionInvocationRequest(
                                    "run-provider-failure", List.of(Message.text(Role.USER, "fail"))))
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(java.util.concurrent.CompletionException.class)
                    .rootCause()
                    .isSameAs(providerFailure);
        }

        FunctionTool frameworkFailureTool = FunctionTool.create(
                metadata("write", ToolApprovalMode.NEVER_REQUIRE),
                (context, arguments) ->
                        CompletableFuture.failedFuture(new ToolInvocationException("framework invariant failed")));
        ScriptedToolTurnSource toolSource = new ScriptedToolTurnSource()
                .enqueue(response(call("call-framework", "write", "value", StateValue.string("one"))));
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(toolSource, List.of(frameworkFailureTool))) {
            assertThatThrownBy(() -> loop.runAsync(new FunctionInvocationRequest(
                                    "run-framework-failure", List.of(Message.text(Role.USER, "fail"))))
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(java.util.concurrent.CompletionException.class)
                    .rootCause()
                    .isInstanceOf(ToolInvocationException.class)
                    .hasMessageContaining("framework invariant failed");
        }

        IllegalStateException unexpectedFailure = new IllegalStateException("unexpected tool bug");
        FunctionTool unexpectedFailureTool = FunctionTool.create(
                metadata("unexpected", ToolApprovalMode.NEVER_REQUIRE),
                (context, arguments) -> CompletableFuture.failedFuture(unexpectedFailure));
        ScriptedToolTurnSource unexpectedSource = new ScriptedToolTurnSource()
                .enqueue(response(call("call-unexpected", "unexpected", "value", StateValue.string("one"))));
        try (FunctionInvocationLoop loop =
                new FunctionInvocationLoop(unexpectedSource, List.of(unexpectedFailureTool))) {
            assertThatThrownBy(() -> loop.runAsync(new FunctionInvocationRequest(
                                    "run-unexpected-failure", List.of(Message.text(Role.USER, "fail"))))
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(java.util.concurrent.CompletionException.class)
                    .rootCause()
                    .isSameAs(unexpectedFailure);
        }

        ValidationException frameworkValidation = new ValidationException("framework validation failure");
        FunctionTool validationFailureTool = FunctionTool.create(
                metadata("validation", ToolApprovalMode.NEVER_REQUIRE),
                (context, arguments) -> CompletableFuture.failedFuture(frameworkValidation));
        ScriptedToolTurnSource validationSource = new ScriptedToolTurnSource()
                .enqueue(response(call("call-validation", "validation", "value", StateValue.string("one"))));
        try (FunctionInvocationLoop loop =
                new FunctionInvocationLoop(validationSource, List.of(validationFailureTool))) {
            assertThatThrownBy(() -> loop.runAsync(new FunctionInvocationRequest(
                                    "run-framework-validation", List.of(Message.text(Role.USER, "fail"))))
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(java.util.concurrent.CompletionException.class)
                    .rootCause()
                    .isSameAs(frameworkValidation);
        }

        AssertionError fatalFailure = new AssertionError("fatal tool error");
        FunctionTool fatalTool =
                FunctionTool.create(metadata("fatal", ToolApprovalMode.NEVER_REQUIRE), (context, arguments) -> {
                    throw fatalFailure;
                });
        ScriptedToolTurnSource fatalSource = new ScriptedToolTurnSource()
                .enqueue(response(call("call-fatal", "fatal", "value", StateValue.string("one"))));
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(fatalSource, List.of(fatalTool))) {
            assertThatThrownBy(() -> loop.runAsync(new FunctionInvocationRequest(
                                    "run-fatal-error", List.of(Message.text(Role.USER, "fail"))))
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(java.util.concurrent.CompletionException.class)
                    .rootCause()
                    .isSameAs(fatalFailure);
        }
    }

    @Test
    void history_shouldPreserveReasoningCallResultAndFinalAssistantGroupingOrder() {
        // Arrange
        FunctionTool tool =
                stringTool("write", ToolApprovalMode.NEVER_REQUIRE, arguments -> StateValue.string("written"));
        ReasoningContent reasoning = new ReasoningContent("reasoning-1", "I should write the value.");
        FunctionCallContent call = call("call-reasoning", "write", "value", StateValue.string("one"));
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(new Message(Role.ASSISTANT, List.of(reasoning, call))))
                .enqueue(response(Message.text(Role.ASSISTANT, "completed")));

        // Act
        FunctionLoopResult result;
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(tool))) {
            result =
                    loop.run(new FunctionInvocationRequest("run-reasoning", List.of(Message.text(Role.USER, "write"))));
        }

        // Assert
        List<String> kinds = result.history().stream()
                .flatMap(message -> message.contents().stream())
                .map(com.microsoft.agents.core.Content::kind)
                .toList();
        assertThat(kinds).containsSubsequence("reasoning", "functionCall", "functionResult", "text");
    }

    private static List<FunctionResultContent> functionResults(FunctionLoopResult result) {
        return result.history().stream()
                .flatMap(message -> message.contents().stream())
                .filter(FunctionResultContent.class::isInstance)
                .map(FunctionResultContent.class::cast)
                .toList();
    }

    private static FunctionTool stringTool(
            String name,
            ToolApprovalMode approvalMode,
            java.util.function.Function<StateValue.ObjectValue, StateValue> function) {
        return FunctionTool.create(
                metadata(name, approvalMode),
                (context, arguments) -> CompletableFuture.completedFuture(function.apply(arguments)));
    }

    private static FunctionTool asyncStringTool(
            String name, java.util.function.Function<StateValue.ObjectValue, CompletableFuture<StateValue>> function) {
        return FunctionTool.create(
                metadata(name, ToolApprovalMode.NEVER_REQUIRE), (context, arguments) -> function.apply(arguments));
    }

    private static ToolMetadata metadata(String name, ToolApprovalMode approvalMode) {
        return new ToolMetadata(
                name,
                name + " tool",
                Set.of(ToolCapability.FUNCTION),
                approvalMode,
                StateValue.object(Map.of(
                        "type",
                        StateValue.string("object"),
                        "properties",
                        StateValue.object(Map.of(
                                "key",
                                StateValue.object(Map.of("type", StateValue.string("string"))),
                                "value",
                                StateValue.object(Map.of(
                                        "anyOf",
                                        StateValue.array(List.of(
                                                StateValue.object(Map.of("type", StateValue.string("string"))),
                                                StateValue.object(Map.of("type", StateValue.string("integer"))))))))),
                        "additionalProperties",
                        StateValue.bool(false))),
                StateValue.object(Map.of(
                        "anyOf",
                        StateValue.array(List.of(
                                StateValue.object(Map.of("type", StateValue.string("string"))),
                                StateValue.object(Map.of("type", StateValue.string("integer"))))))));
    }

    private static FunctionCallContent call(String callId, String name, String argumentName, StateValue argument) {
        return call(callId, name, StateValue.object(Map.of(argumentName, argument)));
    }

    private static FunctionCallContent call(String callId, String name, StateValue arguments) {
        return new FunctionCallContent(callId, name, arguments);
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
}
