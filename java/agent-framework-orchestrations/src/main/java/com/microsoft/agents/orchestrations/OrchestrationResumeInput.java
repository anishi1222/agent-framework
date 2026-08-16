// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.tools.ToolApprovalDecision;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Supplies framework-owned input to one suspended orchestration phase.
 *
 * <p>Inputs are intentionally tied to the continuation kind. A continuation is one-time and can be
 * resumed only by the orchestration instance that created it.
 */
public sealed interface OrchestrationResumeInput
        permits OrchestrationResumeInput.Approval,
                OrchestrationResumeInput.HumanInput,
                OrchestrationResumeInput.PlanReview {
    /**
     * Returns the continuation kind accepted by this input.
     *
     * @return continuation kind
     */
    OrchestrationContinuationKind kind();

    /**
     * Creates approval input from decisions bound to the underlying agent requests.
     *
     * @param decisions approval decisions
     * @return immutable approval input
     */
    static Approval approval(Collection<ToolApprovalDecision> decisions) {
        return new Approval(List.copyOf(Objects.requireNonNull(decisions, "decisions")));
    }

    /**
     * Creates human input from one message.
     *
     * @param message human message
     * @return immutable human input
     */
    static HumanInput human(Message message) {
        return new HumanInput(List.of(Objects.requireNonNull(message, "message")));
    }

    /**
     * Creates one user-authored human text message.
     *
     * @param text non-blank human input
     * @return immutable human input
     */
    static HumanInput human(String text) {
        return human(Message.text(Role.USER, OrchestrationValidation.requireText(text, "text")));
    }

    /**
     * Creates an approved plan-review decision.
     *
     * @return approved plan review
     */
    static PlanReview approvePlan() {
        return new PlanReview(PlanDecision.APPROVE, null);
    }

    /**
     * Creates a rejected plan-review decision.
     *
     * @param feedback optional reviewer feedback used during replanning
     * @return rejected plan review
     */
    static PlanReview rejectPlan(String feedback) {
        return new PlanReview(PlanDecision.REJECT, feedback);
    }

    /**
     * Supplies tool-approval decisions for an underlying {@code AgentContinuation}.
     *
     * @param decisions non-empty immutable decision list
     */
    record Approval(List<ToolApprovalDecision> decisions) implements OrchestrationResumeInput {
        /** Creates validated approval input. */
        public Approval {
            decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions"));
            if (decisions.isEmpty()) {
                throw new com.microsoft.agents.core.ValidationException("decisions must not be empty.");
            }
            if (decisions.stream().anyMatch(Objects::isNull)) {
                throw new NullPointerException("decisions contains null");
            }
        }

        @Override
        public OrchestrationContinuationKind kind() {
            return OrchestrationContinuationKind.APPROVAL;
        }
    }

    /**
     * Supplies one or more human messages to a suspended pattern.
     *
     * @param messages non-empty immutable message list
     */
    record HumanInput(List<Message> messages) implements OrchestrationResumeInput {
        /** Creates validated human input. */
        public HumanInput {
            messages = OrchestrationValidation.copyMessages(messages);
            if (messages.isEmpty()) {
                throw new com.microsoft.agents.core.ValidationException("messages must not be empty.");
            }
        }

        @Override
        public OrchestrationContinuationKind kind() {
            return OrchestrationContinuationKind.HUMAN_INPUT;
        }
    }

    /**
     * Supplies a typed Magentic plan-review decision.
     *
     * @param decision approve or reject decision
     * @param feedback optional reviewer feedback
     */
    record PlanReview(PlanDecision decision, String feedback) implements OrchestrationResumeInput {
        /** Creates validated plan-review input. */
        public PlanReview {
            decision = Objects.requireNonNull(decision, "decision");
            feedback = OrchestrationValidation.optionalText(feedback, "feedback");
        }

        @Override
        public OrchestrationContinuationKind kind() {
            return OrchestrationContinuationKind.PLAN_REVIEW;
        }
    }

    /** Selects whether a suspended Magentic plan is accepted. */
    enum PlanDecision {
        /** Accepts the current plan and starts or continues participant work. */
        APPROVE,

        /** Rejects the current plan and requests bounded replanning. */
        REJECT
    }
}
