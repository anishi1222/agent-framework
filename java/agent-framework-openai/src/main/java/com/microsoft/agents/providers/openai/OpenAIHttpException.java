// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

/**
 * Indicates an unsuccessful OpenAI HTTP response other than authentication or rate limiting.
 */
public final class OpenAIHttpException extends OpenAIProviderException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an HTTP failure.
     *
     * @param statusCode HTTP status
     * @param requestId optional request identifier
     * @param errorCode optional provider error code
     */
    public OpenAIHttpException(int statusCode, String requestId, String errorCode) {
        super(
                "OpenAI request failed with HTTP "
                        + statusCode
                        + suffix(OpenAIProviderException.safeIdentifier(requestId))
                        + '.',
                statusCode,
                requestId,
                errorCode);
    }

    private static String suffix(String requestId) {
        return requestId == null ? "" : " (request " + requestId + ")";
    }
}
