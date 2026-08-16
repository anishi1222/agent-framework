// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents the result correlated to a function call.
 *
 * @param callId non-blank call correlation identifier
 * @param result JSON-shaped result, using {@link StateValue.NullValue} when absent
 * @param items ordered rich result items
 * @param error optional error description
 * @param metadata immutable additive metadata
 */
public record FunctionResultContent(
        String callId, StateValue result, List<Content> items, String error, Map<String, StateValue> metadata)
        implements Content {
    /** Creates validated function-result content. */
    public FunctionResultContent {
        callId = CoreValidation.requireNonBlank(callId, "callId");
        Objects.requireNonNull(result, "result");
        items = CoreValidation.copyList(items, "items");
        error = CoreValidation.optionalNonBlank(error, "error");
        metadata = CoreValidation.copyStateMap(metadata, "metadata");
    }

    /**
     * Creates a function result without rich items or metadata.
     *
     * @param callId non-blank call correlation identifier
     * @param result JSON-shaped result
     */
    public FunctionResultContent(String callId, StateValue result) {
        this(callId, result, List.of(), null, Map.of());
    }

    @Override
    public String kind() {
        return "functionResult";
    }
}
