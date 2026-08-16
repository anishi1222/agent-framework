// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import com.microsoft.agents.core.StateValue;

/**
 * Describes one tool call expected in an evaluated conversation.
 *
 * @param name exact non-blank tool name
 * @param arguments optional immutable expected argument subset
 */
public record ExpectedToolCall(String name, StateValue.ObjectValue arguments) {
    /** Creates a validated expected tool call. */
    public ExpectedToolCall {
        name = EvaluationValidation.requireNonBlank(name, "name");
    }

    /**
     * Creates a name-only expected tool call.
     *
     * @param name exact non-blank tool name
     */
    public ExpectedToolCall(String name) {
        this(name, null);
    }
}
