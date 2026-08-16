// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.StateValue;

/**
 * Encodes workflow inputs and outputs into explicit JSON-shaped event data.
 *
 * <p>Implementations may encode strongly typed application values without exposing a JSON library.
 * Custom encoders should delegate values they do not own to {@link #defaultEncoder()}.
 */
@FunctionalInterface
public interface WorkflowValueEncoder {
    /**
     * Encodes one workflow value.
     *
     * @param value workflow value, possibly {@code null}
     * @return non-null JSON-shaped state value
     * @throws WorkflowValueEncodingException when the value cannot be encoded explicitly
     */
    StateValue encode(Object value);

    /**
     * Returns the built-in encoder for state values, JSON-shaped Java values, and fan-in inputs.
     *
     * @return built-in encoder
     */
    static WorkflowValueEncoder defaultEncoder() {
        return WorkflowValues::toStateValue;
    }
}
