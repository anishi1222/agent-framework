// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

/**
 * Reports an invalid conformance manifest or fixture.
 */
public final class ConformanceValidationException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a validation message.
     *
     * @param message validation failure detail
     */
    public ConformanceValidationException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a validation message and cause.
     *
     * @param message validation failure detail
     * @param cause underlying parse or resource failure
     */
    public ConformanceValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
