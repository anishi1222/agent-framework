// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import java.net.URI;

/**
 * Describes one ordered A2A transport interface.
 *
 * @param protocolBinding open protocol binding identifier such as {@code JSONRPC}
 * @param url absolute endpoint URI
 * @param protocolVersion major/minor A2A version
 * @param tenant optional tenant routing value
 */
public record AgentInterface(String protocolBinding, URI url, String protocolVersion, String tenant) {
    /** Creates a validated transport interface. */
    public AgentInterface {
        protocolBinding = A2AValidation.nonBlank(protocolBinding, "protocolBinding");
        url = A2AValidation.absoluteUri(url, "url");
        protocolVersion = A2AValidation.nonBlank(protocolVersion, "protocolVersion");
        tenant = A2AValidation.optionalNonBlank(tenant, "tenant");
    }

    /**
     * Creates a JSON-RPC v1 interface.
     *
     * @param url endpoint URI
     * @return interface descriptor
     */
    public static AgentInterface jsonRpc(URI url) {
        return new AgentInterface("JSONRPC", url, A2AProtocol.VERSION, null);
    }
}
