// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.mistral;

/**
 * Describes the provider features implemented by this adapter.
 *
 * @param text text messages
 * @param imageUrl remote image input
 * @param imageData inline image input
 * @param documentInput document input
 * @param audioInput audio input
 * @param functionTools function tools
 * @param parallelToolCalls parallel function calls
 * @param structuredOutput JSON Schema output
 * @param reasoning visible or protected reasoning
 * @param citations citation or grounding metadata
 */
public record MistralCapabilities(
        boolean text,
        boolean imageUrl,
        boolean imageData,
        boolean documentInput,
        boolean audioInput,
        boolean functionTools,
        boolean parallelToolCalls,
        boolean structuredOutput,
        boolean reasoning,
        boolean citations) {
    private static final MistralCapabilities CURRENT =
            new MistralCapabilities(true, true, true, false, false, true, true, true, false, false);

    /**
     * Returns the capabilities of the current adapter.
     *
     * @return immutable capability flags
     */
    public static MistralCapabilities current() {
        return CURRENT;
    }
}
