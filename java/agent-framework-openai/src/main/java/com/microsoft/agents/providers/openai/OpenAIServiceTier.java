// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

/**
 * Selects the OpenAI processing tier.
 */
public enum OpenAIServiceTier {
    /** Uses the project-configured tier. */
    AUTO,
    /** Uses standard processing. */
    DEFAULT,
    /** Uses flex processing. */
    FLEX,
    /** Uses scale processing when available. */
    SCALE,
    /** Uses priority processing. */
    PRIORITY,
    /** Requests fast processing. */
    FAST
}
