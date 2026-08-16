// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

/**
 * Indicates an unsupported or internally inconsistent OpenAI response.
 */
public final class OpenAIProtocolException extends OpenAIProviderException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a protocol failure.
     *
     * @param message safe protocol description
     * @param requestId optional request identifier
     * @param errorCode optional provider error code
     */
    public OpenAIProtocolException(String message, String requestId, String errorCode) {
        super(message, null, requestId, errorCode);
    }
}
