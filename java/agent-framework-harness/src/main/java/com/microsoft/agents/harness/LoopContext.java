// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunOptions;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Carries one completed loop iteration and per-run evaluator state.
 *
 * @param agent wrapped chat agent
 * @param session active loop session
 * @param initialMessages original caller messages
 * @param lastResponse latest complete response
 * @param runOptions caller run options
 * @param iteration one-based completed iteration number
 * @param progress immutable progress log
 * @param feedback immutable evaluator feedback log
 * @param attributes mutable per-run evaluator attributes
 */
public record LoopContext(
        ChatAgent agent,
        AgentSession session,
        List<Message> initialMessages,
        AgentResponse<Void> lastResponse,
        RunOptions runOptions,
        int iteration,
        List<String> progress,
        List<String> feedback,
        Map<String, Object> attributes) {
    /** Creates a validated context. */
    public LoopContext {
        agent = Objects.requireNonNull(agent, "agent");
        session = Objects.requireNonNull(session, "session");
        initialMessages = List.copyOf(initialMessages);
        lastResponse = Objects.requireNonNull(lastResponse, "lastResponse");
        runOptions = Objects.requireNonNull(runOptions, "runOptions");
        if (iteration <= 0) {
            throw new IllegalArgumentException("iteration must be greater than zero.");
        }
        progress = List.copyOf(progress);
        feedback = List.copyOf(feedback);
        attributes = Objects.requireNonNull(attributes, "attributes");
    }
}
