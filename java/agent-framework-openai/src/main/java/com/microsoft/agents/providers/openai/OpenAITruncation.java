// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

/**
 * Selects OpenAI Responses context truncation behavior.
 */
public enum OpenAITruncation {
    /** Lets the service truncate older input items when needed. */
    AUTO,
    /** Rejects requests that exceed the context window. */
    DISABLED
}
