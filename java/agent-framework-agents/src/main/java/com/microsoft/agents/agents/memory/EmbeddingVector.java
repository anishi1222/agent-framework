// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.memory;

import com.microsoft.agents.core.ValidationException;
import java.util.ArrayList;
import java.util.List;

/**
 * Contains one bounded immutable embedding vector.
 *
 * @param values finite vector components
 */
public record EmbeddingVector(List<Double> values) {
    /** Maximum dimension accepted by the provider-neutral model. */
    public static final int MAX_DIMENSIONS = 8192;

    /** Creates a validated immutable embedding vector. */
    public EmbeddingVector {
        MemoryValidation.requireNonNull(values, "values");
        if (values.isEmpty() || values.size() > MAX_DIMENSIONS) {
            throw new ValidationException("Embedding dimensions must be between 1 and " + MAX_DIMENSIONS + ".");
        }
        ArrayList<Double> copy = new ArrayList<>(values.size());
        for (Double value : values) {
            if (value == null || !Double.isFinite(value)) {
                throw new ValidationException("Embedding vector values must be finite.");
            }
            copy.add(value);
        }
        values = List.copyOf(copy);
    }

    /**
     * Returns the vector dimension.
     *
     * @return positive dimension
     */
    public int dimensions() {
        return values.size();
    }
}
