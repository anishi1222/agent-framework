// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

/**
 * Indicates a sanitized OpenAI SDK or transport failure without an HTTP response.
 */
public final class OpenAISdkException extends OpenAIProviderException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an SDK failure.
     *
     * @param errorCode optional sanitized classification
     */
    public OpenAISdkException(String errorCode) {
        super("OpenAI SDK request failed.", null, null, errorCode);
    }
}
