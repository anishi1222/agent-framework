// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.openai;

import com.microsoft.agents.hosting.HostingAuthentication;
import com.microsoft.agents.hosting.HostingTransportRequest;
import java.util.concurrent.CompletionStage;

/** Resolves application-framework identity into trusted hosting principal and isolation details. */
@FunctionalInterface
public interface OpenAIResponsesPrincipalResolver {
    /**
     * Resolves trusted authentication.
     *
     * @param authenticatedName application-framework principal name, or {@code null}
     * @param request validated transport request
     * @return authentication result stage
     */
    CompletionStage<HostingAuthentication> resolveAsync(String authenticatedName, HostingTransportRequest request);
}
