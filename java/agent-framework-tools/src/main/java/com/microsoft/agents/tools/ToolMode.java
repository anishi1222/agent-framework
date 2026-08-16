// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.ValidationException;
import java.util.Arrays;

/**
 * Defines provider-neutral tool-selection behavior for a function-calling turn.
 */
public enum ToolMode {
    /** The provider may request a tool or answer directly. */
    AUTO("auto"),
    /** The provider must request a tool. */
    REQUIRED("required"),
    /** The provider must not request a tool. */
    NONE("none");

    private final String value;

    ToolMode(String value) {
        this.value = value;
    }

    /**
     * Returns the stable mode value.
     *
     * @return stable mode value
     */
    public String value() {
        return value;
    }

    /**
     * Parses a stable mode value.
     *
     * @param value mode value
     * @return parsed mode
     * @throws ValidationException when the value is unsupported
     */
    public static ToolMode fromValue(String value) {
        ToolValidation.requireNonBlank(value, "value");
        return Arrays.stream(values())
                .filter(mode -> mode.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new ValidationException("Unsupported tool mode '" + value + "'."));
    }

    @Override
    public String toString() {
        return value;
    }
}
