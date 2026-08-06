// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.AgentResponse;
import java.util.concurrent.CompletionStage;

/** Defines provider-neutral planning, progress, replanning, and answer synthesis contracts. */
public interface MagenticManager {
    /**
     * Creates the initial task plan.
     *
     * @param context immutable manager context
     * @return plan stage
     */
    CompletionStage<MagenticPlan> planAsync(MagenticContext context);

    /**
     * Replaces a stalled task plan.
     *
     * @param context immutable manager context
     * @return replacement plan stage
     */
    CompletionStage<MagenticPlan> replanAsync(MagenticContext context);

    /**
     * Assesses progress after one participant turn.
     *
     * @param context immutable manager context
     * @return assessment stage
     */
    CompletionStage<MagenticProgressAssessment> assessProgressAsync(MagenticContext context);

    /**
     * Synthesizes the final user-facing answer after the request is satisfied.
     *
     * @param context immutable manager context
     * @return final response stage
     */
    CompletionStage<AgentResponse<?>> prepareFinalAnswerAsync(MagenticContext context);
}
