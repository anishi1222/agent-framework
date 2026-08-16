// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Authenticates one validated transport request into trusted hosting identity. */
@FunctionalInterface
public interface HostingAuthenticator {
    /**
     * Authenticates a request.
     *
     * @param request validated transport request
     * @return authentication result stage
     */
    CompletionStage<HostingAuthentication> authenticateAsync(HostingTransportRequest request);

    /**
     * Returns the loopback-development authenticator.
     *
     * <p>This authenticator is deliberately marked local-only and must never be accepted for a
     * non-loopback listener.
     *
     * @return local-only authenticator
     */
    static HostingAuthenticator localOnly() {
        return LocalOnlyHolder.INSTANCE;
    }

    /**
     * Reports whether this authenticator is the built-in loopback-only identity.
     *
     * @return {@code true} only for {@link #localOnly()}
     */
    default boolean isLocalOnly() {
        return false;
    }

    /** Holds the singleton without exposing its implementation. */
    final class LocalOnlyHolder {
        private static final HostingAuthenticator INSTANCE = new LocalOnlyAuthenticator();

        private LocalOnlyHolder() {}
    }

    /** Implements deterministic local development identity. */
    final class LocalOnlyAuthenticator implements HostingAuthenticator {
        private static final HostingAuthentication LOCAL =
                HostingAuthentication.authenticated(new HostingPrincipal("local", "local"));

        private LocalOnlyAuthenticator() {}

        @Override
        public CompletionStage<HostingAuthentication> authenticateAsync(HostingTransportRequest request) {
            java.util.Objects.requireNonNull(request, "request");
            return CompletableFuture.completedFuture(LOCAL);
        }

        @Override
        public boolean isLocalOnly() {
            return true;
        }
    }
}
