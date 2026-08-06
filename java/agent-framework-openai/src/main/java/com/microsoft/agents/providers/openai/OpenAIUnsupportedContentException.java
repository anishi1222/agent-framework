// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import com.microsoft.agents.core.ValidationException;

/**
 * Indicates that framework content cannot be represented by the OpenAI Responses API.
 */
public final class OpenAIUnsupportedContentException extends ValidationException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an unsupported-content failure.
     *
     * @param message safe validation description
     */
    public OpenAIUnsupportedContentException(String message) {
        super(message);
    }
}
