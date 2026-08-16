// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Applies route-level authorization after trusted authentication and isolation are established. */
@FunctionalInterface
public interface HostingAuthorizer {
    /**
     * Authorizes one operation.
     *
     * @param context trusted request context
     * @param descriptor target descriptor, or {@code null} for collection discovery
     * @param action requested action
     * @return decision stage
     */
    CompletionStage<HostingAuthorizationDecision> authorizeAsync(
            HostingRequestContext context, HostingRouteDescriptor descriptor, HostingAuthorizationAction action);

    /**
     * Returns policy that allows every authenticated request.
     *
     * @return allow policy
     */
    static HostingAuthorizer allowAuthenticated() {
        return (context, descriptor, action) -> CompletableFuture.completedFuture(HostingAuthorizationDecision.allow());
    }
}
