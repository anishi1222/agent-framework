// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Arrays;

/**
 * Defines provider-neutral tool-selection behavior for a chat request.
 */
public enum ToolChoice {
    /** The model may select a tool or answer directly. */
    AUTO("auto"),
    /** The model must select a tool. */
    REQUIRED("required"),
    /** The model must not select a tool. */
    NONE("none");

    private final String value;

    ToolChoice(String value) {
        this.value = value;
    }

    /**
     * Returns the stable option value.
     *
     * @return option value
     */
    public String value() {
        return value;
    }

    /**
     * Parses a stable option value.
     *
     * @param value option value
     * @return tool choice
     * @throws ValidationException when the value is unsupported
     */
    public static ToolChoice fromValue(String value) {
        CoreValidation.requireNonBlank(value, "value");
        return Arrays.stream(values())
                .filter(choice -> choice.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new ValidationException("Unsupported tool choice '" + value + "'."));
    }

    @Override
    public String toString() {
        return value;
    }
}
