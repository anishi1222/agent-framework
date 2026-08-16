// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.RunCancellation;

/**
 * Carries immutable data for one chat middleware invocation.
 *
 * @param request provider-neutral chat request
 * @param cancellation explicit cancellation signal
 * @param metadata isolated metadata runtime
 */
public record ChatMiddlewareContext(
        ChatClientRequest request, RunCancellation cancellation, MiddlewareMetadata metadata) {
    /** Creates a validated immutable chat context. */
    public ChatMiddlewareContext {
        request = AgentValidation.requireNonNull(request, "request");
        cancellation = AgentValidation.requireNonNull(cancellation, "cancellation");
        metadata = AgentValidation.requireNonNull(metadata, "metadata");
    }
}
