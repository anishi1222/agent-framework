// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Carries immutable metadata and explicit dependencies for one agent run.
 *
 * <p>The context is passed directly across executor and provider boundaries. The runtime does not use
 * {@link ThreadLocal}, so concurrent and virtual-thread continuations cannot leak one run's context
 * into another.
 *
 * @param runId stable non-blank run identifier
 * @param agent immutable agent metadata
 * @param startedAt run creation time
 * @param inputMessages immutable ordered caller input
 * @param options immutable run options
 * @param cancellation run cancellation signal
 * @param metadata immutable run metadata copied from the run options
 * @param session optional active session
 * @param contribution immutable context-provider contribution accumulated for this run
 */
public record AgentRunContext(
        String runId,
        AgentMetadata agent,
        Instant startedAt,
        List<Message> inputMessages,
        RunOptions options,
        RunCancellation cancellation,
        Map<String, StateValue> metadata,
        AgentSession session,
        ContextContribution contribution) {
    /** Creates and defensively copies an explicit run context. */
    public AgentRunContext {
        runId = AgentValidation.requireNonBlank(runId, "runId");
        agent = AgentValidation.requireNonNull(agent, "agent");
        startedAt = AgentValidation.requireNonNull(startedAt, "startedAt");
        inputMessages = AgentValidation.copyMessages(inputMessages);
        options = AgentValidation.requireNonNull(options, "options");
        cancellation = AgentValidation.requireNonNull(cancellation, "cancellation");
        metadata = AgentValidation.copyMetadata(metadata);
        contribution = AgentValidation.requireNonNull(contribution, "contribution");
    }

    /**
     * Creates a context without a session or provider contributions.
     *
     * @param runId stable run identifier
     * @param agent immutable agent metadata
     * @param startedAt run creation time
     * @param inputMessages ordered caller input
     * @param options run options
     * @param cancellation run cancellation
     * @param metadata immutable run metadata
     */
    public AgentRunContext(
            String runId,
            AgentMetadata agent,
            Instant startedAt,
            List<Message> inputMessages,
            RunOptions options,
            RunCancellation cancellation,
            Map<String, StateValue> metadata) {
        this(
                runId,
                agent,
                startedAt,
                inputMessages,
                options,
                cancellation,
                metadata,
                null,
                ContextContribution.empty());
    }
}
