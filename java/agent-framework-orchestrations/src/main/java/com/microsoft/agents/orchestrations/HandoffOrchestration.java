// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Executes typed, registry-constrained agent handoffs with bounded turns and loop policies.
 *
 * <p>The default router consumes function-call content rather than natural-language prompt text.
 * Every accepted target is resolved from the immutable registry, and correlation metadata is
 * propagated through the shared execution context.
 */
public final class HandoffOrchestration extends AbstractOrchestration<AgentResponse<?>> {
    private final Map<String, OrchestrationParticipant> participantsById;

    private final Map<String, HandoffTarget> targets;

    private final Map<String, Set<String>> allowedTargets;

    private final String startParticipantId;

    private final HandoffRouter router;

    private final HandoffTerminationPredicate terminationPredicate;

    private final HandoffViolationPolicy unknownTargetPolicy;

    private final HandoffViolationPolicy disallowedTransitionPolicy;

    private final HandoffViolationPolicy selfHandoffPolicy;

    private final HandoffViolationPolicy loopPolicy;

    private final int maxTurns;

    private final int maxHandoffs;

    private HandoffOrchestration(Builder builder) {
        super(builder.resolvedId(), OrchestrationPattern.HANDOFF, builder.participants, builder.continuationOptions);
        LinkedHashMap<String, OrchestrationParticipant> participantMap = new LinkedHashMap<>();
        for (OrchestrationParticipant participant : participants()) {
            participantMap.put(participant.id(), participant);
        }
        participantsById = Collections.unmodifiableMap(participantMap);
        targets = Collections.unmodifiableMap(new LinkedHashMap<>(builder.targets));
        allowedTargets = copyTransitions(builder.allowedTargets);
        startParticipantId = builder.startParticipantId;
        router = builder.router;
        terminationPredicate = builder.terminationPredicate;
        unknownTargetPolicy = builder.unknownTargetPolicy;
        disallowedTransitionPolicy = builder.disallowedTransitionPolicy;
        selfHandoffPolicy = builder.selfHandoffPolicy;
        loopPolicy = builder.loopPolicy;
        maxTurns = builder.maxTurns;
        maxHandoffs = builder.maxHandoffs;
    }

    /**
     * Creates a handoff builder with every participant registered as a target.
     *
     * @param participants participants in deterministic registry order
     * @return builder
     */
    public static Builder builder(List<OrchestrationParticipant> participants) {
        return new Builder(participants);
    }

    /**
     * Returns registered targets in deterministic declaration order.
     *
     * @return immutable target map
     */
    public Map<String, HandoffTarget> targets() {
        return targets;
    }

    /**
     * Returns the starting participant identifier.
     *
     * @return start participant identifier
     */
    public String startParticipantId() {
        return startParticipantId;
    }

    @Override
    CompletionStage<OrchestrationResult<AgentResponse<?>>> execute(
            OrchestrationExecutionContext<AgentResponse<?>> context, List<Message> input) {
        HandoffState state = new HandoffState(input, startParticipantId);
        return runTurn(context, state);
    }

    private CompletionStage<OrchestrationResult<AgentResponse<?>>> runTurn(
            OrchestrationExecutionContext<AgentResponse<?>> context, HandoffState state) {
        if (state.turns >= maxTurns) {
            return CompletableFuture.completedFuture(result(
                    context,
                    state,
                    OrchestrationOutcome.TERMINATED,
                    OrchestrationTerminationReason.MAX_TURNS,
                    state.lastResponse,
                    null));
        }
        OrchestrationParticipant current = participantsById.get(state.currentParticipantId);
        List<Message> participantInput = state.turns == 0 ? state.initialInput : nonEmptyTextProjection(state);
        int turn = state.turns;
        return context.invoke(current, participantInput, turn)
                .thenCompose(participantResult -> handleParticipantResult(
                        context, state, current, participantInput, turn, participantResult, false));
    }

    private CompletionStage<OrchestrationResult<AgentResponse<?>>> handleParticipantResult(
            OrchestrationExecutionContext<AgentResponse<?>> context,
            HandoffState state,
            OrchestrationParticipant current,
            List<Message> participantInput,
            int turn,
            ParticipantResult participantResult,
            boolean resumed) {
        context.emitParticipantResult(participantResult, turn, null);
        if (resumed) {
            state.results.set(turn, participantResult);
        } else {
            state.results.add(participantResult);
            state.turns++;
        }
        return switch (participantResult.status()) {
            case COMPLETED ->
                routeCompletedTurn(
                        context,
                        state,
                        current,
                        participantInput,
                        participantResult.response().orElseThrow());
            case INPUT_REQUIRED ->
                CompletableFuture.completedFuture(
                        approvalRequired(context, state, current, participantInput, turn, participantResult));
            case FAILED -> {
                state.errors.add(participantResult.error().orElseThrow());
                yield CompletableFuture.completedFuture(result(
                        context,
                        state,
                        OrchestrationOutcome.FAILED,
                        OrchestrationTerminationReason.PARTICIPANT_FAILURE,
                        null,
                        null));
            }
            case SKIPPED -> throw new IllegalStateException("The runtime cannot invoke a skipped participant.");
        };
    }

    private CompletionStage<OrchestrationResult<AgentResponse<?>>> routeCompletedTurn(
            OrchestrationExecutionContext<AgentResponse<?>> context,
            HandoffState state,
            OrchestrationParticipant current,
            List<Message> participantInput,
            AgentResponse<?> response) {
        state.transcript = OrchestrationMessages.appendResponse(state.transcript, participantInput, response);
        state.lastResponse = response;
        HandoffTurnContext turnContext = new HandoffTurnContext(
                current, targets, state.transcript, response, state.path, state.turns, state.handoffs);
        if (terminationPredicate != null && terminationPredicate.shouldTerminate(turnContext)) {
            return CompletableFuture.completedFuture(result(
                    context,
                    state,
                    OrchestrationOutcome.TERMINATED,
                    OrchestrationTerminationReason.PREDICATE_SATISFIED,
                    response,
                    null));
        }
        HandoffDirective directive;
        try {
            directive = Objects.requireNonNull(router.route(turnContext), "router returned null");
        } catch (RuntimeException failure) {
            state.errors.add(OrchestrationError.from(current.id(), failure));
            return CompletableFuture.completedFuture(result(
                    context,
                    state,
                    OrchestrationOutcome.FAILED,
                    OrchestrationTerminationReason.MANAGER_FAILURE,
                    null,
                    null));
        }
        if (directive instanceof HandoffCompletion) {
            return CompletableFuture.completedFuture(result(
                    context,
                    state,
                    OrchestrationOutcome.COMPLETED,
                    OrchestrationTerminationReason.COMPLETED,
                    response,
                    null));
        }
        if (directive instanceof HandoffInputRequest inputRequest) {
            return CompletableFuture.completedFuture(humanInputRequired(context, state, inputRequest));
        }
        return handleRequest(context, state, current, (HandoffRequest) directive);
    }

    private CompletionStage<OrchestrationResult<AgentResponse<?>>> handleRequest(
            OrchestrationExecutionContext<AgentResponse<?>> context,
            HandoffState state,
            OrchestrationParticipant current,
            HandoffRequest request) {
        if (state.handoffs >= maxHandoffs) {
            return CompletableFuture.completedFuture(result(
                    context,
                    state,
                    OrchestrationOutcome.TERMINATED,
                    OrchestrationTerminationReason.MAX_HANDOFFS,
                    state.lastResponse,
                    null));
        }
        if (!targets.containsKey(request.targetId())) {
            return applyViolation(
                    context,
                    state,
                    unknownTargetPolicy,
                    "Handoff target '" + request.targetId() + "' is not registered.");
        }
        if (current.id().equals(request.targetId())) {
            return applyViolation(
                    context, state, selfHandoffPolicy, "Participant '" + current.id() + "' requested a self-handoff.");
        }
        if (!isAllowed(current.id(), request.targetId())) {
            return applyViolation(
                    context,
                    state,
                    disallowedTransitionPolicy,
                    "Handoff target '" + request.targetId() + "' is not allowed from '" + current.id() + "'.");
        }
        if (state.path.contains(request.targetId())) {
            return applyViolation(
                    context,
                    state,
                    loopPolicy,
                    "Handoff to '" + request.targetId() + "' would repeat the accepted path.");
        }
        LinkedHashMap<String, StateValue> data = new LinkedHashMap<>();
        data.put("sourceId", StateValue.string(current.id()));
        data.put("targetId", StateValue.string(request.targetId()));
        if (request.reason() != null) {
            data.put("reason", StateValue.string(request.reason()));
        }
        context.emit(OrchestrationEventType.HANDOFF, current.id(), state.turns - 1, null, StateValue.object(data));
        state.currentParticipantId = request.targetId();
        state.path.add(request.targetId());
        state.handoffs++;
        return runTurn(context, state);
    }

    private CompletionStage<OrchestrationResult<AgentResponse<?>>> applyViolation(
            OrchestrationExecutionContext<AgentResponse<?>> context,
            HandoffState state,
            HandoffViolationPolicy policy,
            String message) {
        return switch (policy) {
            case FAIL -> {
                state.errors.add(OrchestrationError.from(null, new ValidationException(message)));
                yield CompletableFuture.completedFuture(result(
                        context,
                        state,
                        OrchestrationOutcome.FAILED,
                        OrchestrationTerminationReason.HANDOFF_REJECTED,
                        null,
                        null));
            }
            case TERMINATE ->
                CompletableFuture.completedFuture(result(
                        context,
                        state,
                        OrchestrationOutcome.TERMINATED,
                        OrchestrationTerminationReason.HANDOFF_REJECTED,
                        state.lastResponse,
                        null));
            case IGNORE -> runTurn(context, state);
        };
    }

    private OrchestrationResult<AgentResponse<?>> approvalRequired(
            OrchestrationExecutionContext<AgentResponse<?>> context,
            HandoffState state,
            OrchestrationParticipant current,
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
                                current,
                                agentContinuation,
                                ((OrchestrationResumeInput.Approval) input).decisions(),
                                turn)
                        .thenCompose(resumedResult -> handleParticipantResult(
                                resumedContext, state, current, participantInput, turn, resumedResult, true)));
        return result(
                context,
                state,
                OrchestrationOutcome.INPUT_REQUIRED,
                OrchestrationTerminationReason.INPUT_REQUIRED,
                null,
                continuation);
    }

    private OrchestrationResult<AgentResponse<?>> humanInputRequired(
            OrchestrationExecutionContext<AgentResponse<?>> context,
            HandoffState state,
            HandoffInputRequest inputRequest) {
        OrchestrationContinuation continuation = suspend(
                context,
                OrchestrationContinuationKind.HUMAN_INPUT,
                state.currentParticipantId,
                null,
                state.transcript,
                inputRequest.prompt(),
                (resumedContext, input) -> {
                    ArrayList<Message> continuedTranscript = new ArrayList<>(state.transcript);
                    continuedTranscript.addAll(((OrchestrationResumeInput.HumanInput) input).messages());
                    state.transcript = List.copyOf(continuedTranscript);
                    return runTurn(resumedContext, state);
                });
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
            HandoffState state,
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
                state.turns);
    }

    private boolean isAllowed(String sourceId, String targetId) {
        return allowedTargets.isEmpty()
                || allowedTargets.getOrDefault(sourceId, Set.of()).contains(targetId);
    }

    private static List<Message> nonEmptyTextProjection(HandoffState state) {
        List<Message> projected = OrchestrationMessages.textOnly(state.transcript);
        return projected.isEmpty() ? state.initialInput : projected;
    }

    private static Map<String, Set<String>> copyTransitions(Map<String, Set<String>> transitions) {
        LinkedHashMap<String, Set<String>> copy = new LinkedHashMap<>();
        transitions.forEach((source, destinations) ->
                copy.put(source, Collections.unmodifiableSet(new LinkedHashSet<>(destinations))));
        return Collections.unmodifiableMap(copy);
    }

    /** Builds immutable {@link HandoffOrchestration} instances. */
    public static final class Builder {
        private static final int DEFAULT_MAX_TURNS = 40;

        private static final int DEFAULT_MAX_HANDOFFS = 20;

        private final List<OrchestrationParticipant> participants;

        private final LinkedHashMap<String, HandoffTarget> targets = new LinkedHashMap<>();

        private final LinkedHashMap<String, Set<String>> allowedTargets = new LinkedHashMap<>();

        private String id;

        private String startParticipantId;

        private HandoffRouter router = HandoffRouter.functionCalls();

        private HandoffTerminationPredicate terminationPredicate;

        private HandoffViolationPolicy unknownTargetPolicy = HandoffViolationPolicy.FAIL;

        private HandoffViolationPolicy disallowedTransitionPolicy = HandoffViolationPolicy.FAIL;

        private HandoffViolationPolicy selfHandoffPolicy = HandoffViolationPolicy.FAIL;

        private HandoffViolationPolicy loopPolicy = HandoffViolationPolicy.TERMINATE;

        private int maxTurns = DEFAULT_MAX_TURNS;

        private int maxHandoffs = DEFAULT_MAX_HANDOFFS;

        private OrchestrationContinuationOptions continuationOptions = OrchestrationContinuationOptions.defaults();

        private Builder(List<OrchestrationParticipant> participants) {
            this.participants = OrchestrationValidation.copyParticipants(participants);
            startParticipantId = this.participants.getFirst().id();
            for (OrchestrationParticipant participant : this.participants) {
                targets.put(
                        participant.id(),
                        new HandoffTarget(
                                participant.id(), participant.metadata().description()));
            }
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
         * Sets the registered starting participant.
         *
         * @param participantId participant identifier
         * @return this builder
         */
        public Builder startParticipant(String participantId) {
            startParticipantId = requireRegistered(participantId);
            return this;
        }

        /**
         * Replaces one registered target description.
         *
         * @param participantId registered participant identifier
         * @param description optional description
         * @return this builder
         */
        public Builder target(String participantId, String description) {
            String checked = requireRegistered(participantId);
            targets.put(checked, new HandoffTarget(checked, description));
            return this;
        }

        /**
         * Restricts one source participant to an allowed registered target.
         *
         * <p>Once any transition is configured, every accepted handoff must appear in the configured
         * transition map.
         *
         * @param sourceId source participant identifier
         * @param targetId target participant identifier
         * @return this builder
         */
        public Builder allowHandoff(String sourceId, String targetId) {
            String source = requireRegistered(sourceId);
            String target = requireRegistered(targetId);
            allowedTargets
                    .computeIfAbsent(source, ignored -> new LinkedHashSet<>())
                    .add(target);
            return this;
        }

        /**
         * Sets the typed routing contract.
         *
         * @param router router
         * @return this builder
         */
        public Builder router(HandoffRouter router) {
            this.router = Objects.requireNonNull(router, "router");
            return this;
        }

        /**
         * Sets an optional turn termination predicate.
         *
         * @param terminationPredicate predicate
         * @return this builder
         */
        public Builder terminationPredicate(HandoffTerminationPredicate terminationPredicate) {
            this.terminationPredicate = Objects.requireNonNull(terminationPredicate, "terminationPredicate");
            return this;
        }

        /**
         * Sets the unknown-target policy.
         *
         * @param policy violation policy
         * @return this builder
         */
        public Builder unknownTargetPolicy(HandoffViolationPolicy policy) {
            unknownTargetPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /**
         * Sets the policy for a registered target that is not allowed from the current participant.
         *
         * @param policy violation policy
         * @return this builder
         */
        public Builder disallowedTransitionPolicy(HandoffViolationPolicy policy) {
            disallowedTransitionPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /**
         * Sets the self-handoff policy.
         *
         * @param policy violation policy
         * @return this builder
         */
        public Builder selfHandoffPolicy(HandoffViolationPolicy policy) {
            selfHandoffPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /**
         * Sets the repeated-path loop policy.
         *
         * @param policy violation policy
         * @return this builder
         */
        public Builder loopPolicy(HandoffViolationPolicy policy) {
            loopPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /**
         * Sets the positive maximum participant turn count.
         *
         * @param maxTurns maximum turns
         * @return this builder
         */
        public Builder maxTurns(int maxTurns) {
            if (maxTurns <= 0) {
                throw new IllegalArgumentException("maxTurns must be greater than zero.");
            }
            this.maxTurns = maxTurns;
            return this;
        }

        /**
         * Sets the non-negative maximum accepted handoff count.
         *
         * @param maxHandoffs maximum handoffs
         * @return this builder
         */
        public Builder maxHandoffs(int maxHandoffs) {
            if (maxHandoffs < 0) {
                throw new IllegalArgumentException("maxHandoffs must not be negative.");
            }
            this.maxHandoffs = maxHandoffs;
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
         * Creates the immutable handoff orchestration.
         *
         * @return handoff orchestration
         */
        public HandoffOrchestration build() {
            FeatureUsageIndexes.markUsed(FeatureUsageIndexes.HANDOFF);
            return new HandoffOrchestration(this);
        }

        private String requireRegistered(String participantId) {
            String checked = OrchestrationValidation.requireId(participantId, "participantId");
            if (participants.stream().noneMatch(participant -> participant.id().equals(checked))) {
                throw new ValidationException("Unknown participant '" + checked + "'.");
            }
            return checked;
        }

        private String resolvedId() {
            return id == null
                    ? "handoff-"
                            + participants.stream()
                                    .map(OrchestrationParticipant::id)
                                    .collect(java.util.stream.Collectors.joining("-"))
                    : id;
        }
    }

    private static final class HandoffState {
        private final List<Message> initialInput;

        private List<Message> transcript;

        private final ArrayList<ParticipantResult> results = new ArrayList<>();

        private final ArrayList<OrchestrationError> errors = new ArrayList<>();

        private final ArrayList<String> path = new ArrayList<>();

        private String currentParticipantId;

        private int turns;

        private int handoffs;

        private AgentResponse<?> lastResponse;

        private HandoffState(List<Message> input, String startParticipantId) {
            initialInput = List.copyOf(input);
            transcript = initialInput;
            currentParticipantId = startParticipantId;
            path.add(startParticipantId);
        }
    }
}
