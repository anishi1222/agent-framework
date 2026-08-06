// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Executes bounded Magentic planning, assignment, progress assessment, and replanning.
 *
 * <p>All plans and assessments use framework-owned typed contracts. Assignments are validated
 * against the immutable participant registry, events and ledger history are deterministic, and
 * stalled or exhausted runs return explicit {@link OrchestrationOutcome#UNSOLVED} results.
 */
public final class MagenticOrchestration extends AbstractOrchestration<MagenticResult> {
    private final Map<String, OrchestrationParticipant> participantsById;

    private final MagenticManager manager;

    private final int maxIterations;

    private final int maxStalls;

    private final int maxReplans;

    private final boolean requirePlanReview;

    private MagenticOrchestration(Builder builder) {
        super(builder.resolvedId(), OrchestrationPattern.MAGENTIC, builder.participants, builder.continuationOptions);
        LinkedHashMap<String, OrchestrationParticipant> participantMap = new LinkedHashMap<>();
        for (OrchestrationParticipant participant : participants()) {
            participantMap.put(participant.id(), participant);
        }
        participantsById = Collections.unmodifiableMap(participantMap);
        manager = builder.manager;
        maxIterations = builder.maxIterations;
        maxStalls = builder.maxStalls;
        maxReplans = builder.maxReplans;
        requirePlanReview = builder.requirePlanReview;
    }

    /**
     * Creates a Magentic builder.
     *
     * @param participants registered participants
     * @param manager provider-neutral manager
     * @return builder
     */
    public static Builder builder(List<OrchestrationParticipant> participants, MagenticManager manager) {
        return new Builder(participants, manager);
    }

    /**
     * Returns the maximum bounded participant iteration count.
     *
     * @return maximum iterations
     */
    public int maxIterations() {
        return maxIterations;
    }

    @Override
    CompletionStage<OrchestrationResult<MagenticResult>> execute(
            OrchestrationExecutionContext<MagenticResult> execution, List<Message> input) {
        MagenticState state = new MagenticState(input);
        return createInitialPlan(execution, state);
    }

    private CompletionStage<OrchestrationResult<MagenticResult>> createInitialPlan(
            OrchestrationExecutionContext<MagenticResult> execution, MagenticState state) {
        CompletionStage<MagenticPlan> stage;
        try {
            stage = Objects.requireNonNull(
                    manager.planAsync(managerContext(execution, state)),
                    "Magentic manager returned a null planning stage.");
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(managerFailure(execution, state, failure));
        }
        return stage.handle((plan, failure) -> new ManagerOutcome<>(plan, failure))
                .thenCompose(outcome -> {
                    if (outcome.failure != null) {
                        return handleManagerFailure(execution, state, outcome.failure);
                    }
                    try {
                        state.plan = validatePlan(Objects.requireNonNull(outcome.value, "manager plan"));
                    } catch (RuntimeException failure) {
                        return CompletableFuture.completedFuture(managerFailure(execution, state, failure));
                    }
                    emitPlan(execution, state.plan, false, state.iteration);
                    if (requirePlanReview) {
                        return CompletableFuture.completedFuture(planReviewRequired(execution, state));
                    }
                    return runIteration(execution, state);
                });
    }

    private CompletionStage<OrchestrationResult<MagenticResult>> runIteration(
            OrchestrationExecutionContext<MagenticResult> execution, MagenticState state) {
        if (state.iteration >= maxIterations) {
            return CompletableFuture.completedFuture(
                    unsolved(execution, state, OrchestrationTerminationReason.MAX_ITERATIONS));
        }
        Assignment assignment = nextAssignment(state);
        if (assignment == null) {
            state.stallCount = maxStalls;
            return replanOrUnsolved(execution, state);
        }
        state.plan = updateTaskStatus(state.plan, assignment.taskId, MagenticTaskStatus.IN_PROGRESS);
        OrchestrationParticipant participant = participantsById.get(assignment.participantId);
        List<Message> participantInput = participantInput(state, assignment);
        int turn = state.iteration;
        return execution
                .invoke(participant, participantInput, turn)
                .thenCompose(participantResult -> handleParticipantResult(
                        execution, state, assignment, participant, participantInput, turn, participantResult, false));
    }

    private CompletionStage<OrchestrationResult<MagenticResult>> handleParticipantResult(
            OrchestrationExecutionContext<MagenticResult> execution,
            MagenticState state,
            Assignment assignment,
            OrchestrationParticipant participant,
            List<Message> participantInput,
            int turn,
            ParticipantResult participantResult,
            boolean resumed) {
        execution.emitParticipantResult(participantResult, turn, null);
        if (resumed) {
            state.results.set(turn, participantResult);
        } else {
            state.results.add(participantResult);
            state.iteration++;
        }
        return switch (participantResult.status()) {
            case COMPLETED -> {
                AgentResponse<?> response = participantResult.response().orElseThrow();
                state.transcript = OrchestrationMessages.appendResponse(state.transcript, participantInput, response);
                state.plan = updateTaskStatus(state.plan, assignment.taskId, MagenticTaskStatus.COMPLETED);
                yield assessProgress(execution, state);
            }
            case INPUT_REQUIRED ->
                CompletableFuture.completedFuture(approvalRequired(
                        execution, state, assignment, participant, participantInput, turn, participantResult));
            case FAILED -> {
                state.errors.add(participantResult.error().orElseThrow());
                state.plan = updateTaskStatus(state.plan, assignment.taskId, MagenticTaskStatus.FAILED);
                yield CompletableFuture.completedFuture(result(
                        execution,
                        state,
                        OrchestrationOutcome.FAILED,
                        OrchestrationTerminationReason.PARTICIPANT_FAILURE,
                        new MagenticResult(null, state.snapshot()),
                        null));
            }
            case SKIPPED -> throw new IllegalStateException("The runtime cannot invoke a skipped participant.");
        };
    }

    private CompletionStage<OrchestrationResult<MagenticResult>> assessProgress(
            OrchestrationExecutionContext<MagenticResult> execution, MagenticState state) {
        CompletionStage<MagenticProgressAssessment> stage;
        try {
            stage = Objects.requireNonNull(
                    manager.assessProgressAsync(managerContext(execution, state)),
                    "Magentic manager returned a null assessment stage.");
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(managerFailure(execution, state, failure));
        }
        return stage.handle((assessment, failure) -> new ManagerOutcome<>(assessment, failure))
                .thenCompose(outcome -> {
                    if (outcome.failure != null) {
                        return handleManagerFailure(execution, state, outcome.failure);
                    }
                    MagenticProgressAssessment assessment;
                    try {
                        assessment = validateAssessment(Objects.requireNonNull(outcome.value, "manager assessment"));
                    } catch (RuntimeException failure) {
                        return CompletableFuture.completedFuture(managerFailure(execution, state, failure));
                    }
                    state.assessments.add(assessment);
                    state.lastAssessment = assessment;
                    emitAssessment(execution, state, assessment);
                    if (assessment.requestSatisfied()) {
                        return prepareFinalAnswer(execution, state);
                    }
                    state.stallCount = assessment.stalled() || !assessment.progressMade() ? state.stallCount + 1 : 0;
                    if (state.stallCount >= maxStalls) {
                        return replanOrUnsolved(execution, state);
                    }
                    return runIteration(execution, state);
                });
    }

    private CompletionStage<OrchestrationResult<MagenticResult>> replanOrUnsolved(
            OrchestrationExecutionContext<MagenticResult> execution, MagenticState state) {
        if (state.replanCount >= maxReplans) {
            return CompletableFuture.completedFuture(
                    unsolved(execution, state, OrchestrationTerminationReason.STALLED));
        }
        CompletionStage<MagenticPlan> stage;
        try {
            stage = Objects.requireNonNull(
                    manager.replanAsync(managerContext(execution, state)),
                    "Magentic manager returned a null replanning stage.");
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(managerFailure(execution, state, failure));
        }
        return stage.handle((plan, failure) -> new ManagerOutcome<>(plan, failure))
                .thenCompose(outcome -> {
                    if (outcome.failure != null) {
                        return handleManagerFailure(execution, state, outcome.failure);
                    }
                    try {
                        MagenticPlan validated = validatePlan(Objects.requireNonNull(outcome.value, "manager replan"));
                        int revision = Math.max(state.plan.revision() + 1, validated.revision());
                        state.plan = new MagenticPlan(revision, validated.summary(), validated.tasks());
                    } catch (RuntimeException failure) {
                        return CompletableFuture.completedFuture(managerFailure(execution, state, failure));
                    }
                    state.replanCount++;
                    state.stallCount = 0;
                    state.lastAssessment = null;
                    emitPlan(execution, state.plan, true, state.iteration);
                    if (requirePlanReview) {
                        return CompletableFuture.completedFuture(planReviewRequired(execution, state));
                    }
                    return runIteration(execution, state);
                });
    }

    private CompletionStage<OrchestrationResult<MagenticResult>> prepareFinalAnswer(
            OrchestrationExecutionContext<MagenticResult> execution, MagenticState state) {
        CompletionStage<AgentResponse<?>> stage;
        try {
            stage = Objects.requireNonNull(
                    manager.prepareFinalAnswerAsync(managerContext(execution, state)),
                    "Magentic manager returned a null final-answer stage.");
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(managerFailure(execution, state, failure));
        }
        return stage.handle((response, failure) -> new ManagerOutcome<>(response, failure))
                .thenCompose(outcome -> {
                    if (outcome.failure != null) {
                        return handleManagerFailure(execution, state, outcome.failure);
                    }
                    AgentResponse<?> response =
                            Objects.requireNonNull(outcome.value, "Magentic manager final response");
                    state.transcript =
                            OrchestrationMessages.appendResponse(state.transcript, state.transcript, response);
                    MagenticResult output = new MagenticResult(response, state.snapshot());
                    return CompletableFuture.completedFuture(result(
                            execution,
                            state,
                            OrchestrationOutcome.COMPLETED,
                            OrchestrationTerminationReason.COMPLETED,
                            output,
                            null));
                });
    }

    private CompletionStage<OrchestrationResult<MagenticResult>> handleManagerFailure(
            OrchestrationExecutionContext<MagenticResult> execution, MagenticState state, Throwable failure) {
        Throwable cause = RunHandles.unwrap(failure);
        if (execution.cancellation().isCancellationRequested() || cause instanceof RunCancelledException) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        return CompletableFuture.completedFuture(managerFailure(execution, state, cause));
    }

    private OrchestrationResult<MagenticResult> managerFailure(
            OrchestrationExecutionContext<MagenticResult> execution, MagenticState state, Throwable failure) {
        state.errors.add(OrchestrationError.from(null, failure));
        return result(
                execution,
                state,
                OrchestrationOutcome.FAILED,
                OrchestrationTerminationReason.MANAGER_FAILURE,
                new MagenticResult(null, state.snapshot()),
                null);
    }

    private OrchestrationResult<MagenticResult> unsolved(
            OrchestrationExecutionContext<MagenticResult> execution,
            MagenticState state,
            OrchestrationTerminationReason reason) {
        return result(
                execution,
                state,
                OrchestrationOutcome.UNSOLVED,
                reason,
                new MagenticResult(null, state.snapshot()),
                null);
    }

    private OrchestrationResult<MagenticResult> planReviewRequired(
            OrchestrationExecutionContext<MagenticResult> execution, MagenticState state) {
        OrchestrationContinuation continuation = suspend(
                execution,
                OrchestrationContinuationKind.PLAN_REVIEW,
                null,
                null,
                state.transcript,
                state.plan.summary(),
                (resumedContext, input) -> {
                    OrchestrationResumeInput.PlanReview review = (OrchestrationResumeInput.PlanReview) input;
                    if (review.decision() == OrchestrationResumeInput.PlanDecision.APPROVE) {
                        return runIteration(resumedContext, state);
                    }
                    if (review.feedback() != null) {
                        ArrayList<Message> transcript = new ArrayList<>(state.transcript);
                        transcript.add(Message.builder(Role.USER)
                                .contents(List.of(new com.microsoft.agents.core.TextContent(review.feedback())))
                                .authorName("magentic-plan-reviewer")
                                .build());
                        state.transcript = List.copyOf(transcript);
                    }
                    state.stallCount = maxStalls;
                    return replanOrUnsolved(resumedContext, state);
                });
        return result(
                execution,
                state,
                OrchestrationOutcome.INPUT_REQUIRED,
                OrchestrationTerminationReason.INPUT_REQUIRED,
                new MagenticResult(null, state.snapshot()),
                continuation);
    }

    private OrchestrationResult<MagenticResult> approvalRequired(
            OrchestrationExecutionContext<MagenticResult> execution,
            MagenticState state,
            Assignment assignment,
            OrchestrationParticipant participant,
            List<Message> participantInput,
            int turn,
            ParticipantResult participantResult) {
        com.microsoft.agents.agents.AgentContinuation agentContinuation =
                participantResult.agentContinuation().orElseThrow();
        OrchestrationContinuation continuation = suspend(
                execution,
                OrchestrationContinuationKind.APPROVAL,
                participantResult.participantId(),
                agentContinuation,
                state.transcript,
                "Magentic participant '" + participantResult.participantId() + "' requires approval.",
                (resumedContext, input) -> resumedContext
                        .resumeApproval(
                                participant,
                                agentContinuation,
                                ((OrchestrationResumeInput.Approval) input).decisions(),
                                turn)
                        .thenCompose(resumedResult -> handleParticipantResult(
                                resumedContext,
                                state,
                                assignment,
                                participant,
                                participantInput,
                                turn,
                                resumedResult,
                                true)));
        return result(
                execution,
                state,
                OrchestrationOutcome.INPUT_REQUIRED,
                OrchestrationTerminationReason.INPUT_REQUIRED,
                new MagenticResult(null, state.snapshot()),
                continuation);
    }

    private static OrchestrationResult<MagenticResult> result(
            OrchestrationExecutionContext<MagenticResult> execution,
            MagenticState state,
            OrchestrationOutcome outcome,
            OrchestrationTerminationReason reason,
            MagenticResult output,
            OrchestrationContinuation continuation) {
        return new OrchestrationResult<>(
                execution.runId(),
                outcome,
                reason,
                output,
                state.results,
                state.transcript,
                List.of(),
                state.errors,
                continuation,
                state.iteration);
    }

    private MagenticContext managerContext(
            OrchestrationExecutionContext<MagenticResult> execution, MagenticState state) {
        return new MagenticContext(
                participantsById,
                state.snapshot(),
                execution.cancellation(),
                execution.options().agentRunOptions(),
                execution.options().metadata());
    }

    private MagenticPlan validatePlan(MagenticPlan plan) {
        for (MagenticTask task : plan.tasks()) {
            if (!participantsById.containsKey(task.participantId())) {
                throw new ValidationException("Magentic task '" + task.id() + "' assigns unknown participant '"
                        + task.participantId() + "'.");
            }
        }
        return plan;
    }

    private MagenticProgressAssessment validateAssessment(MagenticProgressAssessment assessment) {
        if (assessment.nextParticipantId() != null && !participantsById.containsKey(assessment.nextParticipantId())) {
            throw new ValidationException(
                    "Magentic assessment assigns unknown participant '" + assessment.nextParticipantId() + "'.");
        }
        return assessment;
    }

    private static Assignment nextAssignment(MagenticState state) {
        if (state.lastAssessment != null && state.lastAssessment.nextParticipantId() != null) {
            String participantId = state.lastAssessment.nextParticipantId();
            MagenticTask task = state.plan.tasks().stream()
                    .filter(candidate -> candidate.participantId().equals(participantId))
                    .filter(candidate -> candidate.status() == MagenticTaskStatus.PENDING
                            || candidate.status() == MagenticTaskStatus.IN_PROGRESS)
                    .findFirst()
                    .orElse(null);
            String instruction = state.lastAssessment.instruction();
            if (instruction == null) {
                instruction = task == null ? "Continue making progress on the current request." : task.description();
            }
            return new Assignment(task == null ? null : task.id(), participantId, instruction);
        }
        return state.plan.tasks().stream()
                .filter(task ->
                        task.status() == MagenticTaskStatus.PENDING || task.status() == MagenticTaskStatus.IN_PROGRESS)
                .findFirst()
                .map(task -> new Assignment(task.id(), task.participantId(), task.description()))
                .orElse(null);
    }

    private static List<Message> participantInput(MagenticState state, Assignment assignment) {
        ArrayList<Message> messages = new ArrayList<>(OrchestrationMessages.textOnly(state.transcript));
        messages.add(Message.builder(Role.USER)
                .contents(List.of(new com.microsoft.agents.core.TextContent(assignment.instruction)))
                .authorName("magentic-manager")
                .build());
        return List.copyOf(messages);
    }

    private static MagenticPlan updateTaskStatus(MagenticPlan plan, String taskId, MagenticTaskStatus status) {
        if (taskId == null) {
            return plan;
        }
        ArrayList<MagenticTask> tasks = new ArrayList<>(plan.tasks().size());
        for (MagenticTask task : plan.tasks()) {
            tasks.add(task.id().equals(taskId) ? task.withStatus(status) : task);
        }
        return new MagenticPlan(plan.revision(), plan.summary(), tasks);
    }

    private static void emitPlan(
            OrchestrationExecutionContext<MagenticResult> execution, MagenticPlan plan, boolean replan, int turn) {
        execution.emit(
                OrchestrationEventType.PLAN_UPDATED,
                null,
                turn,
                null,
                StateValue.object(Map.of(
                        "revision", StateValue.integer(plan.revision()),
                        "replan", StateValue.bool(replan),
                        "summary", StateValue.string(plan.summary()),
                        "taskCount", StateValue.integer(plan.tasks().size()))));
    }

    private static void emitAssessment(
            OrchestrationExecutionContext<MagenticResult> execution,
            MagenticState state,
            MagenticProgressAssessment assessment) {
        LinkedHashMap<String, StateValue> data = new LinkedHashMap<>();
        data.put("requestSatisfied", StateValue.bool(assessment.requestSatisfied()));
        data.put("progressMade", StateValue.bool(assessment.progressMade()));
        data.put("stalled", StateValue.bool(assessment.stalled()));
        data.put("rationale", StateValue.string(assessment.rationale()));
        if (assessment.nextParticipantId() != null) {
            data.put("nextParticipantId", StateValue.string(assessment.nextParticipantId()));
        }
        execution.emit(
                OrchestrationEventType.PROGRESS_ASSESSED,
                assessment.nextParticipantId(),
                state.iteration - 1,
                null,
                StateValue.object(data));
    }

    /** Builds immutable {@link MagenticOrchestration} instances. */
    public static final class Builder {
        private static final int DEFAULT_MAX_ITERATIONS = 40;

        private static final int DEFAULT_MAX_STALLS = 3;

        private static final int DEFAULT_MAX_REPLANS = 3;

        private final List<OrchestrationParticipant> participants;

        private final MagenticManager manager;

        private String id;

        private int maxIterations = DEFAULT_MAX_ITERATIONS;

        private int maxStalls = DEFAULT_MAX_STALLS;

        private int maxReplans = DEFAULT_MAX_REPLANS;

        private boolean requirePlanReview;

        private OrchestrationContinuationOptions continuationOptions = OrchestrationContinuationOptions.defaults();

        private Builder(List<OrchestrationParticipant> participants, MagenticManager manager) {
            this.participants = OrchestrationValidation.copyParticipants(participants);
            this.manager = Objects.requireNonNull(manager, "manager");
        }

        /**
         * Sets the stable orchestration identifier.
         *
         * @param id identifier
         * @return this builder
         */
        public Builder id(String id) {
            this.id = OrchestrationValidation.requireId(id, "id");
            return this;
        }

        /**
         * Sets the positive maximum participant iteration count.
         *
         * @param maxIterations maximum iterations
         * @return this builder
         */
        public Builder maxIterations(int maxIterations) {
            if (maxIterations <= 0) {
                throw new IllegalArgumentException("maxIterations must be greater than zero.");
            }
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * Sets the positive consecutive-stall threshold.
         *
         * @param maxStalls stall threshold
         * @return this builder
         */
        public Builder maxStalls(int maxStalls) {
            if (maxStalls <= 0) {
                throw new IllegalArgumentException("maxStalls must be greater than zero.");
            }
            this.maxStalls = maxStalls;
            return this;
        }

        /**
         * Sets the non-negative maximum replan count.
         *
         * @param maxReplans maximum replans
         * @return this builder
         */
        public Builder maxReplans(int maxReplans) {
            if (maxReplans < 0) {
                throw new IllegalArgumentException("maxReplans must not be negative.");
            }
            this.maxReplans = maxReplans;
            return this;
        }

        /**
         * Sets whether every generated plan suspends for explicit review.
         *
         * @param requirePlanReview whether plan review is required
         * @return this builder
         */
        public Builder requirePlanReview(boolean requirePlanReview) {
            this.requirePlanReview = requirePlanReview;
            return this;
        }

        /**
         * Sets the process-local continuation retention bounds.
         *
         * @param continuationOptions continuation retention options
         * @return this builder
         */
        public Builder continuationOptions(OrchestrationContinuationOptions continuationOptions) {
            this.continuationOptions = Objects.requireNonNull(continuationOptions, "continuationOptions");
            return this;
        }

        /**
         * Creates the immutable Magentic orchestration.
         *
         * @return Magentic orchestration
         */
        public MagenticOrchestration build() {
            return new MagenticOrchestration(this);
        }

        private String resolvedId() {
            return id == null
                    ? "magentic-"
                            + participants.stream()
                                    .map(OrchestrationParticipant::id)
                                    .collect(java.util.stream.Collectors.joining("-"))
                    : id;
        }
    }

    private static final class MagenticState {
        private final List<Message> originalInput;

        private List<Message> transcript;

        private MagenticPlan plan;

        private final ArrayList<MagenticProgressAssessment> assessments = new ArrayList<>();

        private final ArrayList<ParticipantResult> results = new ArrayList<>();

        private final ArrayList<OrchestrationError> errors = new ArrayList<>();

        private MagenticProgressAssessment lastAssessment;

        private int iteration;

        private int stallCount;

        private int replanCount;

        private MagenticState(List<Message> input) {
            originalInput = List.copyOf(input);
            transcript = originalInput;
        }

        private MagenticLedger snapshot() {
            return new MagenticLedger(originalInput, transcript, plan, assessments, iteration, stallCount, replanCount);
        }
    }

    private record Assignment(String taskId, String participantId, String instruction) {}

    private record ManagerOutcome<T>(T value, Throwable failure) {}
}
