// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HandoffOrchestrationTest {
    @Test
    void functionContract_shouldRouteOnlyToRegisteredTargetAndPreserveHistory() {
        // Arrange
        TestAgent triage = new TestAgent(
                "triage",
                0,
                (messages, options, invocation) -> directiveResponse(
                        "triage",
                        "handoff",
                        StateValue.object(Map.of(
                                "target", StateValue.string("billing"),
                                "reason", StateValue.string("billing question")))));
        TestAgent billing = new TestAgent("billing", 0, (messages, options, invocation) -> {
            assertThat(messages).extracting(Message::text).contains("refund request");
            return TestAgent.response("billing", "refund approved");
        });
        HandoffOrchestration orchestration = HandoffOrchestration.builder(
                        List.of(OrchestrationParticipant.of(triage), OrchestrationParticipant.of(billing)))
                .startParticipant("triage")
                .allowHandoff("triage", "billing")
                .build();

        // Act
        OrchestrationResult<AgentResponse<?>> result = orchestration.run("refund request");

        // Assert
        assertThat(result.outcome()).isEqualTo(OrchestrationOutcome.COMPLETED);
        assertThat(result.output().text()).isEqualTo("refund approved");
        assertThat(result.participantResults())
                .extracting(ParticipantResult::participantId)
                .containsExactly("triage", "billing");
        assertThat(result.events())
                .filteredOn(event -> event.type() == OrchestrationEventType.HANDOFF)
                .singleElement()
                .extracting(OrchestrationEvent::participantId)
                .isEqualTo("triage");

        orchestration.close();
        triage.close();
        billing.close();
    }

    @Test
    void naturalLanguageWithoutFunctionContract_shouldNotTriggerHandoff() {
        // Arrange
        TestAgent triage = TestAgent.responding("triage", "handoff to billing");
        TestAgent billing = TestAgent.responding("billing", "unexpected");
        HandoffOrchestration orchestration = HandoffOrchestration.builder(
                        List.of(OrchestrationParticipant.of(triage), OrchestrationParticipant.of(billing)))
                .build();

        // Act
        OrchestrationResult<AgentResponse<?>> result = orchestration.run("request");

        // Assert
        assertThat(result.output().text()).isEqualTo("handoff to billing");
        assertThat(billing.invocationCount()).isZero();

        orchestration.close();
        triage.close();
        billing.close();
    }

    @Test
    void unknownTarget_shouldProduceExplicitFailedOutcome() {
        // Arrange
        TestAgent triage = new TestAgent(
                "triage",
                0,
                (messages, options, invocation) -> directiveResponse(
                        "triage", "handoff", StateValue.object(Map.of("target", StateValue.string("unknown")))));
        HandoffOrchestration orchestration = HandoffOrchestration.builder(List.of(OrchestrationParticipant.of(triage)))
                .unknownTargetPolicy(HandoffViolationPolicy.FAIL)
                .build();

        // Act
        OrchestrationResult<AgentResponse<?>> result = orchestration.run("request");

        // Assert
        assertThat(result.outcome()).isEqualTo(OrchestrationOutcome.FAILED);
        assertThat(result.terminationReason()).isEqualTo(OrchestrationTerminationReason.HANDOFF_REJECTED);
        assertThat(result.errors())
                .singleElement()
                .extracting(OrchestrationError::message)
                .asString()
                .contains("not registered");

        orchestration.close();
        triage.close();
    }

    @Test
    void selfAndLoopPolicies_shouldRejectInvalidRoutesDeterministically() {
        // Arrange
        TestAgent first = new TestAgent(
                "first",
                0,
                (messages, options, invocation) -> directiveResponse(
                        "first", "handoff", StateValue.object(Map.of("target", StateValue.string("second")))));
        TestAgent second = new TestAgent(
                "second",
                0,
                (messages, options, invocation) -> directiveResponse(
                        "second", "handoff", StateValue.object(Map.of("target", StateValue.string("first")))));
        HandoffOrchestration loop = HandoffOrchestration.builder(
                        List.of(OrchestrationParticipant.of(first), OrchestrationParticipant.of(second)))
                .loopPolicy(HandoffViolationPolicy.TERMINATE)
                .build();

        // Act
        OrchestrationResult<AgentResponse<?>> loopResult = loop.run("request");

        // Assert
        assertThat(loopResult.outcome()).isEqualTo(OrchestrationOutcome.TERMINATED);
        assertThat(loopResult.terminationReason()).isEqualTo(OrchestrationTerminationReason.HANDOFF_REJECTED);
        assertThat(loopResult.participantResults())
                .extracting(ParticipantResult::participantId)
                .containsExactly("first", "second");

        TestAgent selfAgent = new TestAgent(
                "self",
                0,
                (messages, options, invocation) -> directiveResponse(
                        "self", "handoff", StateValue.object(Map.of("target", StateValue.string("self")))));
        HandoffOrchestration self = HandoffOrchestration.builder(List.of(OrchestrationParticipant.of(selfAgent)))
                .selfHandoffPolicy(HandoffViolationPolicy.FAIL)
                .build();
        assertThat(self.run("request").outcome()).isEqualTo(OrchestrationOutcome.FAILED);

        loop.close();
        self.close();
        first.close();
        second.close();
        selfAgent.close();
    }

    @Test
    void disallowedTransitionPolicy_shouldRemainIndependentFromUnknownTargetPolicy() {
        TestAgent disallowedSource = new TestAgent(
                "source",
                0,
                (messages, options, invocation) -> directiveResponse(
                        "source", "handoff", StateValue.object(Map.of("target", StateValue.string("blocked")))));
        TestAgent allowed = TestAgent.responding("allowed", "allowed");
        TestAgent blocked = TestAgent.responding("blocked", "blocked");
        HandoffOrchestration disallowed = HandoffOrchestration.builder(List.of(
                        OrchestrationParticipant.of(disallowedSource),
                        OrchestrationParticipant.of(allowed),
                        OrchestrationParticipant.of(blocked)))
                .allowHandoff("source", "allowed")
                .unknownTargetPolicy(HandoffViolationPolicy.IGNORE)
                .disallowedTransitionPolicy(HandoffViolationPolicy.TERMINATE)
                .maxTurns(2)
                .build();

        OrchestrationResult<AgentResponse<?>> disallowedResult = disallowed.run("request");

        assertThat(disallowedResult.outcome()).isEqualTo(OrchestrationOutcome.TERMINATED);
        assertThat(disallowedResult.terminationReason()).isEqualTo(OrchestrationTerminationReason.HANDOFF_REJECTED);
        assertThat(disallowedSource.invocationCount()).isOne();
        assertThat(blocked.invocationCount()).isZero();

        TestAgent unknownSource = new TestAgent(
                "unknown-source",
                0,
                (messages, options, invocation) -> directiveResponse(
                        "unknown-source",
                        "handoff",
                        StateValue.object(Map.of("target", StateValue.string("missing")))));
        HandoffOrchestration unknown = HandoffOrchestration.builder(List.of(OrchestrationParticipant.of(unknownSource)))
                .unknownTargetPolicy(HandoffViolationPolicy.FAIL)
                .disallowedTransitionPolicy(HandoffViolationPolicy.IGNORE)
                .build();
        assertThat(unknown.run("request").outcome()).isEqualTo(OrchestrationOutcome.FAILED);

        disallowed.close();
        unknown.close();
        disallowedSource.close();
        unknownSource.close();
        allowed.close();
        blocked.close();
    }

    @Test
    void inputFunctionAndTurnLimit_shouldProduceExplicitContinuationsAndBounds() {
        // Arrange
        TestAgent human = new TestAgent(
                "human",
                0,
                (messages, options, invocation) -> directiveResponse(
                        "human",
                        "request_human_input",
                        StateValue.object(Map.of("prompt", StateValue.string("Provide an account number.")))));
        HandoffOrchestration inputRequired = HandoffOrchestration.builder(List.of(OrchestrationParticipant.of(human)))
                .build();

        // Act
        OrchestrationResult<AgentResponse<?>> suspended = inputRequired.run("request");

        // Assert
        assertThat(suspended.outcome()).isEqualTo(OrchestrationOutcome.INPUT_REQUIRED);
        assertThat(suspended.continuation()).isNotNull();
        assertThat(suspended.continuation().kind()).isEqualTo(OrchestrationContinuationKind.HUMAN_INPUT);
        assertThat(suspended.continuation().prompt()).contains("account number");

        TestAgent looping = new TestAgent(
                "looping",
                0,
                (messages, options, invocation) -> directiveResponse(
                        "looping", "handoff", StateValue.object(Map.of("target", StateValue.string("missing")))));
        HandoffOrchestration bounded = HandoffOrchestration.builder(List.of(OrchestrationParticipant.of(looping)))
                .unknownTargetPolicy(HandoffViolationPolicy.IGNORE)
                .maxTurns(2)
                .build();
        OrchestrationResult<AgentResponse<?>> boundedResult = bounded.run("request");
        assertThat(boundedResult.terminationReason()).isEqualTo(OrchestrationTerminationReason.MAX_TURNS);
        assertThat(boundedResult.turns()).isEqualTo(2);

        inputRequired.close();
        bounded.close();
        human.close();
        looping.close();
    }

    static AgentResponse<Void> directiveResponse(String agentId, String functionName, StateValue arguments) {
        return AgentResponse.<Void>builder()
                .agentId(agentId)
                .messages(List.of(new Message(
                        Role.ASSISTANT,
                        List.of(new FunctionCallContent(agentId + "-call", functionName, arguments)),
                        agentId,
                        agentId + "-directive",
                        Map.of())))
                .build();
    }
}
