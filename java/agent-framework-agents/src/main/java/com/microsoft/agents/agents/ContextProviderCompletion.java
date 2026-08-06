// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.Message;
import java.util.List;

/**
 * Reports one terminal agent invocation to a context provider.
 *
 * @param request provider request as observed before execution
 * @param inputMessages immutable caller input, excluding provider contributions
 * @param response terminal response, or {@code null} on failure
 * @param failure terminal failure, or {@code null} on success
 */
public record ContextProviderCompletion(
        ContextProviderRequest request, List<Message> inputMessages, AgentResponse<?> response, Throwable failure) {
    /** Creates a validated immutable completion notification. */
    public ContextProviderCompletion {
        request = AgentValidation.requireNonNull(request, "request");
        inputMessages = AgentValidation.copyMessages(inputMessages);
        if ((response == null) == (failure == null)) {
            throw new com.microsoft.agents.core.ValidationException(
                    "Exactly one of response or failure must be present.");
        }
    }
}
