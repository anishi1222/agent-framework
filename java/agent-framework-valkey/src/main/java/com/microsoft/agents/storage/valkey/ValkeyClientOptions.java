// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import java.time.Duration;

/**
 * Configures one framework-owned standalone GLIDE client.
 *
 * @param endpoint standalone Valkey endpoint
 * @param authentication unauthenticated or ACL authentication
 * @param useTls whether transport security is required
 * @param clientName optional non-blank CLIENT SETNAME value
 * @param operationTimeout deadline applied to client creation and every command future
 */
public record ValkeyClientOptions(
        ValkeyEndpoint endpoint,
        ValkeyAuthentication authentication,
        boolean useTls,
        String clientName,
        Duration operationTimeout) {
    /** Creates validated immutable client options. */
    public ValkeyClientOptions {
        endpoint = ValkeyValidation.requireNonNull(endpoint, "endpoint");
        authentication = ValkeyValidation.requireNonNull(authentication, "authentication");
        clientName = ValkeyValidation.optionalBoundedIdentifier(clientName, "clientName", 128);
        operationTimeout = ValkeyValidation.operationTimeout(operationTimeout);
    }

    /**
     * Creates conservative client defaults without authentication or TLS.
     *
     * @param endpoint standalone endpoint
     * @return default client options
     */
    public static ValkeyClientOptions defaults(ValkeyEndpoint endpoint) {
        return new ValkeyClientOptions(
                endpoint, ValkeyAuthentication.none(), false, "agent-framework-java", Duration.ofSeconds(5));
    }
}
