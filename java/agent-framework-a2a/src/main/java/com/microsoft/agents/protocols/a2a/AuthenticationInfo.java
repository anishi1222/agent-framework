// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import java.util.Objects;

/** Holds push-endpoint authentication information with redacted diagnostics. */
public final class AuthenticationInfo {
    private final String scheme;
    private final String credentials;

    /**
     * Creates authentication information.
     *
     * @param scheme non-blank scheme name
     * @param credentials optional opaque credentials
     */
    public AuthenticationInfo(String scheme, String credentials) {
        this.scheme = A2AValidation.nonBlank(scheme, "scheme");
        this.credentials = A2AValidation.optionalNonBlank(credentials, "credentials");
    }

    /**
     * Returns the scheme.
     *
     * @return scheme
     */
    public String scheme() {
        return scheme;
    }

    /**
     * Returns the opaque credentials.
     *
     * @return credentials, or {@code null}
     */
    public String credentials() {
        return credentials;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof AuthenticationInfo info
                        && scheme.equals(info.scheme)
                        && Objects.equals(credentials, info.credentials);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scheme, credentials);
    }

    @Override
    public String toString() {
        return "AuthenticationInfo[scheme=" + scheme + ", credentials=<redacted>]";
    }
}
