// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

/**
 * Selects the requested OpenAI reasoning-summary detail.
 */
public enum OpenAIReasoningSummary {
    /** Lets OpenAI select the summary detail. */
    AUTO,
    /** Requests a concise summary. */
    CONCISE,
    /** Requests a detailed summary. */
    DETAILED
}
