// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.ValidationException;
import java.util.Arrays;

/**
 * Identifies a provider-neutral capability exposed by a tool.
 */
public enum ToolCapability {
    /** Locally invokable function capability. */
    FUNCTION("function"),
    /** Hosted or local code-interpreter capability. */
    CODE_INTERPRETER("codeInterpreter"),
    /** File-search capability. */
    FILE_SEARCH("fileSearch"),
    /** Image-generation capability. */
    IMAGE_GENERATION("imageGeneration"),
    /** Model Context Protocol capability. */
    MCP("mcp"),
    /** Shell execution capability. */
    SHELL("shell"),
    /** Web-search capability. */
    WEB_SEARCH("webSearch");

    private final String value;

    ToolCapability(String value) {
        this.value = value;
    }

    /**
     * Returns the stable capability value.
     *
     * @return stable capability value
     */
    public String value() {
        return value;
    }

    /**
     * Parses a stable capability value.
     *
     * @param value capability value
     * @return parsed capability
     * @throws ValidationException when the value is unsupported
     */
    public static ToolCapability fromValue(String value) {
        ToolValidation.requireNonBlank(value, "value");
        return Arrays.stream(values())
                .filter(capability -> capability.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new ValidationException("Unsupported tool capability '" + value + "'."));
    }

    @Override
    public String toString() {
        return value;
    }
}
