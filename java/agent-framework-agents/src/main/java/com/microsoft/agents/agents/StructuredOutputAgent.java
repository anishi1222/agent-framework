// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.AgentExecutionException;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StructuredOutputDecoder;
import com.microsoft.agents.core.StructuredOutputs;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Decodes structured JSON from any finite inner-agent response.
 *
 * <p>Streaming updates are forwarded unchanged because the structured value exists only after the
 * complete response has been assembled. The inner agent is caller-owned by default.
 *
 * @param <T> decoded response value type
 */
public final class StructuredOutputAgent<T> implements Agent<T> {
    private final Agent<?> innerAgent;

    private final StructuredOutputDecoder<? extends T> decoder;

    private final boolean closeInnerAgent;

    /**
     * Creates a non-owning structured-output decorator.
     *
     * @param innerAgent caller-owned inner agent
     * @param decoder structured-output decoder
     */
    public StructuredOutputAgent(Agent<?> innerAgent, StructuredOutputDecoder<? extends T> decoder) {
        this(innerAgent, decoder, false);
    }

    /**
     * Creates a structured-output decorator with explicit ownership.
     *
     * @param innerAgent inner agent
     * @param decoder structured-output decoder
     * @param closeInnerAgent whether closing this decorator closes the inner agent
     */
    public StructuredOutputAgent(
            Agent<?> innerAgent, StructuredOutputDecoder<? extends T> decoder, boolean closeInnerAgent) {
        this.innerAgent = AgentValidation.requireNonNull(innerAgent, "innerAgent");
        this.decoder = AgentValidation.requireNonNull(decoder, "decoder");
        this.closeInnerAgent = closeInnerAgent;
    }

    @Override
    public AgentMetadata metadata() {
        return innerAgent.metadata();
    }

    @Override
    public RunHandle<AgentResponse<T>> startRun(
            List<Message> messages, RunOptions options, RunCancellation cancellation) {
        RunHandle<? extends AgentResponse<?>> source = innerAgent.startRun(messages, options, cancellation);
        if (source == null) {
            throw new AgentExecutionException("Inner agent returned a null run handle.");
        }
        CompletionStage<AgentResponse<T>> result =
                source.resultAsync().thenApply(response -> StructuredOutputs.decode(response, decoder));
        return new DecodingRunHandle<>(result, source.cancellation());
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

    private record DecodingRunHandle<T>(CompletionStage<AgentResponse<T>> resultAsync, RunCancellation cancellation)
            implements RunHandle<AgentResponse<T>> {
        private DecodingRunHandle {
            AgentValidation.requireNonNull(resultAsync, "resultAsync");
            AgentValidation.requireNonNull(cancellation, "cancellation");
        }
    }
}
