// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

/**
 * Indicates that a tool body returned a value that does not satisfy its declared output schema.
 */
public final class ToolOutputValidationException extends ToolBindingException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an output-validation exception.
     *
     * @param message output-validation failure description
     * @param cause underlying binding or schema-validation failure
     */
    public ToolOutputValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
