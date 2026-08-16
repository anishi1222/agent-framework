// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.azure;

/** Reports Azure authentication failure without retaining credential material. */
public final class AzureAuthenticationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an authentication exception.
     *
     * @param message sanitized message
     */
    public AzureAuthenticationException(String message) {
        super(message);
    }

    /**
     * Creates an authentication exception.
     *
     * @param message sanitized message
     * @param cause provider failure
     */
    public AzureAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
