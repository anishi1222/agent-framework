// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.anthropic;

/**
 * Describes Anthropic Messages capabilities implemented by this adapter.
 *
 * @param text text messages
 * @param imageUrl remote image input
 * @param imageData inline image input
 * @param documentUrl remote PDF input
 * @param documentData inline PDF or plain-text document input
 * @param audioInput audio input
 * @param functionTools function tools
 * @param parallelToolCalls parallel tool calls
 * @param structuredOutput JSON Schema output
 * @param reasoning extended and redacted thinking
 * @param citations citation metadata
 */
public record AnthropicCapabilities(
        boolean text,
        boolean imageUrl,
        boolean imageData,
        boolean documentUrl,
        boolean documentData,
        boolean audioInput,
        boolean functionTools,
        boolean parallelToolCalls,
        boolean structuredOutput,
        boolean reasoning,
        boolean citations) {
    private static final AnthropicCapabilities CURRENT =
            new AnthropicCapabilities(true, true, true, true, true, false, true, true, true, true, true);

    /** Returns current capability flags. */
    public static AnthropicCapabilities current() {
        return CURRENT;
    }
}
