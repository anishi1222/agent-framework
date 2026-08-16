// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.AgentMiddlewareContext;
import com.microsoft.agents.agents.AgentRunContext;
import com.microsoft.agents.agents.MiddlewareMetadata;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunOptions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class PurviewPolicyMiddlewareTest {
    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT = "22222222-2222-2222-2222-222222222222";
    private static final String APP = "33333333-3333-3333-3333-333333333333";

    @Test
    void middleware_shouldShortCircuitReturnedBlockEvenInFailOpenMode() {
        PurviewPolicyEvaluator evaluator = mock(PurviewPolicyEvaluator.class);
        when(evaluator.evaluateAsync(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedStage(new PurviewEvaluationOutcome(
                        new PurviewDecision(
                                true,
                                false,
                                List.of(new PurviewPolicyAction("notifyUserAction", "block")),
                                "request-one"),
                        USER)));
        PurviewPolicyMiddleware<Void> middleware =
                new PurviewPolicyMiddleware<>(settings(PurviewFailureMode.FAIL_OPEN), evaluator);
        AtomicBoolean invoked = new AtomicBoolean();

        AgentResponse<Void> response = middleware
                .invokeAsync(context(), ignored -> {
                    invoked.set(true);
                    return CompletableFuture.completedStage(response("unsafe"));
                })
                .toCompletableFuture()
                .join();

        assertThat(invoked).isFalse();
        assertThat(response.finishReason()).isEqualTo(FinishReason.CONTENT_FILTER);
        assertThat(response.text()).isEqualTo("Prompt blocked by policy");
    }

    @Test
    void middleware_shouldAllowReturnedAllowWarnAndUnknownFutureActions() {
        PurviewPolicyEvaluator evaluator = mock(PurviewPolicyEvaluator.class);
        when(evaluator.evaluateAsync(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedStage(new PurviewEvaluationOutcome(
                        new PurviewDecision(
                                false,
                                false,
                                List.of(
                                        new PurviewPolicyAction("allow", null),
                                        new PurviewPolicyAction("warn", null),
                                        new PurviewPolicyAction("futureAction", "futureRestriction")),
                                "request-one"),
                        USER)));
        PurviewPolicyMiddleware<Void> middleware =
                new PurviewPolicyMiddleware<>(settings(PurviewFailureMode.FAIL_CLOSED), evaluator);

        AgentResponse<Void> response = middleware
                .invokeAsync(context(), ignored -> CompletableFuture.completedStage(response("safe")))
                .toCompletableFuture()
                .join();

        assertThat(response.text()).isEqualTo("safe");
        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
    }

    @Test
    void middleware_shouldHonorExplicitFailOpenAndFailClosed() {
        PurviewPolicyEvaluator evaluator = mock(PurviewPolicyEvaluator.class);
        when(evaluator.evaluateAsync(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedStage(new PurviewException(
                        "service unavailable",
                        null,
                        PurviewException.Kind.SERVICE,
                        503,
                        "request",
                        "unavailable",
                        null)));
        PurviewPolicyMiddleware<Void> open =
                new PurviewPolicyMiddleware<>(settings(PurviewFailureMode.FAIL_OPEN), evaluator);
        PurviewPolicyMiddleware<Void> closed =
                new PurviewPolicyMiddleware<>(settings(PurviewFailureMode.FAIL_CLOSED), evaluator);

        AgentResponse<Void> allowed = open.invokeAsync(
                        context(), ignored -> CompletableFuture.completedStage(response("safe")))
                .toCompletableFuture()
                .join();

        assertThat(allowed.text()).isEqualTo("safe");
        assertThatThrownBy(() -> closed.invokeAsync(
                                context(), ignored -> CompletableFuture.completedStage(response("safe")))
                        .toCompletableFuture()
                        .join())
                .hasRootCauseInstanceOf(PurviewException.class);
    }

    @SuppressWarnings("unchecked")
    private static AgentMiddlewareContext<Void> context() {
        Agent<Void> agent = mock(Agent.class);
        when(agent.metadata()).thenReturn(new AgentMetadata("agent-one", "Agent", "test"));
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        AgentRunContext run = new AgentRunContext(
                "run-one",
                agent.metadata(),
                Instant.now(),
                List.of(Message.text(Role.USER, "secret")),
                RunOptions.empty(),
                cancellation,
                Map.of());
        return new AgentMiddlewareContext<>(agent, run, new MiddlewareMetadata());
    }

    private static AgentResponse<Void> response(String text) {
        return AgentResponse.<Void>builder()
                .messages(List.of(Message.text(Role.ASSISTANT, text)))
                .finishReason(FinishReason.STOP)
                .build();
    }

    private static PurviewSettings settings(PurviewFailureMode mode) {
        return PurviewSettings.builder()
                .authenticationProvider((request, cancellation) ->
                        CompletableFuture.failedStage(new AssertionError("mock evaluator should be used")))
                .appName("Test")
                .appVersion("1")
                .tenantId(TENANT)
                .appLocation(new PurviewAppLocation(PurviewLocationType.APPLICATION, APP))
                .failureMode(mode)
                .build();
    }
}
