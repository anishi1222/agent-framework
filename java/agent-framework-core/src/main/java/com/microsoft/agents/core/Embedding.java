// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Represents one immutable embedding with provider-neutral metadata.
 *
 * @param <V> vector representation
 * @param vector required immutable vector
 * @param model optional model or deployment identifier
 * @param createdAt optional provider creation time
 * @param metadata immutable JSON-shaped metadata
 */
public record Embedding<V extends EmbeddingVector>(
        V vector, String model, Instant createdAt, Map<String, StateValue> metadata) {
    /** Creates and validates an embedding. */
    public Embedding {
        vector = Objects.requireNonNull(vector, "vector");
        model = CoreValidation.optionalNonBlank(model, "model");
        metadata = CoreValidation.copyStateMap(metadata, "metadata");
    }

    /**
     * Creates an embedding without optional metadata.
     *
     * @param vector embedding vector
     */
    public Embedding(V vector) {
        this(vector, null, null, Map.of());
    }

    /**
     * Returns the logical vector dimension.
     *
     * @return vector dimension
     */
    public int dimensions() {
        return vector.dimensions();
    }
}
