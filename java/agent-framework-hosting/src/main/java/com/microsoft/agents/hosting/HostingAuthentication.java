// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

/**
 * Represents an authentication result without exposing transport or security-framework types.
 *
 * @param status authentication status
 * @param principal authenticated principal, present only for authenticated status
 */
public record HostingAuthentication(HostingAuthenticationStatus status, HostingPrincipal principal) {
    /** Creates a validated result. */
    public HostingAuthentication {
        java.util.Objects.requireNonNull(status, "status");
        if ((status == HostingAuthenticationStatus.AUTHENTICATED) != (principal != null)) {
            throw new com.microsoft.agents.core.ValidationException(
                    "principal must be present exactly when authentication succeeds.");
        }
    }

    /**
     * Creates an authenticated result.
     *
     * @param principal trusted principal
     * @return authenticated result
     */
    public static HostingAuthentication authenticated(HostingPrincipal principal) {
        return new HostingAuthentication(
                HostingAuthenticationStatus.AUTHENTICATED, java.util.Objects.requireNonNull(principal, "principal"));
    }

    /**
     * Creates an unauthenticated result.
     *
     * @return unauthenticated result
     */
    public static HostingAuthentication unauthenticated() {
        return new HostingAuthentication(HostingAuthenticationStatus.UNAUTHENTICATED, null);
    }

    /**
     * Creates a forbidden result.
     *
     * @return forbidden result
     */
    public static HostingAuthentication forbidden() {
        return new HostingAuthentication(HostingAuthenticationStatus.FORBIDDEN, null);
    }
}
