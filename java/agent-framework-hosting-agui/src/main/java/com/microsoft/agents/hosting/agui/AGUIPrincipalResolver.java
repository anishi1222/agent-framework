// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import com.microsoft.agents.hosting.HostingAuthentication;
import com.microsoft.agents.hosting.HostingTransportRequest;
import java.util.concurrent.CompletionStage;

/**
 * Resolves a Spring or application authentication name into trusted principal and isolation data.
 *
 * <p>Implementations must derive isolation from trusted authentication state, never AG-UI thread or
 * run identifiers.
 */
@FunctionalInterface
public interface AGUIPrincipalResolver {
    /**
     * Resolves trusted identity.
     *
     * @param authenticatedName authenticated framework principal name, or {@code null}
     * @param request validated transport metadata
     * @return authentication result
     */
    CompletionStage<HostingAuthentication> resolveAsync(String authenticatedName, HostingTransportRequest request);
}
