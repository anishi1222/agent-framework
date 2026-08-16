// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

/**
 * Identifies one logical tool invocation within an uninterrupted logical run.
 *
 * @param value stable non-blank invocation identifier
 */
public record InvocationId(String value) {
    /** Creates a validated invocation identifier. */
    public InvocationId {
        value = ToolValidation.requireNonBlank(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}
