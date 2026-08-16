// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

/**
 * Identifies the component that contributed a message to one agent request.
 *
 * @param value stable extensible source value
 */
public record AgentRequestMessageSourceType(String value) {
    /** Caller input from outside the agent pipeline. */
    public static final AgentRequestMessageSourceType EXTERNAL = new AgentRequestMessageSourceType("External");

    /** A context provider contributed the message. */
    public static final AgentRequestMessageSourceType AI_CONTEXT_PROVIDER =
            new AgentRequestMessageSourceType("AIContextProvider");

    /** A history provider contributed the message. */
    public static final AgentRequestMessageSourceType CHAT_HISTORY = new AgentRequestMessageSourceType("ChatHistory");

    /** Creates a validated extensible source value. */
    public AgentRequestMessageSourceType {
        value = AgentValidation.requireNonBlank(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}
