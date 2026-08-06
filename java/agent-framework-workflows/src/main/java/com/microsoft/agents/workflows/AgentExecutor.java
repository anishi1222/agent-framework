// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Adapts an optional agents-module {@link Agent} to a generic message-to-message workflow executor.
 *
 * <p>The adapter does not make the workflow engine agent-specific. It forwards workflow cancellation
 * to the agent run and selects the last response message as the node output.
 */
public final class AgentExecutor implements Executor<Message, Message> {
    private final Agent<?> agent;

    private final RunOptions options;

    /**
     * Creates an agent executor using empty run options.
     *
     * @param agent agent to invoke
     */
    public AgentExecutor(Agent<?> agent) {
        this(agent, RunOptions.empty());
    }

    /**
     * Creates an agent executor with immutable run options.
     *
     * @param agent agent to invoke
     * @param options agent run options
     */
    public AgentExecutor(Agent<?> agent, RunOptions options) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public Class<Message> inputType() {
        return Message.class;
    }

    @Override
    public Class<Message> outputType() {
        return Message.class;
    }

    @Override
    public CompletionStage<Message> executeAsync(Message input, WorkflowContext context) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(context, "context");
        return agent.runAsync(input, propagatedOptions(context), context.cancellation())
                .thenApply(AgentExecutor::lastMessage);
    }

    private RunOptions propagatedOptions(WorkflowContext context) {
        if (context.metadata().isEmpty()) {
            return options;
        }
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(options.metadata());
        metadata.putAll(context.metadata());
        return new RunOptions(options.maxIterations(), options.maxFunctionCalls(), Map.copyOf(metadata));
    }

    private static Message lastMessage(AgentResponse<?> response) {
        List<Message> messages =
                Objects.requireNonNull(response, "agent response").messages();
        if (messages.isEmpty()) {
            throw new WorkflowException("Agent executor received a response without messages.");
        }
        return messages.getLast();
    }
}
