// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.protocols.a2a.AgentCapabilities;
import com.microsoft.agents.protocols.a2a.AgentCard;
import com.microsoft.agents.protocols.a2a.AgentInterface;
import com.microsoft.agents.protocols.a2a.AgentSkill;
import com.microsoft.agents.workflows.Workflow;
import java.net.URI;
import java.util.List;
import java.util.Objects;

/** Infers conservative A2A cards from framework agents and workflows. */
public final class A2AAgentCardFactory {
    private A2AAgentCardFactory() {}

    /**
     * Creates a card for a framework agent.
     *
     * @param agent agent
     * @param endpoint JSON-RPC endpoint
     * @param version application version
     * @param inputModes advertised input modes
     * @param outputModes advertised output modes
     * @param pushNotifications whether push configuration storage is enabled
     * @param extendedAgentCard whether an authenticated extended card exists
     * @return agent card
     */
    public static AgentCard forAgent(
            Agent<?> agent,
            URI endpoint,
            String version,
            List<String> inputModes,
            List<String> outputModes,
            boolean pushNotifications,
            boolean extendedAgentCard) {
        Objects.requireNonNull(agent, "agent");
        String name = agent.name() == null ? agent.id() : agent.name();
        String description = agent.description() == null ? "Framework-hosted agent " + name : agent.description();
        AgentSkill skill = AgentSkill.builder(agent.id(), name, description)
                .tags(List.of("agent-framework"))
                .inputModes(inputModes)
                .outputModes(outputModes)
                .build();
        return AgentCard.builder(name, description, version)
                .capabilities(AgentCapabilities.builder()
                        .streaming(true)
                        .pushNotifications(pushNotifications)
                        .extendedAgentCard(extendedAgentCard)
                        .build())
                .defaultInputModes(inputModes)
                .defaultOutputModes(outputModes)
                .skills(List.of(skill))
                .supportedInterfaces(List.of(AgentInterface.jsonRpc(endpoint)))
                .build();
    }

    /**
     * Creates a card for a typed framework workflow.
     *
     * @param workflow workflow
     * @param endpoint JSON-RPC endpoint
     * @param version application version
     * @param inputModes advertised input modes
     * @param outputModes advertised output modes
     * @param pushNotifications whether push configuration storage is enabled
     * @param extendedAgentCard whether an authenticated extended card exists
     * @return agent card
     */
    public static AgentCard forWorkflow(
            Workflow<?, ?> workflow,
            URI endpoint,
            String version,
            List<String> inputModes,
            List<String> outputModes,
            boolean pushNotifications,
            boolean extendedAgentCard) {
        Objects.requireNonNull(workflow, "workflow");
        String description = "Framework-hosted workflow " + workflow.id();
        AgentSkill skill = AgentSkill.builder(workflow.id(), workflow.id(), description)
                .tags(List.of("agent-framework", "workflow"))
                .inputModes(inputModes)
                .outputModes(outputModes)
                .build();
        return AgentCard.builder(workflow.id(), description, version)
                .capabilities(AgentCapabilities.builder()
                        .streaming(true)
                        .pushNotifications(pushNotifications)
                        .extendedAgentCard(extendedAgentCard)
                        .build())
                .defaultInputModes(inputModes)
                .defaultOutputModes(outputModes)
                .skills(List.of(skill))
                .supportedInterfaces(List.of(AgentInterface.jsonRpc(endpoint)))
                .build();
    }
}
