// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

/**
 * Decodes one parsed JSON value into an application response type.
 *
 * @param <T> decoded response type
 */
@FunctionalInterface
public interface StructuredOutputDecoder<T> {
    /**
     * Decodes one framework-owned JSON value.
     *
     * @param value parsed structured output
     * @return decoded application value
     */
    T decode(StateValue value);

    /**
     * Returns an identity decoder for framework-owned JSON values.
     *
     * @return identity decoder
     */
    static StructuredOutputDecoder<StateValue> stateValue() {
        return value -> value;
    }
}
