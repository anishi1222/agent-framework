// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes participants concurrently and aggregates successful results in declaration order.
 *
 * <p>Completion timing never controls result or event order. A collect-errors run with any failure
 * returns {@link OrchestrationOutcome#FAILED} and never invokes the aggregator, so partial success is
 * not presented as a successful aggregate.
 *
 * @param <O> aggregate output type
 */
public final class ConcurrentOrchestration<O> extends AbstractOrchestration<O> {
    private final ConcurrentFailurePolicy failurePolicy;

    private final ConcurrentAggregator<O> aggregator;

    private ConcurrentOrchestration(Builder<O> builder) {
        super(builder.resolvedId(), OrchestrationPattern.CONCURRENT, builder.participants, builder.continuationOptions);
        failurePolicy = builder.failurePolicy;
        aggregator = builder.aggregator;
    }

    /**
     * Creates a builder whose default output is one response per participant.
     *
     * @param participants participants in deterministic declaration order
     * @return concurrent builder
     */
    public static Builder<List<AgentResponse<?>>> builder(List<OrchestrationParticipant> participants) {
        return new Builder<>(participants, results -> {
            ArrayList<AgentResponse<?>> responses = new ArrayList<>(results.size());
            for (ParticipantResult result : results) {
                responses.add(result.response().orElseThrow());
            }
            return List.copyOf(responses);
        });
    }

    /**
     * Creates a typed builder with a custom successful-result aggregator.
     *
     * @param participants participants in deterministic declaration order
     * @param aggregator aggregator
     * @param <O> aggregate output type
     * @return concurrent builder
     */
    public static <O> Builder<O> builder(
            List<OrchestrationParticipant> participants, ConcurrentAggregator<O> aggregator) {
        return new Builder<>(participants, aggregator);
    }

    /**
     * Returns the configured failure policy.
     *
     * @return failure policy
     */
    public ConcurrentFailurePolicy failurePolicy() {
        return failurePolicy;
    }

    @Override
    CompletionStage<OrchestrationResult<O>> execute(OrchestrationExecutionContext<O> context, List<Message> input) {
        if (context.sessionPolicy() == OrchestrationSessionPolicy.SHARED
                && participants().size() > 1) {
            return CompletableFuture.failedFuture(
                    new ValidationException("Concurrent orchestration requires ISOLATED or STATELESS sessions."));
        }
        ArrayList<CompletableFuture<ParticipantResult>> settled =
                new ArrayList<>(participants().size());
        AtomicBoolean siblingCancellationRequested = new AtomicBoolean();
        for (int index = 0; index < participants().size(); index++) {
            OrchestrationParticipant participant = participants().get(index);
            CompletionStage<ParticipantResult> invocation = context.invoke(participant, input, 0);
            CompletableFuture<ParticipantResult> normalized = invocation
                    .handle((result, failure) -> {
                        if (failure == null) {
                            if (siblingCancellationRequested.get()
                                    && result.status() == ParticipantStatus.FAILED
                                    && result.error()
                                            .map(OrchestrationError::errorType)
                                            .filter(RunCancelledException.class.getName()::equals)
                                            .isPresent()) {
                                return ParticipantResult.skipped(participant.id());
                            }
                            return result;
                        }
                        Throwable cause = RunHandles.unwrap(failure);
                        if (context.cancellation().isCancellationRequested()) {
                            throw new java.util.concurrent.CompletionException(new RunCancelledException());
                        }
                        if (siblingCancellationRequested.get() && cause instanceof RunCancelledException) {
                            return ParticipantResult.skipped(participant.id());
                        }
                        return ParticipantResult.failed(participant.id(), cause);
                    })
                    .toCompletableFuture();
            if (failurePolicy == ConcurrentFailurePolicy.FAIL_FAST) {
                normalized.thenAccept(result -> {
                    if (result.status() == ParticipantStatus.FAILED
                            && siblingCancellationRequested.compareAndSet(false, true)) {
                        context.cancelParticipants();
                    }
                });
            }
            settled.add(normalized);
        }
        CompletableFuture<?>[] futures = settled.toArray(CompletableFuture<?>[]::new);
        CompletableFuture<Void> all = CompletableFuture.allOf(futures);
        return all.thenApply(ignored -> collect(context, input, settled))
                .thenCompose(state -> resolveOutcome(context, state));
    }

    private ConcurrentState collect(
            OrchestrationExecutionContext<O> context,
            List<Message> input,
            List<CompletableFuture<ParticipantResult>> settled) {
        ConcurrentState state = new ConcurrentState(input);
        for (CompletableFuture<ParticipantResult> future : settled) {
            ParticipantResult result = future.join();
            state.results.add(result);
            context.emitParticipantResult(result, 0, null);
            if (result.status() == ParticipantStatus.COMPLETED) {
                state.transcript = OrchestrationMessages.appendResponse(
                        state.transcript, input, result.response().orElseThrow());
            }
        }
        return state;
    }

    private CompletionStage<OrchestrationResult<O>> resolveOutcome(
            OrchestrationExecutionContext<O> context, ConcurrentState state) {
        state.errors.clear();
        ArrayList<Integer> inputRequiredIndexes = new ArrayList<>();
        for (int index = 0; index < state.results.size(); index++) {
            ParticipantResult result = state.results.get(index);
            if (result.status() == ParticipantStatus.FAILED) {
                state.errors.add(result.error().orElseThrow());
            } else if (result.status() == ParticipantStatus.INPUT_REQUIRED) {
                inputRequiredIndexes.add(index);
            }
        }
        if (!state.errors.isEmpty()) {
            for (int index : inputRequiredIndexes) {
                ParticipantResult abandoned = state.results.get(index);
                abandoned
                        .agentContinuation()
                        .ifPresent(continuation ->
                                context.abandonApproval(participants().get(index), continuation));
                state.results.set(index, ParticipantResult.abandonedInputRequired(abandoned.participantId()));
            }
            return CompletableFuture.completedFuture(new OrchestrationResult<>(
                    context.runId(),
                    OrchestrationOutcome.FAILED,
                    failurePolicy == ConcurrentFailurePolicy.COLLECT_ERRORS
                            ? OrchestrationTerminationReason.COLLECTED_ERRORS
                            : OrchestrationTerminationReason.PARTICIPANT_FAILURE,
                    null,
                    state.results,
                    state.transcript,
                    List.of(),
                    state.errors,
                    null,
                    state.results.size()));
        }
        if (!inputRequiredIndexes.isEmpty()) {
            return CompletableFuture.completedFuture(inputRequired(context, state, inputRequiredIndexes.getFirst()));
        }
        O output = Objects.requireNonNull(aggregator.aggregate(List.copyOf(state.results)), "aggregator returned null");
        return CompletableFuture.completedFuture(new OrchestrationResult<>(
                context.runId(),
                OrchestrationOutcome.COMPLETED,
                OrchestrationTerminationReason.COMPLETED,
                output,
                state.results,
                state.transcript,
                List.of(),
                List.of(),
                null,
                state.results.size()));
    }

    private OrchestrationResult<O> inputRequired(
            OrchestrationExecutionContext<O> context, ConcurrentState state, int index) {
        ParticipantResult suspended = state.results.get(index);
        com.microsoft.agents.agents.AgentContinuation agentContinuation =
                suspended.agentContinuation().orElseThrow();
        OrchestrationParticipant participant = participants().get(index);
        OrchestrationContinuation continuation = suspend(
                context,
                OrchestrationContinuationKind.APPROVAL,
                suspended.participantId(),
                agentContinuation,
                state.transcript,
                "Concurrent participant '" + suspended.participantId() + "' requires approval.",
                (resumedContext, input) -> resumedContext
                        .resumeApproval(
                                participant,
                                agentContinuation,
                                ((OrchestrationResumeInput.Approval) input).decisions(),
                                0)
                        .thenCompose(result -> {
                            resumedContext.emitParticipantResult(result, 0, null);
                            state.results.set(index, result);
                            if (result.status() == ParticipantStatus.COMPLETED) {
                                state.transcript = OrchestrationMessages.appendResponse(
                                        state.transcript,
                                        state.initialInput,
                                        result.response().orElseThrow());
                            }
                            return resolveOutcome(resumedContext, state);
                        }));
        return new OrchestrationResult<>(
                context.runId(),
                OrchestrationOutcome.INPUT_REQUIRED,
                OrchestrationTerminationReason.INPUT_REQUIRED,
                null,
                state.results,
                state.transcript,
                List.of(),
                List.of(),
                continuation,
                state.results.size());
    }

    /**
     * Builds immutable {@link ConcurrentOrchestration} instances.
     *
     * @param <O> aggregate output type
     */
    public static final class Builder<O> {
        private final List<OrchestrationParticipant> participants;

        private String id;

        private ConcurrentFailurePolicy failurePolicy = ConcurrentFailurePolicy.FAIL_FAST;

        private ConcurrentAggregator<O> aggregator;

        private OrchestrationContinuationOptions continuationOptions = OrchestrationContinuationOptions.defaults();

        private Builder(List<OrchestrationParticipant> participants, ConcurrentAggregator<O> aggregator) {
            this.participants = OrchestrationValidation.copyParticipants(participants);
            this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        }

        /**
         * Sets the stable orchestration identifier.
         *
         * @param id identifier
         * @return this builder
         */
        public Builder<O> id(String id) {
            this.id = OrchestrationValidation.requireId(id, "id");
            return this;
        }

        /**
         * Sets the participant failure policy.
         *
         * @param failurePolicy failure policy
         * @return this builder
         */
        public Builder<O> failurePolicy(ConcurrentFailurePolicy failurePolicy) {
            this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
            return this;
        }

        /**
         * Replaces the successful-result aggregator.
         *
         * @param aggregator aggregator
         * @return this builder
         */
        public Builder<O> aggregator(ConcurrentAggregator<O> aggregator) {
            this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
            return this;
        }

        /**
         * Sets the process-local continuation retention bounds.
         *
         * @param continuationOptions continuation retention options
         * @return this builder
         */
        public Builder<O> continuationOptions(OrchestrationContinuationOptions continuationOptions) {
            this.continuationOptions = Objects.requireNonNull(continuationOptions, "continuationOptions");
            return this;
        }

        /**
         * Creates the immutable orchestration.
         *
         * @return concurrent orchestration
         */
        public ConcurrentOrchestration<O> build() {
            FeatureUsageIndexes.markUsed(FeatureUsageIndexes.CONCURRENT);
            return new ConcurrentOrchestration<>(this);
        }

        private String resolvedId() {
            return id == null
                    ? "concurrent-"
                            + participants.stream()
                                    .map(OrchestrationParticipant::id)
                                    .collect(java.util.stream.Collectors.joining("-"))
                    : id;
        }
    }

    private final class ConcurrentState {
        private final List<Message> initialInput;

        private final ArrayList<ParticipantResult> results = new ArrayList<>();

        private final ArrayList<OrchestrationError> errors = new ArrayList<>();

        private List<Message> transcript;

        private ConcurrentState(List<Message> input) {
            initialInput = List.copyOf(input);
            transcript = initialInput;
        }
    }
}
