// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

/**
 * Indicates that OpenAI rate-limited a request.
 */
public final class OpenAIRateLimitException extends OpenAIProviderException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a rate-limit failure.
     *
     * @param requestId optional request identifier
     * @param errorCode optional provider error code
     */
    public OpenAIRateLimitException(String requestId, String errorCode) {
        super(
                "OpenAI rate limit exceeded" + suffix(OpenAIProviderException.safeIdentifier(requestId)) + '.',
                429,
                requestId,
                errorCode);
    }

    private static String suffix(String requestId) {
        return requestId == null ? "" : " (request " + requestId + ")";
    }
}
