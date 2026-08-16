// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

/**
 * Indicates that an OpenAI request timed out.
 */
public final class OpenAITimeoutException extends OpenAIProviderException {
    private static final long serialVersionUID = 1L;

    /** Creates a timeout failure without retaining transport details. */
    public OpenAITimeoutException() {
        super("OpenAI request timed out.", null, null, "timeout");
    }
}
