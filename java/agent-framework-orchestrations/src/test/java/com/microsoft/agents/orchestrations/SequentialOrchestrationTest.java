// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class SequentialOrchestrationTest {
    @Test
    void run_shouldPreserveOrderApplyTransformAndAvoidDuplicateMessages() {
        // Arrange
        CopyOnWriteArrayList<String> order = new CopyOnWriteArrayList<>();
        TestAgent first = new TestAgent("first", 40, (messages, options, invocation) -> {
            order.add("first");
            return TestAgent.response("first", "draft");
        });
        TestAgent second = new TestAgent("second", 0, (messages, options, invocation) -> {
            order.add("second");
            assertThat(messages).singleElement().extracting(Message::text).isEqualTo("transform:draft");
            return TestAgent.response("second", "review");
        });
        TestAgent third = new TestAgent("third", 0, (messages, options, invocation) -> {
            order.add("third");
            assertThat(messages).singleElement().extracting(Message::text).isEqualTo("transform:review");
            return TestAgent.response("third", "final");
        });
        SequentialOrchestration orchestration = SequentialOrchestration.builder(List.of(
                        OrchestrationParticipant.of(first),
                        OrchestrationParticipant.of(second),
                        OrchestrationParticipant.of(third)))
                .inputTransform(context -> List.of(Message.text(
                        Role.USER, "transform:" + context.previousResponse().text())))
                .build();

        // Act
        OrchestrationResult<AgentResponse<?>> result = orchestration.run(
                "request",
                OrchestrationRunOptions.builder().runId("sequential-order").build());

        // Assert
        assertThat(order).containsExactly("first", "second", "third");
        assertThat(result.outcome()).isEqualTo(OrchestrationOutcome.COMPLETED);
        assertThat(result.output().text()).isEqualTo("final");
        assertThat(result.participantResults())
                .extracting(ParticipantResult::participantId)
                .containsExactly("first", "second", "third");
        assertThat(result.transcript())
                .extracting(Message::text)
                .containsExactly("request", "draft", "review", "final");
        assertThat(result.events())
                .extracting(OrchestrationEvent::sequence)
                .containsExactlyElementsOf(
                        java.util.stream.LongStream.range(0, result.events().size())
                                .boxed()
                                .toList());
        assertThat(result.events())
                .allSatisfy(event -> assertThat(event.eventId()).startsWith("sequential-order:event:"));

        orchestration.close();
        first.close();
        second.close();
        third.close();
    }

    @Test
    void previousResponsePolicy_shouldPassOnlyReassignedPriorAgentOutput() {
        // Arrange
        TestAgent writer = TestAgent.responding("writer", "draft");
        TestAgent reviewer = new TestAgent("reviewer", 0, (messages, options, invocation) -> {
            assertThat(messages).hasSize(1);
            assertThat(messages.getFirst().role()).isEqualTo(Role.USER);
            assertThat(messages.getFirst().authorName()).isEqualTo("writer");
            return TestAgent.response("reviewer", "approved");
        });
        SequentialOrchestration orchestration = SequentialOrchestration.builder(
                        List.of(OrchestrationParticipant.of(writer), OrchestrationParticipant.of(reviewer)))
                .historyPolicy(SequentialHistoryPolicy.PREVIOUS_RESPONSE)
                .build();

        // Act
        OrchestrationResult<AgentResponse<?>> result = orchestration.run("request");

        // Assert
        assertThat(result.output().text()).isEqualTo("approved");
        assertThat(result.transcript()).extracting(Message::text).containsExactly("request", "draft", "approved");

        orchestration.close();
        writer.close();
        reviewer.close();
    }

    @Test
    void continuePolicy_shouldRetainErrorsAndCompleteWithLaterOutput() {
        // Arrange
        TestAgent first = TestAgent.responding("first", "one");
        TestAgent failing = new TestAgent("failing", 0, (messages, options, invocation) -> {
            throw new IllegalStateException("expected failure");
        });
        TestAgent last = TestAgent.responding("last", "three");
        SequentialOrchestration orchestration = SequentialOrchestration.builder(List.of(
                        OrchestrationParticipant.of(first),
                        OrchestrationParticipant.of(failing),
                        OrchestrationParticipant.of(last)))
                .failurePolicy(SequentialFailurePolicy.CONTINUE)
                .build();

        // Act
        OrchestrationResult<AgentResponse<?>> result = orchestration.run("request");

        // Assert
        assertThat(result.outcome()).isEqualTo(OrchestrationOutcome.COMPLETED_WITH_ERRORS);
        assertThat(result.output().text()).isEqualTo("three");
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.participantId()).isEqualTo("failing");
            assertThat(error.message()).contains("expected failure");
        });
        assertThat(result.participantResults())
                .extracting(ParticipantResult::status)
                .containsExactly(ParticipantStatus.COMPLETED, ParticipantStatus.FAILED, ParticipantStatus.COMPLETED);

        orchestration.close();
        first.close();
        failing.close();
        last.close();
    }

    @Test
    void stopPolicy_shouldFailAndMarkRemainingParticipantsSkipped() {
        // Arrange
        TestAgent failing = new TestAgent("failing", 0, (messages, options, invocation) -> {
            throw new IllegalArgumentException("stop");
        });
        TestAgent never = TestAgent.responding("never", "unexpected");
        SequentialOrchestration orchestration = SequentialOrchestration.builder(
                        List.of(OrchestrationParticipant.of(failing), OrchestrationParticipant.of(never)))
                .failurePolicy(SequentialFailurePolicy.STOP)
                .build();

        // Act
        OrchestrationResult<AgentResponse<?>> result = orchestration.run("request");

        // Assert
        assertThat(result.outcome()).isEqualTo(OrchestrationOutcome.FAILED);
        assertThat(result.terminationReason()).isEqualTo(OrchestrationTerminationReason.PARTICIPANT_FAILURE);
        assertThat(result.participantResults())
                .extracting(ParticipantResult::status)
                .containsExactly(ParticipantStatus.FAILED, ParticipantStatus.SKIPPED);
        assertThat(never.invocationCount()).isZero();

        orchestration.close();
        failing.close();
        never.close();
    }
}
