// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.memory;

import com.microsoft.agents.core.ValidationException;

/**
 * Configures bounded untrusted memory-context injection.
 *
 * @param topK maximum retrieved memories
 * @param characterBudget maximum injected characters
 * @param maxQueryCharacters maximum source-query characters
 * @param maxSnippetCharacters maximum characters from one memory
 * @param persistRetrievedContent whether successful runs append the injected reference message to
 *     the active session
 */
public record MemoryContextOptions(
        int topK,
        int characterBudget,
        int maxQueryCharacters,
        int maxSnippetCharacters,
        boolean persistRetrievedContent) {
    /** Creates validated bounded options. */
    public MemoryContextOptions {
        if (topK <= 0 || topK > MemoryQuery.MAX_TOP_K) {
            throw new ValidationException("topK is outside the supported range.");
        }
        if (characterBudget <= 0 || characterBudget > 100_000) {
            throw new ValidationException("characterBudget must be between 1 and 100000.");
        }
        if (maxQueryCharacters <= 0 || maxQueryCharacters > 100_000) {
            throw new ValidationException("maxQueryCharacters must be between 1 and 100000.");
        }
        if (maxSnippetCharacters <= 0 || maxSnippetCharacters > characterBudget) {
            throw new ValidationException("maxSnippetCharacters must be positive and not exceed characterBudget.");
        }
    }

    /**
     * Returns conservative defaults.
     *
     * @return default context options
     */
    public static MemoryContextOptions defaults() {
        return new MemoryContextOptions(5, 8_000, 8_000, 1_500, false);
    }
}
