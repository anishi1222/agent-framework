// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.core.AgentResponse;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MagenticOrchestrationTest {
    @Test
    void manager_shouldPlanAssignAssessAndSynthesizeDeterministically() {
        // Arrange
        TestAgent researcher = TestAgent.responding("researcher", "facts");
        TestAgent writer = TestAgent.responding("writer", "draft");
        ScriptedMagenticManager manager = new ScriptedMagenticManager(
                List.of(new MagenticPlan(
                        0,
                        "research then write",
                        List.of(
                                MagenticTask.pending("research", "Find facts", "researcher"),
                                MagenticTask.pending("write", "Write answer", "writer")))),
                List.of(
                        new MagenticProgressAssessment(
                                false, true, false, "writer", "Use the gathered facts.", "Research completed."),
                        new MagenticProgressAssessment(
                                true, true, false, null, null, "The draft satisfies the request.")),
                TestAgent.response("manager", "final answer"));
        MagenticOrchestration orchestration = MagenticOrchestration.builder(
                        List.of(OrchestrationParticipant.of(researcher), OrchestrationParticipant.of(writer)), manager)
                .build();

        // Act
        OrchestrationResult<MagenticResult> result = orchestration.run("prepare report");

        // Assert
        assertThat(result.outcome()).isEqualTo(OrchestrationOutcome.COMPLETED);
        assertThat(result.output().response().text()).isEqualTo("final answer");
        assertThat(result.participantResults())
                .extracting(ParticipantResult::participantId)
                .containsExactly("researcher", "writer");
        assertThat(result.output().ledger().plan().tasks())
                .extracting(MagenticTask::status)
                .containsExactly(MagenticTaskStatus.COMPLETED, MagenticTaskStatus.COMPLETED);
        assertThat(result.output().ledger().assessments()).hasSize(2);
        assertThat(result.events())
                .filteredOn(event -> event.type() == OrchestrationEventType.PROGRESS_ASSESSED)
                .hasSize(2);

        orchestration.close();
        researcher.close();
        writer.close();
    }

    @Test
    void stallDetection_shouldReplanThenReturnExplicitUnsolvedOutcome() {
        // Arrange
        TestAgent first = TestAgent.responding("first", "blocked");
        TestAgent second = TestAgent.responding("second", "still blocked");
        ScriptedMagenticManager manager = new ScriptedMagenticManager(
                List.of(
                        new MagenticPlan(0, "first plan", List.of(MagenticTask.pending("one", "Try first", "first"))),
                        new MagenticPlan(
                                1, "different plan", List.of(MagenticTask.pending("two", "Try second", "second")))),
                List.of(
                        new MagenticProgressAssessment(false, false, true, "first", "Try again.", "No progress."),
                        new MagenticProgressAssessment(false, false, true, "second", "Try again.", "Still stalled.")),
                TestAgent.response("manager", "unused"));
        MagenticOrchestration orchestration = MagenticOrchestration.builder(
                        List.of(OrchestrationParticipant.of(first), OrchestrationParticipant.of(second)), manager)
                .maxStalls(1)
                .maxReplans(1)
                .build();

        // Act
        OrchestrationResult<MagenticResult> result = orchestration.run("solve hard problem");

        // Assert
        assertThat(result.outcome()).isEqualTo(OrchestrationOutcome.UNSOLVED);
        assertThat(result.terminationReason()).isEqualTo(OrchestrationTerminationReason.STALLED);
        assertThat(result.output().response()).isNull();
        assertThat(result.output().ledger().replanCount()).isOne();
        assertThat(result.output().ledger().plan().revision()).isOne();
        assertThat(result.events())
                .filteredOn(event -> event.type() == OrchestrationEventType.PLAN_UPDATED)
                .hasSize(2);

        orchestration.close();
        first.close();
        second.close();
    }

    @Test
    void iterationLimitAndUnknownAssignments_shouldBeExplicit() {
        // Arrange
        TestAgent worker = TestAgent.responding("worker", "increment");
        ScriptedMagenticManager boundedManager = new ScriptedMagenticManager(
                List.of(new MagenticPlan(0, "loop", List.of(MagenticTask.pending("work", "Continue", "worker")))),
                List.of(
                        new MagenticProgressAssessment(false, true, false, "worker", "Continue.", "Progress."),
                        new MagenticProgressAssessment(false, true, false, "worker", "Continue.", "Progress.")),
                TestAgent.response("manager", "unused"));
        MagenticOrchestration bounded = MagenticOrchestration.builder(
                        List.of(OrchestrationParticipant.of(worker)), boundedManager)
                .maxIterations(2)
                .build();

        // Act
        OrchestrationResult<MagenticResult> boundedResult = bounded.run("work");

        // Assert
        assertThat(boundedResult.outcome()).isEqualTo(OrchestrationOutcome.UNSOLVED);
        assertThat(boundedResult.terminationReason()).isEqualTo(OrchestrationTerminationReason.MAX_ITERATIONS);
        assertThat(boundedResult.turns()).isEqualTo(2);

        ScriptedMagenticManager invalidManager = new ScriptedMagenticManager(
                List.of(new MagenticPlan(
                        0, "invalid", List.of(MagenticTask.pending("bad", "Bad assignment", "missing")))),
                List.of(),
                TestAgent.response("manager", "unused"));
        MagenticOrchestration invalid = MagenticOrchestration.builder(
                        List.of(OrchestrationParticipant.of(worker)), invalidManager)
                .build();
        OrchestrationResult<MagenticResult> invalidResult = invalid.run("work");
        assertThat(invalidResult.outcome()).isEqualTo(OrchestrationOutcome.FAILED);
        assertThat(invalidResult.terminationReason()).isEqualTo(OrchestrationTerminationReason.MANAGER_FAILURE);
        assertThat(invalidResult.errors())
                .singleElement()
                .extracting(OrchestrationError::message)
                .asString()
                .contains("unknown participant");

        bounded.close();
        invalid.close();
        worker.close();
    }

    @Test
    void requiredPlanReview_shouldPropagateInputRequiredContinuation() {
        // Arrange
        TestAgent worker = TestAgent.responding("worker", "unused");
        ScriptedMagenticManager manager = new ScriptedMagenticManager(
                List.of(new MagenticPlan(
                        0, "review this plan", List.of(MagenticTask.pending("task", "Do work", "worker")))),
                List.of(),
                TestAgent.response("manager", "unused"));
        MagenticOrchestration orchestration = MagenticOrchestration.builder(
                        List.of(OrchestrationParticipant.of(worker)), manager)
                .requirePlanReview(true)
                .build();

        // Act
        OrchestrationResult<MagenticResult> result = orchestration.run("work");

        // Assert
        assertThat(result.outcome()).isEqualTo(OrchestrationOutcome.INPUT_REQUIRED);
        assertThat(result.continuation().kind()).isEqualTo(OrchestrationContinuationKind.PLAN_REVIEW);
        assertThat(result.continuation().prompt()).isEqualTo("review this plan");
        assertThat(worker.invocationCount()).isZero();

        orchestration.close();
        worker.close();
    }

    private static final class ScriptedMagenticManager implements MagenticManager {
        private final ArrayDeque<MagenticPlan> plans;

        private final ArrayDeque<MagenticProgressAssessment> assessments;

        private final AgentResponse<?> finalResponse;

        private final AtomicInteger planCalls = new AtomicInteger();

        private ScriptedMagenticManager(
                List<MagenticPlan> plans,
                List<MagenticProgressAssessment> assessments,
                AgentResponse<?> finalResponse) {
            this.plans = new ArrayDeque<>(plans);
            this.assessments = new ArrayDeque<>(assessments);
            this.finalResponse = finalResponse;
        }

        @Override
        public CompletionStage<MagenticPlan> planAsync(MagenticContext context) {
            planCalls.incrementAndGet();
            return CompletableFuture.completedFuture(plans.removeFirst());
        }

        @Override
        public CompletionStage<MagenticPlan> replanAsync(MagenticContext context) {
            return CompletableFuture.completedFuture(plans.removeFirst());
        }

        @Override
        public CompletionStage<MagenticProgressAssessment> assessProgressAsync(MagenticContext context) {
            return CompletableFuture.completedFuture(assessments.removeFirst());
        }

        @Override
        public CompletionStage<AgentResponse<?>> prepareFinalAnswerAsync(MagenticContext context) {
            return CompletableFuture.completedFuture(finalResponse);
        }
    }
}
