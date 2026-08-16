// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import java.net.URI;

/**
 * Identifies the organization providing an A2A agent.
 *
 * @param organization non-blank organization name
 * @param url absolute provider URL
 */
public record AgentProvider(String organization, URI url) {
    /** Creates a validated provider. */
    public AgentProvider {
        organization = A2AValidation.nonBlank(organization, "organization");
        url = A2AValidation.absoluteUri(url, "url");
    }
}
