// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Map;
import java.util.Objects;

/**
 * Represents a requested function invocation.
 *
 * @param callId non-blank correlation identifier
 * @param name non-blank function name
 * @param arguments JSON-shaped arguments, using {@link StateValue.NullValue} when absent
 * @param informationalOnly whether the call is transcript-only and must not be executed
 * @param metadata immutable additive metadata
 */
public record FunctionCallContent(
        String callId, String name, StateValue arguments, boolean informationalOnly, Map<String, StateValue> metadata)
        implements Content {
    /** Creates validated function-call content. */
    public FunctionCallContent {
        callId = CoreValidation.requireNonBlank(callId, "callId");
        name = CoreValidation.requireNonBlank(name, "name");
        Objects.requireNonNull(arguments, "arguments");
        metadata = CoreValidation.copyStateMap(metadata, "metadata");
    }

    /**
     * Creates an executable function call without metadata.
     *
     * @param callId non-blank correlation identifier
     * @param name non-blank function name
     * @param arguments JSON-shaped arguments
     */
    public FunctionCallContent(String callId, String name, StateValue arguments) {
        this(callId, name, arguments, false, Map.of());
    }

    @Override
    public String kind() {
        return "functionCall";
    }
}
