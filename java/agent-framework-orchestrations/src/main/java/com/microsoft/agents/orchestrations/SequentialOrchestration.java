// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Executes an ordered agent pipeline through one shared orchestration runtime.
 *
 * <p>Responses are appended to the canonical transcript exactly once. By default, downstream
 * participants receive the full transcript; callers may select the previous response projection or
 * inject a typed output-to-input transform.
 */
public final class SequentialOrchestration extends AbstractOrchestration<AgentResponse<?>> {
    private final SequentialHistoryPolicy historyPolicy;

    private final SequentialFailurePolicy failurePolicy;

    private final SequentialInputTransform inputTransform;

    private SequentialOrchestration(Builder builder) {
        super(builder.resolvedId(), OrchestrationPattern.SEQUENTIAL, builder.participants, builder.continuationOptions);
        historyPolicy = builder.historyPolicy;
        failurePolicy = builder.failurePolicy;
        inputTransform = builder.inputTransform;
    }

    /**
     * Creates a sequential builder.
     *
     * @param participants participants in pipeline order
     * @return builder
     */
    public static Builder builder(List<OrchestrationParticipant> participants) {
        return new Builder(participants);
    }

    /**
     * Creates a sequential builder from agent descriptors.
     *
     * @param first first participant
     * @param remaining remaining participants
     * @return builder
     */
    public static Builder builder(OrchestrationParticipant first, OrchestrationParticipant... remaining) {
        Objects.requireNonNull(first, "first");
        ArrayList<OrchestrationParticipant> participants = new ArrayList<>();
        participants.add(first);
        participants.addAll(List.of(remaining));
        return builder(participants);
    }

    /**
     * Returns the configured history projection.
     *
     * @return history policy
     */
    public SequentialHistoryPolicy historyPolicy() {
        return historyPolicy;
    }

    /**
     * Returns the configured failure policy.
     *
     * @return failure policy
     */
    public SequentialFailurePolicy failurePolicy() {
        return failurePolicy;
    }

    @Override
    CompletionStage<OrchestrationResult<AgentResponse<?>>> execute(
            OrchestrationExecutionContext<AgentResponse<?>> context, List<Message> input) {
        PipelineState state = new PipelineState(input);
        return runStep(context, state);
    }

    private CompletionStage<OrchestrationResult<AgentResponse<?>>> runStep(
            OrchestrationExecutionContext<AgentResponse<?>> context, PipelineState state) {
        if (state.nextIndex >= participants().size()) {
            return CompletableFuture.completedFuture(complete(context, state));
        }
        int turn = state.nextIndex;
        OrchestrationParticipant participant = participants().get(turn);
        List<Message> participantInput = inputFor(state, participant);
        return context.invoke(participant, participantInput, turn)
                .thenCompose(result ->
                        handleParticipantResult(context, state, participant, participantInput, turn, result, false));
    }

    private CompletionStage<OrchestrationResult<AgentResponse<?>>> handleParticipantResult(
            OrchestrationExecutionContext<AgentResponse<?>> context,
            PipelineState state,
            OrchestrationParticipant participant,
            List<Message> participantInput,
            int turn,
            ParticipantResult result,
            boolean resumed) {
        context.emitParticipantResult(result, turn, null);
        if (resumed) {
            state.results.set(turn, result);
        } else {
            state.results.add(result);
            state.nextIndex++;
        }
        return switch (result.status()) {
            case COMPLETED -> {
                AgentResponse<?> response = result.response().orElseThrow();
                state.transcript = OrchestrationMessages.appendResponse(state.transcript, participantInput, response);
                state.lastResponse = response;
                state.lastSuccessfulParticipant = participant;
                yield runStep(context, state);
            }
            case INPUT_REQUIRED ->
                CompletableFuture.completedFuture(
                        inputRequired(context, state, participant, participantInput, turn, result));
            case FAILED -> {
                state.errors.add(result.error().orElseThrow());
                if (failurePolicy == SequentialFailurePolicy.STOP) {
                    for (int index = state.nextIndex; index < participants().size(); index++) {
                        ParticipantResult skipped = ParticipantResult.skipped(
                                participants().get(index).id());
                        state.results.add(skipped);
                        context.emitParticipantResult(skipped, index, null);
                    }
                    yield CompletableFuture.completedFuture(failed(context, state));
                }
                yield runStep(context, state);
            }
            case SKIPPED -> throw new IllegalStateException("The runtime cannot invoke a skipped participant.");
        };
    }

    private List<Message> inputFor(PipelineState state, OrchestrationParticipant nextParticipant) {
        if (state.nextIndex == 0 || state.lastResponse == null || state.lastSuccessfulParticipant == null) {
            return state.initialInput;
        }
        if (inputTransform != null) {
            List<Message> transformed = inputTransform.transform(new SequentialTransformContext(
                    state.nextIndex,
                    state.lastSuccessfulParticipant,
                    nextParticipant,
                    state.initialInput,
                    state.transcript,
                    state.lastResponse));
            return OrchestrationValidation.copyMessages(
                    Objects.requireNonNull(transformed, "Sequential input transform returned null."));
        }
        return historyPolicy == SequentialHistoryPolicy.SHARED_TRANSCRIPT
                ? state.transcript
                : OrchestrationMessages.responseAsNextInput(state.lastSuccessfulParticipant, state.lastResponse);
    }

    private OrchestrationResult<AgentResponse<?>> complete(
            OrchestrationExecutionContext<AgentResponse<?>> context, PipelineState state) {
        if (state.lastResponse == null) {
            return failed(context, state);
        }
        OrchestrationOutcome outcome =
                state.errors.isEmpty() ? OrchestrationOutcome.COMPLETED : OrchestrationOutcome.COMPLETED_WITH_ERRORS;
        return result(context, state, outcome, OrchestrationTerminationReason.COMPLETED, state.lastResponse, null);
    }

    private OrchestrationResult<AgentResponse<?>> failed(
            OrchestrationExecutionContext<AgentResponse<?>> context, PipelineState state) {
        return result(
                context,
                state,
                OrchestrationOutcome.FAILED,
                OrchestrationTerminationReason.PARTICIPANT_FAILURE,
                null,
                null);
    }

    private OrchestrationResult<AgentResponse<?>> inputRequired(
            OrchestrationExecutionContext<AgentResponse<?>> context,
            PipelineState state,
            OrchestrationParticipant participant,
            List<Message> participantInput,
            int turn,
            ParticipantResult participantResult) {
        com.microsoft.agents.agents.AgentContinuation agentContinuation =
                participantResult.agentContinuation().orElseThrow();
        OrchestrationContinuation continuation = suspend(
                context,
                OrchestrationContinuationKind.APPROVAL,
                participantResult.participantId(),
                agentContinuation,
                state.transcript,
                "Participant '" + participantResult.participantId() + "' requires approval.",
                (resumedContext, input) -> resumedContext
                        .resumeApproval(
                                participant,
                                agentContinuation,
                                ((OrchestrationResumeInput.Approval) input).decisions(),
                                turn)
                        .thenCompose(resumedResult -> handleParticipantResult(
                                resumedContext, state, participant, participantInput, turn, resumedResult, true)));
        return result(
                context,
                state,
                OrchestrationOutcome.INPUT_REQUIRED,
                OrchestrationTerminationReason.INPUT_REQUIRED,
                null,
                continuation);
    }

    private static OrchestrationResult<AgentResponse<?>> result(
            OrchestrationExecutionContext<AgentResponse<?>> context,
            PipelineState state,
            OrchestrationOutcome outcome,
            OrchestrationTerminationReason reason,
            AgentResponse<?> output,
            OrchestrationContinuation continuation) {
        return new OrchestrationResult<>(
                context.runId(),
                outcome,
                reason,
                output,
                state.results,
                state.transcript,
                List.of(),
                state.errors,
                continuation,
                state.nextIndex);
    }

    /** Builds immutable {@link SequentialOrchestration} instances. */
    public static final class Builder {
        private final List<OrchestrationParticipant> participants;

        private String id;

        private SequentialHistoryPolicy historyPolicy = SequentialHistoryPolicy.SHARED_TRANSCRIPT;

        private SequentialFailurePolicy failurePolicy = SequentialFailurePolicy.STOP;

        private SequentialInputTransform inputTransform;

        private OrchestrationContinuationOptions continuationOptions = OrchestrationContinuationOptions.defaults();

        private Builder(List<OrchestrationParticipant> participants) {
            this.participants = OrchestrationValidation.copyParticipants(participants);
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
         * Sets the default history projection.
         *
         * @param historyPolicy history policy
         * @return this builder
         */
        public Builder historyPolicy(SequentialHistoryPolicy historyPolicy) {
            this.historyPolicy = Objects.requireNonNull(historyPolicy, "historyPolicy");
            return this;
        }

        /**
         * Sets the participant failure policy.
         *
         * @param failurePolicy failure policy
         * @return this builder
         */
        public Builder failurePolicy(SequentialFailurePolicy failurePolicy) {
            this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
            return this;
        }

        /**
         * Sets an output-to-next-input transform.
         *
         * @param inputTransform transform
         * @return this builder
         */
        public Builder inputTransform(SequentialInputTransform inputTransform) {
            this.inputTransform = Objects.requireNonNull(inputTransform, "inputTransform");
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
         * Creates the immutable orchestration.
         *
         * @return sequential orchestration
         */
        public SequentialOrchestration build() {
            FeatureUsageIndexes.markUsed(FeatureUsageIndexes.SEQUENTIAL);
            return new SequentialOrchestration(this);
        }

        private String resolvedId() {
            return id == null
                    ? "sequential-"
                            + participants.stream()
                                    .map(OrchestrationParticipant::id)
                                    .collect(java.util.stream.Collectors.joining("-"))
                    : id;
        }
    }

    private static final class PipelineState {
        private final List<Message> initialInput;

        private List<Message> transcript;

        private final ArrayList<ParticipantResult> results = new ArrayList<>();

        private final ArrayList<OrchestrationError> errors = new ArrayList<>();

        private int nextIndex;

        private OrchestrationParticipant lastSuccessfulParticipant;

        private AgentResponse<?> lastResponse;

        private PipelineState(List<Message> input) {
            initialInput = List.copyOf(input);
            transcript = initialInput;
        }
    }
}
