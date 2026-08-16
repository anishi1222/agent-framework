// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.ollama;

/**
 * Describes the Ollama features implemented by this adapter.
 *
 * @param text text messages
 * @param imageData inline image input
 * @param imageUrl remote image input
 * @param documentInput document input
 * @param audioInput audio input
 * @param functionTools function tools
 * @param parallelToolCalls parallel tool calls
 * @param structuredOutput JSON Schema output
 * @param reasoning streamed thinking output
 */
public record OllamaCapabilities(
        boolean text,
        boolean imageData,
        boolean imageUrl,
        boolean documentInput,
        boolean audioInput,
        boolean functionTools,
        boolean parallelToolCalls,
        boolean structuredOutput,
        boolean reasoning) {
    private static final OllamaCapabilities CURRENT =
            new OllamaCapabilities(true, true, false, false, false, true, true, true, true);

    /** Returns current capability flags. */
    public static OllamaCapabilities current() {
        return CURRENT;
    }
}
