// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import com.microsoft.agents.core.StateValue;
import java.util.List;

/**
 * Represents the exact request body consumed by an AG-UI agent endpoint.
 *
 * <p>This implementation deliberately applies a stricter input policy than the permissive official
 * schemas: present optional identifiers and names must be nonblank, and decoding rejects unknown
 * members with an actionable list. This catches misspellings and unsupported request features
 * early, at the cost of requiring a library upgrade before sending future input members. Recognized
 * event envelopes use the more forward-compatible policy documented by {@link
 * AGUIEvent#additionalProperties()}.
 *
 * @param threadId caller correlation thread identifier, never authorization identity
 * @param runId caller correlation run identifier, never authorization identity
 * @param parentRunId optional lineage run identifier
 * @param state required JSON-shaped state, using JSON null when absent
 * @param messages ordered conversation messages
 * @param tools client-provided tool declarations
 * @param context context entries
 * @param forwardedProps required JSON-shaped forwarded properties, using JSON null when absent
 * @param resume optional complete responses to the preceding run's open interrupts
 */
public record RunAgentInput(
        String threadId,
        String runId,
        String parentRunId,
        StateValue state,
        List<AGUIMessage> messages,
        List<AGUITool> tools,
        List<AGUIContext> context,
        StateValue forwardedProps,
        List<AGUIResumeEntry> resume) {
    /** Creates a validated immutable run input. */
    public RunAgentInput {
        threadId = AGUIValidation.nonBlank(threadId, "threadId");
        runId = AGUIValidation.nonBlank(runId, "runId");
        parentRunId = AGUIValidation.optionalNonBlank(parentRunId, "parentRunId");
        state = AGUIValidation.state(state, "state");
        messages = AGUIValidation.list(messages, "messages");
        tools = AGUIValidation.list(tools, "tools");
        context = AGUIValidation.list(context, "context");
        forwardedProps = AGUIValidation.state(forwardedProps, "forwardedProps");
        resume = AGUIValidation.list(resume, "resume");
    }

    /**
     * Creates a run without lineage or resume entries.
     *
     * @param threadId thread identifier
     * @param runId run identifier
     * @param state state value
     * @param messages messages
     * @param tools tools
     * @param context context
     * @param forwardedProps forwarded properties
     */
    public RunAgentInput(
            String threadId,
            String runId,
            StateValue state,
            List<AGUIMessage> messages,
            List<AGUITool> tools,
            List<AGUIContext> context,
            StateValue forwardedProps) {
        this(threadId, runId, null, state, messages, tools, context, forwardedProps, List.of());
    }
}
