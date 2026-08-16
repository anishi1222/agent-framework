// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.AgentResponse;
import java.util.Objects;

/**
 * Exposes an honest completed, working, input-required, or auth-required outcome.
 *
 * @param outcome outcome
 * @param response mapped framework response
 * @param continuation non-terminal continuation, or {@code null} after completion
 * @param task remote task snapshot, or {@code null} for a direct message
 */
public record A2AAgentResult(
        A2AAgentOutcome outcome, AgentResponse<Void> response, A2AContinuation continuation, Task task) {
    /** Creates a validated result. */
    public A2AAgentResult {
        outcome = Objects.requireNonNull(outcome, "outcome");
        response = Objects.requireNonNull(response, "response");
        if (outcome != A2AAgentOutcome.COMPLETED && continuation == null) {
            throw new com.microsoft.agents.core.ValidationException(
                    "Non-completed A2A results must have a continuation.");
        }
    }
}
