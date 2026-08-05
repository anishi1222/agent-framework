// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.ValidationException;
import java.util.Arrays;

/**
 * Defines whether a function tool requires an explicit decision before execution.
 */
public enum ToolApprovalMode {
    /** The tool executes without an approval interruption. */
    NEVER_REQUIRE("neverRequire"),
    /** Every invocation requires an explicit approval decision. */
    ALWAYS_REQUIRE("alwaysRequire");

    private final String value;

    ToolApprovalMode(String value) {
        this.value = value;
    }

    /**
     * Returns the stable approval-mode value.
     *
     * @return stable approval-mode value
     */
    public String value() {
        return value;
    }

    /**
     * Parses a stable approval-mode value.
     *
     * @param value approval-mode value
     * @return parsed approval mode
     * @throws ValidationException when the value is unsupported
     */
    public static ToolApprovalMode fromValue(String value) {
        ToolValidation.requireNonBlank(value, "value");
        return Arrays.stream(values())
                .filter(mode -> mode.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new ValidationException("Unsupported tool approval mode '" + value + "'."));
    }

    @Override
    public String toString() {
        return value;
    }
}
