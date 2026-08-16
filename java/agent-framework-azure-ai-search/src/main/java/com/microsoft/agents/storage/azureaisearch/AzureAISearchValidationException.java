// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.azureaisearch;

import com.microsoft.agents.core.ValidationException;

/** Reports an Azure AI Search index or knowledge-base contract mismatch. */
public final class AzureAISearchValidationException extends ValidationException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a validation failure.
     *
     * @param message sanitized failure description
     */
    public AzureAISearchValidationException(String message) {
        super(message);
    }

    /**
     * Creates a validation failure with a cause.
     *
     * @param message sanitized failure description
     * @param cause underlying failure
     */
    public AzureAISearchValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
