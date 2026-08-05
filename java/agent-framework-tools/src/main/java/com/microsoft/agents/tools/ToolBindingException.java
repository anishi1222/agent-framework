// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.ValidationException;

/**
 * Indicates that a schema or function argument cannot be generated or bound safely.
 */
public class ToolBindingException extends ValidationException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a binding exception.
     *
     * @param message binding failure description
     */
    public ToolBindingException(String message) {
        super(message);
    }

    /**
     * Creates a binding exception with a cause.
     *
     * @param message binding failure description
     * @param cause underlying cause
     */
    public ToolBindingException(String message, Throwable cause) {
        super(message, cause);
    }
}
