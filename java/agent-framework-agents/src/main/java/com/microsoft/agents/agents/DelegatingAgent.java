// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunOptions;
import java.util.List;
import java.util.concurrent.Flow;

/**
 * Provides transparent decorator behavior for an inner agent.
 *
 * <p>Subclasses can override selected operations while forwarding the remaining lifecycle to
 * {@link #innerAgent()}. The inner agent is caller-owned by default.
 *
 * @param <T> structured response value type
 */
public abstract class DelegatingAgent<T> implements Agent<T> {
    private final Agent<T> innerAgent;

    private final boolean closeInnerAgent;

    /**
     * Creates a non-owning agent decorator.
     *
     * @param innerAgent caller-owned inner agent
     */
    protected DelegatingAgent(Agent<T> innerAgent) {
        this(innerAgent, false);
    }

    /**
     * Creates an agent decorator with explicit ownership.
     *
     * @param innerAgent inner agent
     * @param closeInnerAgent whether closing this decorator closes the inner agent
     */
    protected DelegatingAgent(Agent<T> innerAgent, boolean closeInnerAgent) {
        this.innerAgent = AgentValidation.requireNonNull(innerAgent, "innerAgent");
        this.closeInnerAgent = closeInnerAgent;
    }

    /**
     * Returns the inner agent receiving delegated operations.
     *
     * @return inner agent
     */
    protected final Agent<T> innerAgent() {
        return innerAgent;
    }

    @Override
    public AgentMetadata metadata() {
        return innerAgent.metadata();
    }

    @Override
    public RunHandle<AgentResponse<T>> startRun(
            List<Message> messages, RunOptions options, RunCancellation cancellation) {
        return innerAgent.startRun(messages, options, cancellation);
    }

    @Override
    public Flow.Publisher<AgentResponseUpdate> runStreaming(
            List<Message> messages, RunOptions options, RunCancellation cancellation) {
        return innerAgent.runStreaming(messages, options, cancellation);
    }

    @Override
    public void close() {
        if (closeInnerAgent) {
            innerAgent.close();
        }
    }
}
