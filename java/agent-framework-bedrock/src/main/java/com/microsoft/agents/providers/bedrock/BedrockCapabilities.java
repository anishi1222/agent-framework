// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.bedrock;

/**
 * Describes Amazon Bedrock Converse capabilities implemented by this adapter.
 *
 * @param text text messages
 * @param imageData inline image input
 * @param imageUrl remote image input
 * @param documentData inline document input
 * @param audioData inline audio input
 * @param functionTools function tools
 * @param parallelToolCalls model-controlled parallel calls
 * @param structuredOutput model-dependent JSON Schema output
 * @param reasoning reasoning blocks
 * @param citations citation blocks
 * @param guardrails guardrail stop and trace metadata
 */
public record BedrockCapabilities(
        boolean text,
        boolean imageData,
        boolean imageUrl,
        boolean documentData,
        boolean audioData,
        boolean functionTools,
        boolean parallelToolCalls,
        boolean structuredOutput,
        boolean reasoning,
        boolean citations,
        boolean guardrails) {
    private static final BedrockCapabilities CURRENT =
            new BedrockCapabilities(true, true, false, true, true, true, true, true, true, true, true);

    /** Returns current capability flags. */
    public static BedrockCapabilities current() {
        return CURRENT;
    }
}
