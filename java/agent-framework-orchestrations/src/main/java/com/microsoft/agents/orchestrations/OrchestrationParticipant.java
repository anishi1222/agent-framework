// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import java.util.Objects;

/**
 * Describes one agent participating in an orchestration.
 *
 * <p>The descriptor and its identifier are immutable. The referenced agent remains caller-owned and
 * is never closed by an orchestration.
 *
 * @param id stable participant identifier
 * @param agent caller-owned agent
 */
public record OrchestrationParticipant(String id, Agent<?> agent) {
    /** Creates a validated participant descriptor. */
    public OrchestrationParticipant {
        id = OrchestrationValidation.requireId(id, "id");
        agent = Objects.requireNonNull(agent, "agent");
    }

    /**
     * Creates a descriptor using the agent's stable identifier.
     *
     * @param agent caller-owned agent
     * @return participant descriptor
     */
    public static OrchestrationParticipant of(Agent<?> agent) {
        Agent<?> checkedAgent = Objects.requireNonNull(agent, "agent");
        return new OrchestrationParticipant(checkedAgent.id(), checkedAgent);
    }

    /**
     * Returns immutable display metadata from the underlying agent.
     *
     * @return agent metadata
     */
    public AgentMetadata metadata() {
        return agent.metadata();
    }
}
