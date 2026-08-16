// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LoopEvaluatorsTest {
    @Test
    void aiJudge_shouldUseStructuredVerdictAndPreserveOriginalContent() {
        RecordingJudgeClient judge =
                new RecordingJudgeClient(response("{\"answered\":false,\"gapAnalysis\":\"Add integration tests.\"}"));
        AIJudgeLoopEvaluatorOptions options = AIJudgeLoopEvaluatorOptions.builder()
                .criteria(List.of("Include tests.", "Explain failures."))
                .build();
        AIJudgeLoopEvaluator evaluator = new AIJudgeLoopEvaluator(judge, options);
        FunctionCallContent originalContent =
                new FunctionCallContent("original-call", "research", StateValue.object(Map.of()));
        DefaultRunCancellation cancellation = new DefaultRunCancellation();

        try (ChatAgent agent = agent()) {
            LoopEvaluation evaluation = evaluator
                    .evaluateAsync(
                            context(
                                    agent,
                                    List.of(Message.builder(Role.USER)
                                            .contents(List.of(new TextContent("Investigate."), originalContent))
                                            .build()),
                                    "Partial response."),
                            cancellation)
                    .toCompletableFuture()
                    .join();

            assertThat(evaluation.shouldContinue()).isTrue();
            assertThat(evaluation.feedback()).contains("Add integration tests.");
            assertThat(judge.cancellation.get()).isSameAs(cancellation);
            assertThat(judge.request.get().options().structuredOutput()).isNotNull();
            assertThat(judge.request.get().messages().getFirst().text())
                    .contains("- Include tests.", "- Explain failures.");
            assertThat(judge.request.get().messages().getLast().contents()).contains(originalContent);
        }
    }

    @Test
    void aiJudge_shouldStopForStructuredOrFallbackDoneVerdict() {
        try (ChatAgent agent = agent()) {
            LoopContext context = context(agent, List.of(Message.text(Role.USER, "Complete the task.")), "Finished.");

            LoopEvaluation structured = new AIJudgeLoopEvaluator(
                            new RecordingJudgeClient(response("{\"answered\":true}")))
                    .evaluateAsync(context, new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();
            LoopEvaluation fallback = new AIJudgeLoopEvaluator(new RecordingJudgeClient(response("VERDICT: DONE")))
                    .evaluateAsync(context, new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();

            assertThat(structured).isEqualTo(LoopEvaluation.stop());
            assertThat(fallback).isEqualTo(LoopEvaluation.stop());
        }
    }

    @Test
    void aiJudge_shouldContinueWhenFallbackIsAmbiguousOrMissing() {
        try (ChatAgent agent = agent()) {
            LoopContext context = context(agent, List.of(Message.text(Role.USER, "Complete.")), "Maybe.");

            LoopEvaluation ambiguous = new AIJudgeLoopEvaluator(
                            new RecordingJudgeClient(response("VERDICT: DONE, but also VERDICT: MORE")))
                    .evaluateAsync(context, new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();
            LoopEvaluation missing = new AIJudgeLoopEvaluator(
                            new RecordingJudgeClient(response("No structured verdict.")))
                    .evaluateAsync(context, new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();

            assertThat(ambiguous.shouldContinue()).isTrue();
            assertThat(missing.feedback()).contains("<unknown>");
        }
    }

    @Test
    void completionMarker_shouldSubstituteResponseAndRejectBlankMarker() {
        CompletionMarkerLoopEvaluator evaluator = new CompletionMarkerLoopEvaluator(
                "TASK_DONE", "Previous: {last_response}; finish with {completion_marker}.");

        try (ChatAgent agent = agent()) {
            LoopEvaluation incomplete = evaluator
                    .evaluateAsync(
                            context(agent, List.of(Message.text(Role.USER, "work")), "still working"),
                            new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();
            LoopEvaluation complete = evaluator
                    .evaluateAsync(
                            context(agent, List.of(Message.text(Role.USER, "work")), "finished TASK_DONE"),
                            new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();

            assertThat(incomplete.feedback()).isEqualTo("Previous: still working; finish with TASK_DONE.");
            assertThat(complete).isEqualTo(LoopEvaluation.stop());
        }
        assertThatThrownBy(() -> new CompletionMarkerLoopEvaluator(" \t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    private static ChatAgent agent() {
        return new ChatAgent(new RecordingJudgeClient(response("unused")));
    }

    private static LoopContext context(ChatAgent agent, List<Message> initialMessages, String latestResponse) {
        AgentResponse<Void> response = AgentResponse.<Void>builder()
                .messages(List.of(Message.text(Role.ASSISTANT, latestResponse)))
                .responseId("response")
                .agentId("agent")
                .createdAt(Instant.EPOCH)
                .finishReason(FinishReason.STOP)
                .build();
        return new LoopContext(
                agent,
                new AgentSession("loop-evaluator-session"),
                initialMessages,
                response,
                RunOptions.empty(),
                1,
                List.of(),
                List.of(),
                new java.util.concurrent.ConcurrentHashMap<>());
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder()
                .messages(List.of(Message.text(Role.ASSISTANT, text)))
                .responseId("judge-response")
                .createdAt(Instant.EPOCH)
                .finishReason(FinishReason.STOP)
                .build();
    }

    private static final class RecordingJudgeClient implements ChatClient {
        private final ChatResponse response;

        private final AtomicReference<ChatClientRequest> request = new AtomicReference<>();

        private final AtomicReference<RunCancellation> cancellation = new AtomicReference<>();

        private RecordingJudgeClient(ChatResponse response) {
            this.response = response;
        }

        @Override
        public CompletionStage<ChatResponse> completeAsync(
                ChatClientRequest nextRequest, RunCancellation nextCancellation) {
            request.set(nextRequest);
            cancellation.set(nextCancellation);
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                ChatClientRequest nextRequest, RunCancellation nextCancellation) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {}
            });
        }
    }
}
