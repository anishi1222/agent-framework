// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandles;
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
 * Executes a manager-directed group chat against one race-free shared transcript.
 *
 * <p>Manager selections are validated against exact registered identifiers, configured transitions,
 * and the speaker repetition policy before any participant invocation starts.
 */
public final class GroupChatOrchestration extends AbstractOrchestration<AgentResponse<?>> {
    private final Map<String, OrchestrationParticipant> participantsById;

    private final Map<String, Set<String>> allowedTransitions;

    private final GroupChatManager manager;

    private final SpeakerRepetitionPolicy repetitionPolicy;

    private final int maxTurns;

    private GroupChatOrchestration(Builder builder) {
        super(builder.resolvedId(), OrchestrationPattern.GROUP_CHAT, builder.participants, builder.continuationOptions);
        LinkedHashMap<String, OrchestrationParticipant> participantMap = new LinkedHashMap<>();
        for (OrchestrationParticipant participant : participants()) {
            participantMap.put(participant.id(), participant);
        }
        participantsById = Collections.unmodifiableMap(participantMap);
        allowedTransitions = copyTransitions(builder.allowedTransitions);
        GroupChatManager configured = builder.manager;
        if (builder.terminationPredicate == null) {
            manager = configured;
        } else {
            manager = context -> builder.terminationPredicate.shouldTerminate(context)
                    ? CompletableFuture.completedFuture(
                            GroupChatDecision.terminate("The configured termination predicate was satisfied."))
                    : configured.decideAsync(context);
        }
        repetitionPolicy = builder.repetitionPolicy;
        maxTurns = builder.maxTurns;
    }

    /**
     * Creates a group-chat builder using deterministic round-robin selection.
     *
     * @param participants participants in declaration order
     * @return builder
     */
    public static Builder builder(List<OrchestrationParticipant> participants) {
        return new Builder(participants);
    }

    /**
     * Returns the configured speaker repetition policy.
     *
     * @return repetition policy
     */
    public SpeakerRepetitionPolicy repetitionPolicy() {
        return repetitionPolicy;
    }

    @Override
    CompletionStage<OrchestrationResult<AgentResponse<?>>> execute(
            OrchestrationExecutionContext<AgentResponse<?>> context, List<Message> input) {
        return runTurn(context, new GroupChatState(input));
    }

    private CompletionStage<OrchestrationResult<AgentResponse<?>>> runTurn(
            OrchestrationExecutionContext<AgentResponse<?>> execution, GroupChatState state) {
        if (state.turns >= maxTurns) {
            return CompletableFuture.completedFuture(result(
                    execution,
                    state,
                    OrchestrationOutcome.TERMINATED,
                    OrchestrationTerminationReason.MAX_TURNS,
                    state.lastResponse,
                    null));
        }
        GroupChatContext managerContext = new GroupChatContext(
                participantsById,
                state.transcript,
                state.previousSpeakerId,
                state.turns,
                execution.cancellation(),
                execution.options().agentRunOptions(),
                execution.options().metadata());
        CompletionStage<GroupChatDecision> decisionStage;
        try {
            decisionStage =
                    Objects.requireNonNull(manager.decideAsync(managerContext), "group-chat manager returned null");
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(managerFailure(execution, state, failure));
        }
        return decisionStage
                .handle((decision, failure) -> new ManagerOutcome(decision, failure))
                .thenCompose(outcome -> {
                    if (outcome.failure != null) {
                        Throwable cause = RunHandles.unwrap(outcome.failure);
                        if (execution.cancellation().isCancellationRequested()
                                || cause instanceof RunCancelledException) {
                            return CompletableFuture.failedFuture(new RunCancelledException());
                        }
                        return CompletableFuture.completedFuture(managerFailure(execution, state, cause));
                    }
                    GroupChatDecision decision;
                    try {
                        decision = validateDecision(outcome.decision, state);
                    } catch (RuntimeException failure) {
                        return CompletableFuture.completedFuture(managerFailure(execution, state, failure));
                    }
                    if (decision.terminate()) {
                        return CompletableFuture.completedFuture(result(
                                execution,
                                state,
                                OrchestrationOutcome.TERMINATED,
                                OrchestrationTerminationReason.PREDICATE_SATISFIED,
                                state.lastResponse,
                                null));
                    }
                    return invokeSelected(execution, state, decision.participantId());
                });
    }

    private CompletionStage<OrchestrationResult<AgentResponse<?>>> invokeSelected(
            OrchestrationExecutionContext<AgentResponse<?>> execution, GroupChatState state, String participantId) {
        LinkedHashMap<String, StateValue> data = new LinkedHashMap<>();
        data.put("participantId", StateValue.string(participantId));
        data.put("turn", StateValue.integer(state.turns));
        execution.emit(
                OrchestrationEventType.SPEAKER_SELECTED, participantId, state.turns, null, StateValue.object(data));
        OrchestrationParticipant participant = participantsById.get(participantId);
        List<Message> participantInput = state.turns == 0 ? state.initialInput : nonEmptyTextProjection(state);
        int turn = state.turns;
        return execution
                .invoke(participant, participantInput, turn)
                .thenCompose(participantResult -> handleParticipantResult(
                        execution, state, participant, participantInput, turn, participantResult, false));
    }

    private CompletionStage<OrchestrationResult<AgentResponse<?>>> handleParticipantResult(
            OrchestrationExecutionContext<AgentResponse<?>> execution,
            GroupChatState state,
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
            state.turns++;
        }
        return switch (participantResult.status()) {
            case COMPLETED -> {
                AgentResponse<?> response = participantResult.response().orElseThrow();
                state.transcript = OrchestrationMessages.appendResponse(state.transcript, participantInput, response);
                state.lastResponse = response;
                state.previousSpeakerId = participant.id();
                yield runTurn(execution, state);
            }
            case INPUT_REQUIRED ->
                CompletableFuture.completedFuture(
                        inputRequired(execution, state, participant, participantInput, turn, participantResult));
            case FAILED -> {
                state.errors.add(participantResult.error().orElseThrow());
                yield CompletableFuture.completedFuture(result(
                        execution,
                        state,
                        OrchestrationOutcome.FAILED,
                        OrchestrationTerminationReason.PARTICIPANT_FAILURE,
                        null,
                        null));
            }
            case SKIPPED -> throw new IllegalStateException("The runtime cannot invoke a skipped participant.");
        };
    }

    private GroupChatDecision validateDecision(GroupChatDecision decision, GroupChatState state) {
        GroupChatDecision checked = Objects.requireNonNull(decision, "manager decision");
        if (checked.terminate()) {
            return checked;
        }
        String selected = checked.participantId();
        if (!participantsById.containsKey(selected)) {
            throw new ValidationException("Group-chat manager selected unknown participant '" + selected + "'.");
        }
        if (repetitionPolicy == SpeakerRepetitionPolicy.DISALLOW_CONSECUTIVE
                && selected.equals(state.previousSpeakerId)) {
            throw new ValidationException("Group-chat manager repeated participant '" + selected + "'.");
        }
        if (!allowedTransitions.isEmpty()
                && state.previousSpeakerId != null
                && !allowedTransitions
                        .getOrDefault(state.previousSpeakerId, Set.of())
                        .contains(selected)) {
            throw new ValidationException(
                    "Transition from '" + state.previousSpeakerId + "' to '" + selected + "' is not allowed.");
        }
        return checked;
    }

    private OrchestrationResult<AgentResponse<?>> managerFailure(
            OrchestrationExecutionContext<AgentResponse<?>> execution, GroupChatState state, Throwable failure) {
        state.errors.add(OrchestrationError.from(null, failure));
        return result(
                execution,
                state,
                OrchestrationOutcome.FAILED,
                OrchestrationTerminationReason.MANAGER_FAILURE,
                null,
                null);
    }

    private OrchestrationResult<AgentResponse<?>> inputRequired(
            OrchestrationExecutionContext<AgentResponse<?>> execution,
            GroupChatState state,
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
                "Group-chat participant '" + participantResult.participantId() + "' requires approval.",
                (resumedContext, input) -> resumedContext
                        .resumeApproval(
                                participant,
                                agentContinuation,
                                ((OrchestrationResumeInput.Approval) input).decisions(),
                                turn)
                        .thenCompose(resumedResult -> handleParticipantResult(
                                resumedContext, state, participant, participantInput, turn, resumedResult, true)));
        return result(
                execution,
                state,
                OrchestrationOutcome.INPUT_REQUIRED,
                OrchestrationTerminationReason.INPUT_REQUIRED,
                null,
                continuation);
    }

    private static OrchestrationResult<AgentResponse<?>> result(
            OrchestrationExecutionContext<AgentResponse<?>> execution,
            GroupChatState state,
            OrchestrationOutcome outcome,
            OrchestrationTerminationReason reason,
            AgentResponse<?> output,
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
                state.turns);
    }

    private static List<Message> nonEmptyTextProjection(GroupChatState state) {
        List<Message> projected = OrchestrationMessages.textOnly(state.transcript);
        return projected.isEmpty() ? state.initialInput : projected;
    }

    private static Map<String, Set<String>> copyTransitions(Map<String, Set<String>> transitions) {
        LinkedHashMap<String, Set<String>> copy = new LinkedHashMap<>();
        transitions.forEach(
                (source, targets) -> copy.put(source, Collections.unmodifiableSet(new LinkedHashSet<>(targets))));
        return Collections.unmodifiableMap(copy);
    }

    /** Builds immutable {@link GroupChatOrchestration} instances. */
    public static final class Builder {
        private static final int DEFAULT_MAX_TURNS = 40;

        private final List<OrchestrationParticipant> participants;

        private final LinkedHashMap<String, Set<String>> allowedTransitions = new LinkedHashMap<>();

        private String id;

        private GroupChatManager manager = GroupChatManager.fromSelector(new RoundRobinGroupChatSelector());

        private GroupChatTerminationPredicate terminationPredicate;

        private SpeakerRepetitionPolicy repetitionPolicy = SpeakerRepetitionPolicy.ALLOW;

        private int maxTurns = DEFAULT_MAX_TURNS;

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
         * Sets a complete manager contract.
         *
         * @param manager manager
         * @return this builder
         */
        public Builder manager(GroupChatManager manager) {
            this.manager = Objects.requireNonNull(manager, "manager");
            return this;
        }

        /**
         * Sets a next-speaker selector while retaining optional predicate termination.
         *
         * @param selector selector
         * @return this builder
         */
        public Builder selector(GroupChatSelector selector) {
            manager = GroupChatManager.fromSelector(Objects.requireNonNull(selector, "selector"));
            return this;
        }

        /**
         * Sets an agent-based selector manager.
         *
         * @param selector agent-based selector
         * @return this builder
         */
        public Builder agentBasedSelector(AgentBasedGroupChatSelector selector) {
            manager = Objects.requireNonNull(selector, "selector").asManager();
            return this;
        }

        /**
         * Sets the shared-transcript termination predicate.
         *
         * @param terminationPredicate predicate
         * @return this builder
         */
        public Builder terminationPredicate(GroupChatTerminationPredicate terminationPredicate) {
            this.terminationPredicate = Objects.requireNonNull(terminationPredicate, "terminationPredicate");
            return this;
        }

        /**
         * Sets the speaker repetition policy.
         *
         * @param repetitionPolicy repetition policy
         * @return this builder
         */
        public Builder repetitionPolicy(SpeakerRepetitionPolicy repetitionPolicy) {
            this.repetitionPolicy = Objects.requireNonNull(repetitionPolicy, "repetitionPolicy");
            return this;
        }

        /**
         * Allows one exact registered speaker transition.
         *
         * <p>Once any transition is configured, every transition after the first turn must be
         * explicitly allowed.
         *
         * @param sourceId source speaker
         * @param targetId target speaker
         * @return this builder
         */
        public Builder allowTransition(String sourceId, String targetId) {
            String source = requireRegistered(sourceId);
            String target = requireRegistered(targetId);
            allowedTransitions
                    .computeIfAbsent(source, ignored -> new LinkedHashSet<>())
                    .add(target);
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
         * Creates the immutable group-chat orchestration.
         *
         * @return group-chat orchestration
         */
        public GroupChatOrchestration build() {
            return new GroupChatOrchestration(this);
        }

        private String requireRegistered(String id) {
            String checked = OrchestrationValidation.requireId(id, "participantId");
            if (participants.stream().noneMatch(participant -> participant.id().equals(checked))) {
                throw new ValidationException("Unknown participant '" + checked + "'.");
            }
            return checked;
        }

        private String resolvedId() {
            return id == null
                    ? "group-chat-"
                            + participants.stream()
                                    .map(OrchestrationParticipant::id)
                                    .collect(java.util.stream.Collectors.joining("-"))
                    : id;
        }
    }

    private static final class GroupChatState {
        private final List<Message> initialInput;

        private List<Message> transcript;

        private final ArrayList<ParticipantResult> results = new ArrayList<>();

        private final ArrayList<OrchestrationError> errors = new ArrayList<>();

        private String previousSpeakerId;

        private int turns;

        private AgentResponse<?> lastResponse;

        private GroupChatState(List<Message> input) {
            initialInput = List.copyOf(input);
            transcript = initialInput;
        }
    }

    private record ManagerOutcome(GroupChatDecision decision, Throwable failure) {}
}
