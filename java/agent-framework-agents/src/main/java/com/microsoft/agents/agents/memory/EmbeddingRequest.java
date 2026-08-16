// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.memory;

/**
 * Requests an embedding within an explicit tenant scope.
 *
 * @param scope tenant and application scope
 * @param text bounded source text
 */
public record EmbeddingRequest(MemoryScope scope, String text) {
    /** Creates a validated embedding request. */
    public EmbeddingRequest {
        scope = MemoryValidation.requireNonNull(scope, "scope");
        text = MemoryValidation.requireNonBlank(text, "text");
    }
}
