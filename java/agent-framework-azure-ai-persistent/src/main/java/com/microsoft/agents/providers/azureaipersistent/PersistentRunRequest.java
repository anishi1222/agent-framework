// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

import java.util.Map;

/**
 * Defines one persistent run.
 *
 * @param threadId thread identifier
 * @param agentId agent identifier
 * @param additionalInstructions optional per-run instructions
 * @param maxPromptTokens optional positive prompt-token budget
 * @param maxCompletionTokens optional positive completion-token budget
 * @param metadata immutable service metadata
 */
public record PersistentRunRequest(
        String threadId,
        String agentId,
        String additionalInstructions,
        Integer maxPromptTokens,
        Integer maxCompletionTokens,
        Map<String, String> metadata) {
    /** Creates and validates a run request. */
    public PersistentRunRequest {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be blank.");
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank.");
        }
        if (additionalInstructions != null && additionalInstructions.isBlank()) {
            throw new IllegalArgumentException("additionalInstructions must not be blank.");
        }
        if (maxPromptTokens != null && maxPromptTokens <= 0) {
            throw new IllegalArgumentException("maxPromptTokens must be positive.");
        }
        if (maxCompletionTokens != null && maxCompletionTokens <= 0) {
            throw new IllegalArgumentException("maxCompletionTokens must be positive.");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
