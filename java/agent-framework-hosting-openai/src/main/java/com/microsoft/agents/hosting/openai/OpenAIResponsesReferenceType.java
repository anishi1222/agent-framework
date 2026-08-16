// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.openai;

/** Identifies a mutable conversation head or an immutable response snapshot. */
public enum OpenAIResponsesReferenceType {
    /** Stable mutable conversation head. */
    CONVERSATION,
    /** Immutable response-chain snapshot. */
    RESPONSE
}
