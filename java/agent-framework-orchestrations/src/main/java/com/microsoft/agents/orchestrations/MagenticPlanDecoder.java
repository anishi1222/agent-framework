// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.AgentResponse;

/** Decodes an agent-backed manager response into a strongly typed Magentic plan. */
@FunctionalInterface
public interface MagenticPlanDecoder {
    /**
     * Decodes one plan response.
     *
     * @param response manager response
     * @param context immutable manager context
     * @return non-null plan
     */
    MagenticPlan decode(AgentResponse<?> response, MagenticContext context);
}
