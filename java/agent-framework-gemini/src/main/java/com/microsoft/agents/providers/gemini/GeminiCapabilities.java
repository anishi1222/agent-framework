// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.gemini;

/**
 * Describes Gemini capabilities implemented by this adapter.
 *
 * @param text text messages
 * @param imageInput image input
 * @param documentInput document input
 * @param audioInput audio input
 * @param videoInput video input
 * @param uriInput provider-addressable URI input
 * @param functionTools function tools
 * @param parallelToolCalls model-controlled parallel calls
 * @param structuredOutput JSON Schema output
 * @param reasoning thought summaries and signatures
 * @param grounding grounding metadata
 * @param safety safety and prompt-block metadata
 */
public record GeminiCapabilities(
        boolean text,
        boolean imageInput,
        boolean documentInput,
        boolean audioInput,
        boolean videoInput,
        boolean uriInput,
        boolean functionTools,
        boolean parallelToolCalls,
        boolean structuredOutput,
        boolean reasoning,
        boolean grounding,
        boolean safety) {
    private static final GeminiCapabilities CURRENT =
            new GeminiCapabilities(true, true, true, true, true, true, true, true, true, true, true, true);

    /** Returns current capability flags. */
    public static GeminiCapabilities current() {
        return CURRENT;
    }
}
