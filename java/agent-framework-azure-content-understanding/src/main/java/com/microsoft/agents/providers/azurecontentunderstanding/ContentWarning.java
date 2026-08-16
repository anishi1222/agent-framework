// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azurecontentunderstanding;

/**
 * Represents a sanitized analysis warning.
 *
 * @param code optional service warning code
 * @param message optional sanitized warning message
 */
public record ContentWarning(String code, String message) {
    /** Creates a warning. */
    public ContentWarning {
        if (code != null && code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank.");
        }
        if (message != null && message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank.");
        }
    }
}
