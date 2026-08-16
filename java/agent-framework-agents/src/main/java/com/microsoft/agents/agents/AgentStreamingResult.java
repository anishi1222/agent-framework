// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Couples streaming updates with the terminal response for middleware short-circuiting.
 *
 * @param <T> structured response value type
 * @param updates update publisher
 * @param resultAsync terminal response stage
 */
public record AgentStreamingResult<T>(
        Flow.Publisher<AgentResponseUpdate> updates, CompletionStage<AgentResponse<T>> resultAsync) {
    /** Creates a validated immutable streaming result. */
    public AgentStreamingResult {
        updates = AgentValidation.requireNonNull(updates, "updates");
        resultAsync = AgentValidation.requireNonNull(resultAsync, "resultAsync");
    }
}
