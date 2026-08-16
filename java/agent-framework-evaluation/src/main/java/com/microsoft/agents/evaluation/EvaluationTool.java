// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import com.microsoft.agents.core.StateValue;
import java.util.Map;
import java.util.Objects;

/**
 * Describes one immutable provider-neutral tool available during evaluation.
 *
 * @param name non-blank tool name
 * @param description optional non-blank description
 * @param parameters immutable JSON-shaped parameter schema
 */
public record EvaluationTool(String name, String description, StateValue.ObjectValue parameters) {
    /** Creates a validated evaluation tool. */
    public EvaluationTool {
        name = EvaluationValidation.requireNonBlank(name, "name");
        description = EvaluationValidation.optionalNonBlank(description, "description");
        Objects.requireNonNull(parameters, "parameters");
    }

    /**
     * Creates a tool without a description or parameter members.
     *
     * @param name non-blank tool name
     */
    public EvaluationTool(String name) {
        this(name, null, StateValue.object(Map.of()));
    }
}
