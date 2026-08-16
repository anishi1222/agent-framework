// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.StateValue;
import java.util.List;

/**
 * Represents detached, serializable state for one agent session.
 *
 * <p>The snapshot contains data only. Providers, middleware, tools, clients, executors, credentials,
 * and other behavior are reconstructed from the owning {@link ChatAgent} configuration.
 *
 * @param sessionId immutable session identity
 * @param messages ordered conversation history
 * @param state immutable JSON-shaped state
 * @param pendingRun optional safe pending-run state, or {@code null}
 */
public record AgentSessionSnapshot(
        String sessionId, List<Message> messages, AgentSessionStateBag state, StateValue.ObjectValue pendingRun) {
    /** Creates and defensively copies a session snapshot. */
    public AgentSessionSnapshot {
        sessionId = AgentValidation.requireNonBlank(sessionId, "sessionId");
        messages = AgentValidation.copyMessages(messages);
        state = AgentValidation.requireNonNull(state, "state");
    }

    /**
     * Creates a snapshot without a pending run.
     *
     * @param sessionId immutable session identity
     * @param messages ordered history
     * @param state immutable state
     */
    public AgentSessionSnapshot(String sessionId, List<Message> messages, AgentSessionStateBag state) {
        this(sessionId, messages, state, null);
    }
}
