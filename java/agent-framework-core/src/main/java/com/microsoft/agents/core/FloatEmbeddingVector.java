// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Contains one immutable finite floating-point embedding vector.
 *
 * @param values finite vector components
 */
public record FloatEmbeddingVector(List<Double> values) implements EmbeddingVector {
    /** Maximum dimension accepted by the standard framework vector. */
    public static final int MAX_DIMENSIONS = 8192;

    /** Creates a validated defensive copy. */
    public FloatEmbeddingVector {
        Objects.requireNonNull(values, "values");
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
     * Converts finite single-precision provider values into the standard vector.
     *
     * @param values provider vector values
     * @return immutable framework vector
     */
    public static FloatEmbeddingVector fromFloats(List<Float> values) {
        Objects.requireNonNull(values, "values");
        ArrayList<Double> converted = new ArrayList<>(values.size());
        for (Float value : values) {
            if (value == null || !Float.isFinite(value)) {
                throw new ValidationException("Embedding vector values must be finite.");
            }
            converted.add((double) value);
        }
        return new FloatEmbeddingVector(converted);
    }

    @Override
    public int dimensions() {
        return values.size();
    }
}
