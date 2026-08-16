// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import java.util.concurrent.CompletionStage;

/** Authenticates one A2A request and supplies its mandatory isolation key. */
@FunctionalInterface
public interface A2AHostAuthenticator {
    /**
     * Authenticates a request.
     *
     * @param request request metadata
     * @return authenticated principal stage
     */
    CompletionStage<A2APrincipal> authenticateAsync(A2AHostRequest request);
}
