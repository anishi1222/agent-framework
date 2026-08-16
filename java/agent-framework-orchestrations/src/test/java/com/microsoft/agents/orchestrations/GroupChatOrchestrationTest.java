// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.Message;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class GroupChatOrchestrationTest {
    @Test
    void roundRobin_shouldShareTranscriptAndStopAtTurnLimit() {
        // Arrange
        TestAgent alpha = new TestAgent(
                "alpha", 0, (messages, options, invocation) -> TestAgent.response("alpha", "alpha-" + invocation));
        TestAgent beta = new TestAgent(
                "beta", 0, (messages, options, invocation) -> TestAgent.response("beta", "beta-" + invocation));
        GroupChatOrchestration orchestration = GroupChatOrchestration.builder(
                        List.of(OrchestrationParticipant.of(alpha), OrchestrationParticipant.of(beta)))
                .maxTurns(4)
                .build();

        // Act
        OrchestrationResult<AgentResponse<?>> result = orchestration.run("topic");

        // Assert
        assertThat(result.outcome()).isEqualTo(OrchestrationOutcome.TERMINATED);
        assertThat(result.terminationReason()).isEqualTo(OrchestrationTerminationReason.MAX_TURNS);
        assertThat(result.participantResults())
                .extracting(ParticipantResult::participantId)
                .containsExactly("alpha", "beta", "alpha", "beta");
        assertThat(result.transcript())
                .extracting(Message::text)
                .containsExactly("topic", "alpha-0", "beta-0", "alpha-1", "beta-1");
        assertThat(alpha.inputs().get(1)).extracting(Message::text).contains("topic", "alpha-0", "beta-0");

        orchestration.close();
        alpha.close();
        beta.close();
    }

    @Test
    void managerSelection_shouldValidateRegisteredIdsTransitionsAndRepetition() {
        // Arrange
        TestAgent alpha = TestAgent.responding("alpha", "alpha");
        TestAgent beta = TestAgent.responding("beta", "beta");
        GroupChatManager unknown = context -> CompletableFuture.completedFuture(GroupChatDecision.select("unknown"));
        GroupChatOrchestration unknownSelection = GroupChatOrchestration.builder(
                        List.of(OrchestrationParticipant.of(alpha), OrchestrationParticipant.of(beta)))
                .manager(unknown)
                .build();

        // Act
        OrchestrationResult<AgentResponse<?>> unknownResult = unknownSelection.run("topic");

        // Assert
        assertThat(unknownResult.outcome()).isEqualTo(OrchestrationOutcome.FAILED);
        assertThat(unknownResult.terminationReason()).isEqualTo(OrchestrationTerminationReason.MANAGER_FAILURE);
        assertThat(unknownResult.errors())
                .singleElement()
                .extracting(OrchestrationError::message)
                .asString()
                .contains("unknown participant");

        GroupChatOrchestration repeated = GroupChatOrchestration.builder(
                        List.of(OrchestrationParticipant.of(alpha), OrchestrationParticipant.of(beta)))
                .manager(context -> CompletableFuture.completedFuture(GroupChatDecision.select("alpha")))
                .repetitionPolicy(SpeakerRepetitionPolicy.DISALLOW_CONSECUTIVE)
                .maxTurns(3)
                .build();
        OrchestrationResult<AgentResponse<?>> repeatedResult = repeated.run("topic");
        assertThat(repeatedResult.outcome()).isEqualTo(OrchestrationOutcome.FAILED);
        assertThat(repeatedResult.participantResults()).hasSize(1);

        ArrayDeque<String> choices = new ArrayDeque<>(List.of("alpha", "beta"));
        GroupChatOrchestration disallowedTransition = GroupChatOrchestration.builder(
                        List.of(OrchestrationParticipant.of(alpha), OrchestrationParticipant.of(beta)))
                .manager(context -> CompletableFuture.completedFuture(GroupChatDecision.select(choices.removeFirst())))
                .allowTransition("alpha", "alpha")
                .maxTurns(3)
                .build();
        OrchestrationResult<AgentResponse<?>> transitionResult = disallowedTransition.run("topic");
        assertThat(transitionResult.outcome()).isEqualTo(OrchestrationOutcome.FAILED);
        assertThat(transitionResult.errors())
                .singleElement()
                .extracting(OrchestrationError::message)
                .asString()
                .contains("not allowed");

        unknownSelection.close();
        repeated.close();
        disallowedTransition.close();
        alpha.close();
        beta.close();
    }

    @Test
    void agentBasedSelector_shouldAcceptOnlyExactRegisteredIds() {
        // Arrange
        TestAgent selectorAgent = TestAgent.responding("selector", "beta");
        TestAgent alpha = TestAgent.responding("alpha", "alpha");
        TestAgent beta = TestAgent.responding("beta", "beta");
        AgentBasedGroupChatSelector selector = new AgentBasedGroupChatSelector(selectorAgent);
        GroupChatOrchestration orchestration = GroupChatOrchestration.builder(
                        List.of(OrchestrationParticipant.of(alpha), OrchestrationParticipant.of(beta)))
                .agentBasedSelector(selector)
                .maxTurns(1)
                .build();

        // Act
        OrchestrationResult<AgentResponse<?>> result = orchestration.run("topic");

        // Assert
        assertThat(result.participantResults())
                .extracting(ParticipantResult::participantId)
                .containsExactly("beta");
        assertThat(beta.invocationCount()).isOne();
        assertThat(alpha.invocationCount()).isZero();

        TestAgent invalidSelectorAgent = TestAgent.responding("invalid-selector", "beta because it is best");
        GroupChatOrchestration invalid = GroupChatOrchestration.builder(
                        List.of(OrchestrationParticipant.of(alpha), OrchestrationParticipant.of(beta)))
                .agentBasedSelector(new AgentBasedGroupChatSelector(invalidSelectorAgent))
                .build();
        assertThat(invalid.run("topic").outcome()).isEqualTo(OrchestrationOutcome.FAILED);

        orchestration.close();
        invalid.close();
        selectorAgent.close();
        invalidSelectorAgent.close();
        alpha.close();
        beta.close();
    }

    @Test
    void terminationPredicateAndManagerFailure_shouldProduceExplicitOutcomes() {
        // Arrange
        TestAgent participant = TestAgent.responding("participant", "done");
        GroupChatOrchestration terminated = GroupChatOrchestration.builder(
                        List.of(OrchestrationParticipant.of(participant)))
                .terminationPredicate(context -> context.transcript().stream()
                        .anyMatch(message -> message.text().contains("done")))
                .maxTurns(5)
                .build();

        // Act
        OrchestrationResult<AgentResponse<?>> terminatedResult = terminated.run("topic");

        // Assert
        assertThat(terminatedResult.outcome()).isEqualTo(OrchestrationOutcome.TERMINATED);
        assertThat(terminatedResult.terminationReason()).isEqualTo(OrchestrationTerminationReason.PREDICATE_SATISFIED);
        assertThat(terminatedResult.output().text()).isEqualTo("done");

        GroupChatOrchestration managerFailure = GroupChatOrchestration.builder(
                        List.of(OrchestrationParticipant.of(participant)))
                .manager(context -> CompletableFuture.failedFuture(new IllegalStateException("manager unavailable")))
                .build();
        OrchestrationResult<AgentResponse<?>> failed = managerFailure.run("topic");
        assertThat(failed.outcome()).isEqualTo(OrchestrationOutcome.FAILED);
        assertThat(failed.errors())
                .singleElement()
                .extracting(OrchestrationError::message)
                .isEqualTo("manager unavailable");

        terminated.close();
        managerFailure.close();
        participant.close();
    }
}
