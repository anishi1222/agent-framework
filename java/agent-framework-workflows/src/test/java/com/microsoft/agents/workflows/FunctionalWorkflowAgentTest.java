// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateCodec;
import com.microsoft.agents.core.StateValue;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FunctionalWorkflowAgentTest {
    private static final StateCodec<String> STRING = WorkflowCodecs.stringCodec();

    @Test
    void runAsync_shouldMapMessagesAndReturnTypedWorkflowOutput() {
        // Arrange
        FunctionalStep<String, String> upper = FunctionalStep.sync(
                "upper", String.class, String.class, STRING, STRING, (input, context) -> input.toUpperCase());
        FunctionalWorkflow<String, String> workflow = singleStepWorkflow("agent-output", upper);
        try (FunctionalWorkflowAgent<String, String> agent = workflow.asAgent(
                new AgentMetadata("functional-agent", "Functional Agent", "Runs a functional workflow."),
                FunctionalWorkflowAgent.joinedTextInput(),
                FunctionalWorkflowAgent.assistantTextOutput())) {
            // Act
            AgentResponse<String> response = agent.runAsync(
                            List.of(Message.text(Role.SYSTEM, "policy"), Message.text(Role.USER, "draft")))
                    .toCompletableFuture()
                    .join();

            // Assert
            assertThat(response.value()).isEqualTo("POLICY\nDRAFT");
            assertThat(response.text()).isEqualTo("POLICY\nDRAFT");
            assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
            assertThat(response.continuationToken()).isNull();
            assertThat(response.agentId()).isEqualTo("functional-agent");
        } finally {
            workflow.close();
        }
    }

    @Test
    void runAsync_shouldSurfaceRequestInfoAndResumeFromFunctionResult() {
        // Arrange
        AtomicInteger inputMappings = new AtomicInteger();
        FunctionalStep<String, String> review = FunctionalStep.sync(
                "review",
                String.class,
                String.class,
                STRING,
                STRING,
                (input, context) ->
                        input + "-" + context.requestInfo(StateValue.string("Approve " + input), String.class, STRING));
        FunctionalWorkflow<String, String> workflow = singleStepWorkflow("agent-hitl", review);
        try (FunctionalWorkflowAgent<String, String> agent = workflow.asAgent(
                new AgentMetadata("review-agent", "Review Agent", null),
                messages -> {
                    inputMappings.incrementAndGet();
                    return FunctionalWorkflowAgent.joinedTextInput().apply(messages);
                },
                FunctionalWorkflowAgent.assistantTextOutput())) {
            AgentResponse<String> pending =
                    agent.runAsync("draft").toCompletableFuture().join();

            // Act
            AgentResponse<String> completed = agent.runAsync(new Message(
                            Role.TOOL, List.of(new FunctionResultContent("auto::0", StateValue.string("approved")))))
                    .toCompletableFuture()
                    .join();

            // Assert
            assertThat(pending.value()).isNull();
            assertThat(pending.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
            assertThat(pending.continuationToken()).isInstanceOf(StateValue.ObjectValue.class);
            assertThat(pending.messages()).singleElement().satisfies(message -> {
                assertThat(message.role()).isEqualTo(Role.ASSISTANT);
                assertThat(message.contents())
                        .singleElement()
                        .isInstanceOfSatisfying(FunctionCallContent.class, call -> {
                            assertThat(call.callId()).isEqualTo("auto::0");
                            assertThat(call.name()).isEqualTo(FunctionalWorkflowAgent.REQUEST_INFO_FUNCTION);
                            assertThat(call.informationalOnly()).isTrue();
                        });
            });
            assertThat(completed.value()).isEqualTo("draft-approved");
            assertThat(completed.text()).isEqualTo("draft-approved");
            assertThat(completed.finishReason()).isEqualTo(FinishReason.STOP);
            assertThat(completed.continuationToken()).isNull();
            assertThat(inputMappings).hasValue(1);
        } finally {
            workflow.close();
        }
    }

    @Test
    void runStreaming_shouldRemainColdAndEmitMappedOutputAndTerminalUpdate() {
        // Arrange
        AtomicInteger calls = new AtomicInteger();
        FunctionalStep<String, String> upper =
                FunctionalStep.sync("upper", String.class, String.class, STRING, STRING, (input, context) -> {
                    calls.incrementAndGet();
                    return input.toUpperCase();
                });
        FunctionalWorkflow<String, String> workflow = singleStepWorkflow("agent-streaming", upper);
        try (FunctionalWorkflowAgent<String, String> agent = workflow.asAgent(
                new AgentMetadata("streaming-agent", null, null),
                FunctionalWorkflowAgent.joinedTextInput(),
                FunctionalWorkflowAgent.assistantTextOutput())) {
            Flow.Publisher<AgentResponseUpdate> publisher = agent.runStreaming("hello");

            // Act
            assertThat(calls).hasValue(0);
            List<AgentResponseUpdate> updates = collect(publisher);

            // Assert
            assertThat(calls).hasValue(1);
            assertThat(updates).hasSize(2);
            assertThat(updates.get(0).text()).isEqualTo("HELLO");
            assertThat(updates.get(0).finishReason()).isNull();
            assertThat(updates.get(1).contents()).isEmpty();
            assertThat(updates.get(1).finishReason()).isEqualTo(FinishReason.STOP);
            assertThat(updates).extracting(AgentResponseUpdate::sequence).containsExactly(0L, 1L);
        } finally {
            workflow.close();
        }
    }

    @Test
    void startRun_shouldPropagateCancellationToFunctionalWorkflow() throws Exception {
        // Arrange
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<String> gate = new CompletableFuture<>();
        FunctionalWorkflow<String, String> workflow = FunctionalWorkflow.builder(
                        "agent-cancellation", String.class, String.class, STRING, STRING)
                .body((input, context) -> {
                    started.countDown();
                    return gate;
                })
                .build();
        try (FunctionalWorkflowAgent<String, String> agent = workflow.asAgent(
                new AgentMetadata("cancel-agent", null, null),
                FunctionalWorkflowAgent.joinedTextInput(),
                FunctionalWorkflowAgent.assistantTextOutput())) {
            RunHandle<AgentResponse<String>> run = agent.startRun(
                    List.of(Message.text(Role.USER, "wait")), RunOptions.empty(), new DefaultRunCancellation());
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            // Act
            assertThat(run.cancel()).isTrue();

            // Assert
            assertThatThrownBy(() -> run.resultAsync().toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RunCancelledException.class);
        } finally {
            gate.complete("released");
            workflow.close();
        }
    }

    private static FunctionalWorkflow<String, String> singleStepWorkflow(
            String id, FunctionalStep<String, String> step) {
        return FunctionalWorkflow.builder(id, String.class, String.class, STRING, STRING)
                .body((input, context) -> context.runStepAsync(step, input))
                .build();
    }

    private static List<AgentResponseUpdate> collect(Flow.Publisher<AgentResponseUpdate> publisher) {
        CompletableFuture<List<AgentResponseUpdate>> completion = new CompletableFuture<>();
        ArrayList<AgentResponseUpdate> updates = new ArrayList<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AgentResponseUpdate item) {
                updates.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                completion.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                completion.complete(List.copyOf(updates));
            }
        });
        return completion.orTimeout(5, TimeUnit.SECONDS).join();
    }
}
