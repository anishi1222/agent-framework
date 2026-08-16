// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

/**
 * Identifies one agent-session snapshot in a {@link SessionStore}.
 *
 * @param value opaque non-blank storage key
 */
public record SessionKey(String value) {
    /** Creates a validated opaque session key. */
    public SessionKey {
        value = AgentValidation.requireNonBlank(value, "value");
    }

    /**
     * Creates a key from a session identity.
     *
     * @param session session runtime
     * @return key using the immutable session identifier
     */
    public static SessionKey of(AgentSession session) {
        return new SessionKey(AgentValidation.requireNonNull(session, "session").sessionId());
    }
}
