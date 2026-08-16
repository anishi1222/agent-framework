// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

/**
 * Indicates that OpenAI rejected authentication or authorization.
 */
public final class OpenAIAuthenticationException extends OpenAIProviderException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an authentication failure.
     *
     * @param statusCode HTTP status
     * @param requestId optional request identifier
     * @param errorCode optional provider error code
     */
    public OpenAIAuthenticationException(int statusCode, String requestId, String errorCode) {
        super(
                "OpenAI authentication failed with HTTP "
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
