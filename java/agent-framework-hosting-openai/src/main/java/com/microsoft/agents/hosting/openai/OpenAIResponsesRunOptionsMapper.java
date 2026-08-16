// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.openai;

import com.microsoft.agents.core.RunOptions;

/** Maps caller-supplied OpenAI Responses settings onto provider-neutral run options. */
@FunctionalInterface
public interface OpenAIResponsesRunOptionsMapper {
    /**
     * Maps validated request settings.
     *
     * @param request validated framework-owned request information
     * @return non-null provider-neutral run options
     */
    RunOptions map(OpenAIResponsesRequestInfo request);
}
