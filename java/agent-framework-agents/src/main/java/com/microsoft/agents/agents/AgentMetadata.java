// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import java.util.UUID;

/**
 * Describes immutable identity and display metadata for an agent.
 *
 * @param id stable non-blank agent identifier
 * @param name optional non-blank display name
 * @param description optional non-blank description
 */
public record AgentMetadata(String id, String name, String description) {
    /** Creates validated immutable agent metadata. */
    public AgentMetadata {
        id = AgentValidation.requireNonBlank(id, "id");
        name = AgentValidation.optionalNonBlank(name, "name");
        description = AgentValidation.optionalNonBlank(description, "description");
    }

    /**
     * Creates metadata with a generated stable identifier.
     *
     * @param name optional display name
     * @param description optional description
     * @return immutable metadata
     */
    public static AgentMetadata create(String name, String description) {
        return new AgentMetadata(UUID.randomUUID().toString(), name, description);
    }

    /**
     * Creates unnamed metadata with a generated stable identifier.
     *
     * @return immutable metadata
     */
    public static AgentMetadata create() {
        return create(null, null);
    }
}
