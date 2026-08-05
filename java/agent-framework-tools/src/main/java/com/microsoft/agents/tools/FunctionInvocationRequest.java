// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Describes one new uninterrupted logical function-calling run.
 *
 * @param logicalRunId stable logical run identifier
 * @param messages immutable initial model history
 * @param options loop options
 * @param cancellation caller-owned cancellation signal
 * @param metadata immutable provider-neutral run metadata
 */
public record FunctionInvocationRequest(
        String logicalRunId,
        List<Message> messages,
        FunctionInvocationOptions options,
        RunCancellation cancellation,
        Map<String, StateValue> metadata) {
    /** Creates a validated immutable request. */
    public FunctionInvocationRequest {
        logicalRunId = ToolValidation.requireNonBlank(logicalRunId, "logicalRunId");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(cancellation, "cancellation");
        metadata = ToolValidation.copyMetadata(metadata);
    }

    /**
     * Creates a request with default options, cancellation, and metadata.
     *
     * @param logicalRunId logical run identifier
     * @param messages initial model history
     */
    public FunctionInvocationRequest(String logicalRunId, List<Message> messages) {
        this(logicalRunId, messages, FunctionInvocationOptions.defaults(), new DefaultRunCancellation(), Map.of());
    }
}
