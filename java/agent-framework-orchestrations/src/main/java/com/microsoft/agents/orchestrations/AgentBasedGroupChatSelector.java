// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Uses a caller-owned agent to select the next group-chat speaker.
 *
 * <p>The framework supplies an injectable prompt and decoder. The default decoder accepts only one
 * exact registered identifier or the exact token {@code TERMINATE}; surrounding prose and unknown
 * identifiers are rejected.
 */
public final class AgentBasedGroupChatSelector implements GroupChatSelector {
    private final Agent<?> selectorAgent;

    private final GroupChatSelectionPrompt prompt;

    private final GroupChatSelectionDecoder decoder;

    /**
     * Creates a selector using the framework default prompt and strict decoder.
     *
     * @param selectorAgent caller-owned selector agent
     */
    public AgentBasedGroupChatSelector(Agent<?> selectorAgent) {
        this(selectorAgent, AgentBasedGroupChatSelector::defaultMessages, AgentBasedGroupChatSelector::decodeStrict);
    }

    /**
     * Creates a selector using injected framework-owned prompt and decoder contracts.
     *
     * @param selectorAgent caller-owned selector agent
     * @param prompt prompt builder
     * @param decoder response decoder
     */
    public AgentBasedGroupChatSelector(
            Agent<?> selectorAgent, GroupChatSelectionPrompt prompt, GroupChatSelectionDecoder decoder) {
        this.selectorAgent = Objects.requireNonNull(selectorAgent, "selectorAgent");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /**
     * Returns the caller-owned selector agent.
     *
     * @return selector agent
     */
    public Agent<?> selectorAgent() {
        return selectorAgent;
    }

    @Override
    public CompletionStage<String> selectNextAsync(GroupChatContext context) {
        Objects.requireNonNull(context, "context");
        List<Message> messages = OrchestrationValidation.copyMessages(
                Objects.requireNonNull(prompt.messages(context), "prompt returned null"));
        RunOptions configured = context.agentRunOptions();
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(configured.metadata());
        metadata.putAll(context.metadata());
        metadata.put("orchestration.selector.agent.id", StateValue.string(selectorAgent.id()));
        RunOptions options = new RunOptions(configured.maxIterations(), configured.maxFunctionCalls(), metadata);
        CompletionStage<? extends AgentResponse<?>> response =
                selectorAgent.runAsync(messages, options, context.cancellation());
        return Objects.requireNonNull(response, "selector agent returned null").thenApply(value -> {
            GroupChatDecision decision =
                    Objects.requireNonNull(decoder.decode(value, context), "decoder returned null");
            if (decision.terminate()) {
                throw new ValidationException(
                        "A GroupChatSelector must select a participant; use a GroupChatManager to terminate.");
            }
            return validateSelected(decision.participantId(), context);
        });
    }

    /**
     * Creates a manager that supports both strict selection and the {@code TERMINATE} token.
     *
     * @return agent-based manager
     */
    public GroupChatManager asManager() {
        return context -> {
            List<Message> messages = OrchestrationValidation.copyMessages(
                    Objects.requireNonNull(prompt.messages(context), "prompt returned null"));
            RunOptions configured = context.agentRunOptions();
            LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(configured.metadata());
            metadata.putAll(context.metadata());
            metadata.put("orchestration.selector.agent.id", StateValue.string(selectorAgent.id()));
            RunOptions options = new RunOptions(configured.maxIterations(), configured.maxFunctionCalls(), metadata);
            CompletionStage<? extends AgentResponse<?>> response =
                    selectorAgent.runAsync(messages, options, context.cancellation());
            return Objects.requireNonNull(response, "selector agent returned null")
                    .thenApply(value -> {
                        GroupChatDecision decision =
                                Objects.requireNonNull(decoder.decode(value, context), "decoder returned null");
                        if (!decision.terminate()) {
                            validateSelected(decision.participantId(), context);
                        }
                        return decision;
                    });
        };
    }

    private static List<Message> defaultMessages(GroupChatContext context) {
        String identifiers = String.join(", ", context.participants().keySet());
        String instruction = "Select exactly one next speaker identifier from [" + identifiers
                + "], or output exactly TERMINATE. Output no other text.";
        ArrayList<Message> messages = new ArrayList<>(context.transcript().size() + 1);
        messages.add(Message.text(Role.SYSTEM, instruction));
        messages.addAll(context.transcript());
        return List.copyOf(messages);
    }

    private static GroupChatDecision decodeStrict(AgentResponse<?> response, GroupChatContext context) {
        Object structured = response.value();
        String selected = structured instanceof String string ? string : response.text();
        if (selected == null || selected.isBlank()) {
            throw new ValidationException("Selector output must not be blank.");
        }
        String exact = selected.strip();
        if ("TERMINATE".equals(exact)) {
            return GroupChatDecision.terminate("The selector requested termination.");
        }
        validateSelected(exact, context);
        return GroupChatDecision.select(exact);
    }

    private static String validateSelected(String selected, GroupChatContext context) {
        String checked = OrchestrationValidation.requireId(selected, "selected participant");
        if (!context.participants().containsKey(checked)) {
            throw new ValidationException(
                    "Selector output '" + checked + "' is not a registered participant identifier.");
        }
        return checked;
    }
}
