// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

/**
 * Represents one provider-neutral embedding vector.
 *
 * <p>Providers may expose specialized immutable vector implementations for integer, binary, or
 * other embedding encodings without leaking provider SDK types.
 */
public interface EmbeddingVector {
    /**
     * Returns the logical vector dimension.
     *
     * @return non-negative dimension
     */
    int dimensions();
}
