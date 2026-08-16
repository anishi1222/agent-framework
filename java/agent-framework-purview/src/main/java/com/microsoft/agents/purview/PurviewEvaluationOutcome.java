// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

/**
 * Returns a policy decision and the resolved Entra user used for response evaluation.
 *
 * @param decision policy decision
 * @param userId resolved Entra user object identifier
 */
public record PurviewEvaluationOutcome(PurviewDecision decision, String userId) {
    /** Creates and validates an outcome. */
    public PurviewEvaluationOutcome {
        decision = java.util.Objects.requireNonNull(decision, "decision");
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank.");
        }
    }
}
