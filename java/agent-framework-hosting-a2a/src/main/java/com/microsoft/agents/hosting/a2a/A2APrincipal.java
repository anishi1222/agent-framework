// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

/**
 * Carries authenticated identity and mandatory storage-isolation dimensions.
 *
 * @param principalId authenticated principal identifier
 * @param isolationKey tenant/user isolation key
 */
public record A2APrincipal(String principalId, String isolationKey) {
    /** Creates a validated principal. */
    public A2APrincipal {
        principalId = HostingA2AValidation.nonBlank(principalId, "principalId");
        isolationKey = HostingA2AValidation.nonBlank(isolationKey, "isolationKey");
    }

    /**
     * Returns the fixed principal used only by explicitly loopback-only unauthenticated hosts.
     *
     * @return loopback principal
     */
    public static A2APrincipal loopbackAnonymous() {
        return new A2APrincipal("anonymous", "loopback-only");
    }
}
