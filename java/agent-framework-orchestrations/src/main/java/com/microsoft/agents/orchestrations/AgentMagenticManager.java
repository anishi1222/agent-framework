// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/**
 * Adapts a caller-owned agent to the provider-neutral {@link MagenticManager} contract.
 *
 * <p>The default decoders require framework-owned structured values in
 * {@link AgentResponse#value()}; they do not parse provider-specific text or JSON. Callers may inject
 * decoders when their configured structured-output agent uses another framework-owned projection.
 */
public final class AgentMagenticManager implements MagenticManager {
    private final Agent<?> managerAgent;

    private final MagenticPromptTemplates templates;

    private final MagenticPlanDecoder planDecoder;

    private final MagenticAssessmentDecoder assessmentDecoder;

    /**
     * Creates an agent manager using default prompts and strict structured-value decoders.
     *
     * @param managerAgent caller-owned manager agent
     */
    public AgentMagenticManager(Agent<?> managerAgent) {
        this(
                managerAgent,
                MagenticPromptTemplates.defaults(),
                AgentMagenticManager::decodePlan,
                AgentMagenticManager::decodeAssessment);
    }

    /**
     * Creates an agent manager using injected prompts and decoders.
     *
     * @param managerAgent caller-owned manager agent
     * @param templates framework-owned prompt templates
     * @param planDecoder plan decoder
     * @param assessmentDecoder assessment decoder
     */
    public AgentMagenticManager(
            Agent<?> managerAgent,
            MagenticPromptTemplates templates,
            MagenticPlanDecoder planDecoder,
            MagenticAssessmentDecoder assessmentDecoder) {
        this.managerAgent = Objects.requireNonNull(managerAgent, "managerAgent");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.planDecoder = Objects.requireNonNull(planDecoder, "planDecoder");
        this.assessmentDecoder = Objects.requireNonNull(assessmentDecoder, "assessmentDecoder");
    }

    /**
     * Returns the caller-owned manager agent.
     *
     * @return manager agent
     */
    public Agent<?> managerAgent() {
        return managerAgent;
    }

    /**
     * Returns immutable prompt templates.
     *
     * @return templates
     */
    public MagenticPromptTemplates templates() {
        return templates;
    }

    @Override
    public CompletionStage<MagenticPlan> planAsync(MagenticContext context) {
        return invoke(context, templates.planningPrompt(), "plan")
                .thenApply(response ->
                        Objects.requireNonNull(planDecoder.decode(response, context), "plan decoder returned null"));
    }

    @Override
    public CompletionStage<MagenticPlan> replanAsync(MagenticContext context) {
        return invoke(context, templates.replanningPrompt(), "replan")
                .thenApply(response ->
                        Objects.requireNonNull(planDecoder.decode(response, context), "plan decoder returned null"));
    }

    @Override
    public CompletionStage<MagenticProgressAssessment> assessProgressAsync(MagenticContext context) {
        return invoke(context, templates.assessmentPrompt(), "assessment")
                .thenApply(response -> Objects.requireNonNull(
                        assessmentDecoder.decode(response, context), "assessment decoder returned null"));
    }

    @Override
    public CompletionStage<AgentResponse<?>> prepareFinalAnswerAsync(MagenticContext context) {
        return invoke(context, templates.finalAnswerPrompt(), "final-answer");
    }

    private CompletionStage<AgentResponse<?>> invoke(MagenticContext context, String template, String operation) {
        Objects.requireNonNull(context, "context");
        String rendered = render(template, context);
        RunOptions configured = context.agentRunOptions();
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(configured.metadata());
        metadata.putAll(context.metadata());
        metadata.put("orchestration.magentic.manager.id", StateValue.string(managerAgent.id()));
        metadata.put("orchestration.magentic.operation", StateValue.string(operation));
        RunOptions options = new RunOptions(configured.maxIterations(), configured.maxFunctionCalls(), metadata);
        CompletionStage<? extends AgentResponse<?>> response = Objects.requireNonNull(
                managerAgent.runAsync(List.of(Message.text(Role.SYSTEM, rendered)), options, context.cancellation()),
                "manager agent returned null");
        return response.thenApply(value -> value);
    }

    private static String render(String template, MagenticContext context) {
        String task =
                context.ledger().originalInput().stream().map(Message::text).collect(Collectors.joining("\n"));
        String team = context.participants().values().stream()
                .map(participant -> participant.id() + ": "
                        + Objects.toString(participant.metadata().description(), "no description"))
                .collect(Collectors.joining("\n"));
        String ledger = renderLedger(context.ledger());
        return template.replace("{task}", task).replace("{team}", team).replace("{ledger}", ledger);
    }

    private static String renderLedger(MagenticLedger ledger) {
        String plan = ledger.plan() == null
                ? "No plan yet."
                : ledger.plan().tasks().stream()
                        .map(task -> task.id() + " [" + task.status() + "] -> " + task.participantId() + ": "
                                + task.description())
                        .collect(Collectors.joining("\n"));
        return "iteration=" + ledger.iteration()
                + ", stalls=" + ledger.stallCount()
                + ", replans=" + ledger.replanCount()
                + "\n"
                + plan;
    }

    private static MagenticPlan decodePlan(AgentResponse<?> response, MagenticContext context) {
        if (response.value() instanceof MagenticPlan plan) {
            return plan;
        }
        throw new ValidationException(
                "Agent-backed Magentic planning requires AgentResponse.value() to contain MagenticPlan.");
    }

    private static MagenticProgressAssessment decodeAssessment(AgentResponse<?> response, MagenticContext context) {
        if (response.value() instanceof MagenticProgressAssessment assessment) {
            return assessment;
        }
        throw new ValidationException("Agent-backed Magentic assessment requires AgentResponse.value() "
                + "to contain MagenticProgressAssessment.");
    }
}
