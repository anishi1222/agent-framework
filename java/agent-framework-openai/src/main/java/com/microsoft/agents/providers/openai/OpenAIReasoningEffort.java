// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

/**
 * Selects the requested OpenAI reasoning effort.
 */
public enum OpenAIReasoningEffort {
    /** Disables reasoning effort when supported. */
    NONE,
    /** Uses minimal reasoning effort. */
    MINIMAL,
    /** Uses low reasoning effort. */
    LOW,
    /** Uses medium reasoning effort. */
    MEDIUM,
    /** Uses high reasoning effort. */
    HIGH,
    /** Uses extra-high reasoning effort. */
    XHIGH,
    /** Uses the maximum reasoning effort. */
    MAX
}
