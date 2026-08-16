// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.ValidationException;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ConcurrentOrchestrationTest {
    @Test
    void run_shouldReturnDeclarationOrderIndependentOfCompletionTiming() {
        // Arrange
        TestAgent slow =
                new TestAgent("slow", 80, (messages, options, invocation) -> TestAgent.response("slow", "slow-result"));
        TestAgent fast =
                new TestAgent("fast", 0, (messages, options, invocation) -> TestAgent.response("fast", "fast-result"));
        TestAgent medium = new TestAgent(
                "medium", 30, (messages, options, invocation) -> TestAgent.response("medium", "medium-result"));
        ConcurrentOrchestration<List<AgentResponse<?>>> orchestration = ConcurrentOrchestration.builder(List.of(
                        OrchestrationParticipant.of(slow),
                        OrchestrationParticipant.of(fast),
                        OrchestrationParticipant.of(medium)))
                .build();

        // Act
        OrchestrationResult<List<AgentResponse<?>>> result = orchestration.run("request");

        // Assert
        assertThat(result.outcome()).isEqualTo(OrchestrationOutcome.COMPLETED);
        assertThat(result.output())
                .extracting(AgentResponse::text)
                .containsExactly("slow-result", "fast-result", "medium-result");
        assertThat(result.participantResults())
                .extracting(ParticipantResult::participantId)
                .containsExactly("slow", "fast", "medium");
        assertThat(result.events())
                .filteredOn(event -> event.type() == OrchestrationEventType.PARTICIPANT_COMPLETED)
                .extracting(OrchestrationEvent::participantId)
                .containsExactly("slow", "fast", "medium");

        orchestration.close();
        slow.close();
        fast.close();
        medium.close();
    }

    @Test
    void collectErrors_shouldNotInvokeAggregatorOrMasqueradeAsSuccess() {
        // Arrange
        TestAgent successful = TestAgent.responding("successful", "value");
        TestAgent failing = new TestAgent("failing", 0, (messages, options, invocation) -> {
            throw new IllegalStateException("broken");
        });
        AtomicBoolean aggregated = new AtomicBoolean();
        ConcurrentOrchestration<String> orchestration = ConcurrentOrchestration.builder(
                        List.of(OrchestrationParticipant.of(successful), OrchestrationParticipant.of(failing)),
                        results -> {
                            aggregated.set(true);
                            return "partial";
                        })
                .failurePolicy(ConcurrentFailurePolicy.COLLECT_ERRORS)
                .build();

        // Act
        OrchestrationResult<String> result = orchestration.run("request");

        // Assert
        assertThat(result.outcome()).isEqualTo(OrchestrationOutcome.FAILED);
        assertThat(result.terminationReason()).isEqualTo(OrchestrationTerminationReason.COLLECTED_ERRORS);
        assertThat(result.output()).isNull();
        assertThat(aggregated).isFalse();
        assertThat(result.participantResults())
                .extracting(ParticipantResult::status)
                .containsExactly(ParticipantStatus.COMPLETED, ParticipantStatus.FAILED);
        assertThat(result.errors())
                .singleElement()
                .extracting(OrchestrationError::participantId)
                .isEqualTo("failing");

        orchestration.close();
        successful.close();
        failing.close();
    }

    @Test
    void failFast_shouldCancelUnfinishedSiblings() throws Exception {
        // Arrange
        TestAgent failing = new TestAgent("failing", 5, (messages, options, invocation) -> {
            throw new IllegalArgumentException("fail fast");
        });
        TestAgent slow =
                new TestAgent("slow", 10_000, (messages, options, invocation) -> TestAgent.response("slow", "late"));
        ConcurrentOrchestration<List<AgentResponse<?>>> orchestration = ConcurrentOrchestration.builder(
                        List.of(OrchestrationParticipant.of(failing), OrchestrationParticipant.of(slow)))
                .failurePolicy(ConcurrentFailurePolicy.FAIL_FAST)
                .build();

        // Act
        OrchestrationResult<List<AgentResponse<?>>> result = orchestration.run("request");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!slow.cancellationObserved().get() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }

        // Assert
        assertThat(result.outcome()).isEqualTo(OrchestrationOutcome.FAILED);
        assertThat(result.participantResults())
                .extracting(ParticipantResult::status)
                .containsExactly(ParticipantStatus.FAILED, ParticipantStatus.SKIPPED);
        assertThat(slow.cancellationObserved()).isTrue();
        assertThat(result.events())
                .filteredOn(event -> event.type() == OrchestrationEventType.PARTICIPANT_SKIPPED)
                .extracting(OrchestrationEvent::participantId)
                .containsExactly("slow");
        assertThat(result.events())
                .filteredOn(event -> event.type() == OrchestrationEventType.RUN_TERMINATED)
                .singleElement()
                .extracting(OrchestrationEvent::participantId)
                .isNull();

        orchestration.close();
        failing.close();
        slow.close();
    }

    @Test
    void failFastStreaming_shouldEmitDeclarationOrderedSkippedEventsAndOneRunTerminal() {
        TestAgent failing = new TestAgent("failing", 5, (messages, options, invocation) -> {
            throw new IllegalStateException("fail");
        });
        TestAgent second = new TestAgent(
                "second", 10_000, (messages, options, invocation) -> TestAgent.response("second", "late"));
        TestAgent third =
                new TestAgent("third", 10_000, (messages, options, invocation) -> TestAgent.response("third", "late"));
        ConcurrentOrchestration<List<AgentResponse<?>>> orchestration = ConcurrentOrchestration.builder(List.of(
                        OrchestrationParticipant.of(failing),
                        OrchestrationParticipant.of(second),
                        OrchestrationParticipant.of(third)))
                .failurePolicy(ConcurrentFailurePolicy.FAIL_FAST)
                .build();
        TestEventSubscriber subscriber = new TestEventSubscriber(Long.MAX_VALUE);

        orchestration.runStreaming("request").subscribe(subscriber);
        List<OrchestrationEvent> events =
                subscriber.result().orTimeout(5, TimeUnit.SECONDS).join();

        assertThat(events)
                .filteredOn(event -> event.type() == OrchestrationEventType.PARTICIPANT_SKIPPED)
                .extracting(OrchestrationEvent::participantId)
                .containsExactly("second", "third");
        assertThat(events)
                .filteredOn(event -> event.type() == OrchestrationEventType.RUN_TERMINATED)
                .singleElement()
                .extracting(OrchestrationEvent::participantId)
                .isNull();
        assertThat(events.getLast().type()).isEqualTo(OrchestrationEventType.RUN_TERMINATED);
        assertThat(subscriber.terminalSignals()).hasValue(1);

        orchestration.close();
        failing.close();
        second.close();
        third.close();
    }

    @Test
    void callerExecutor_shouldRemainCallerOwnedAndSharedSessionsShouldBeRejected() {
        // Arrange
        TestAgent one = TestAgent.responding("one", "one");
        TestAgent two = TestAgent.responding("two", "two");
        ExecutorService callerExecutor = Executors.newFixedThreadPool(2);
        ConcurrentOrchestration<List<AgentResponse<?>>> orchestration = ConcurrentOrchestration.builder(
                        List.of(OrchestrationParticipant.of(one), OrchestrationParticipant.of(two)))
                .build();
        OrchestrationRunOptions options = OrchestrationRunOptions.builder()
                .participantExecutor(callerExecutor)
                .sessionPolicy(OrchestrationSessionPolicy.ISOLATED)
                .build();

        // Act
        OrchestrationResult<List<AgentResponse<?>>> result = orchestration.run("request", options);
        orchestration.close();

        // Assert
        assertThat(result.outcome()).isEqualTo(OrchestrationOutcome.COMPLETED);
        assertThat(callerExecutor.isShutdown()).isFalse();
        assertThat(java.util.concurrent.CompletableFuture.supplyAsync(() -> "still-owned", callerExecutor)
                        .join())
                .isEqualTo("still-owned");

        ConcurrentOrchestration<List<AgentResponse<?>>> invalid = ConcurrentOrchestration.builder(
                        List.of(OrchestrationParticipant.of(one), OrchestrationParticipant.of(two)))
                .build();
        assertThatThrownBy(() -> invalid.runAsync(
                                "request",
                                OrchestrationRunOptions.builder()
                                        .sessionPolicy(OrchestrationSessionPolicy.SHARED)
                                        .build())
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ValidationException.class);

        invalid.close();
        callerExecutor.close();
        one.close();
        two.close();
    }
}
