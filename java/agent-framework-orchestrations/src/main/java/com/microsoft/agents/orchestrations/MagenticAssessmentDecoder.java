// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.AgentResponse;

/** Decodes an agent-backed manager response into a strongly typed progress assessment. */
@FunctionalInterface
public interface MagenticAssessmentDecoder {
    /**
     * Decodes one assessment response.
     *
     * @param response manager response
     * @param context immutable manager context
     * @return non-null assessment
     */
    MagenticProgressAssessment decode(AgentResponse<?> response, MagenticContext context);
}
