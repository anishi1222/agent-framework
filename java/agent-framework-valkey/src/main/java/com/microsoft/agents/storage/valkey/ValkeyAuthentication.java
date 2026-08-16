// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import java.util.Optional;

/** Selects unauthenticated access or explicit Valkey ACL username/password authentication. */
public sealed interface ValkeyAuthentication permits ValkeyNoAuthentication, ValkeyAclAuthentication {
    /** Identifies the configured authentication mechanism. */
    enum Kind {
        /** No AUTH command is sent. */
        NONE,
        /** ACL username and password authentication. */
        ACL
    }

    /**
     * Creates an unauthenticated configuration.
     *
     * @return unauthenticated configuration
     */
    static ValkeyAuthentication none() {
        return ValkeyNoAuthentication.INSTANCE;
    }

    /**
     * Creates ACL username/password authentication.
     *
     * @param username non-blank ACL username
     * @param password redacting password wrapper
     * @return ACL authentication configuration
     */
    static ValkeyAuthentication acl(String username, ValkeyPassword password) {
        return new ValkeyAclAuthentication(username, password);
    }

    /**
     * Returns the authentication kind.
     *
     * @return authentication kind
     */
    Kind kind();

    /**
     * Returns the ACL username when configured.
     *
     * @return optional ACL username
     */
    Optional<String> username();

    /**
     * Returns the redacting password wrapper when configured.
     *
     * @return optional password wrapper
     */
    Optional<ValkeyPassword> password();
}
