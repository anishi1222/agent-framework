// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.memory;

import com.microsoft.agents.core.ValidationException;
import java.util.OptionalDouble;

/**
 * Represents one ranked memory search result.
 *
 * @param record detached memory record
 * @param score finite provider-native score, or {@link Double#NaN} when the backend exposes only
 *     rank
 * @param rank one-based ordinal in provider rank order
 * @param provenance record origin and citation
 */
public record MemorySearchResult(MemoryRecord record, double score, int rank, MemoryProvenance provenance) {
    /** Creates a validated result. */
    public MemorySearchResult {
        record = MemoryValidation.requireNonNull(record, "record");
        provenance = MemoryValidation.requireNonNull(provenance, "provenance");
        if (Double.isInfinite(score)) {
            throw new ValidationException("Memory search score must be finite or Double.NaN when unavailable.");
        }
        if (rank <= 0) {
            throw new ValidationException("Memory search rank must be positive.");
        }
    }

    /**
     * Reports whether the provider supplied a score.
     *
     * @return {@code true} when {@link #score()} is not the no-score sentinel
     */
    public boolean hasScore() {
        return !Double.isNaN(score);
    }

    /**
     * Returns the provider score when available.
     *
     * @return optional provider-native score
     */
    public OptionalDouble optionalScore() {
        return hasScore() ? OptionalDouble.of(score) : OptionalDouble.empty();
    }

    /**
     * Creates a rank-only result when the provider doesn't expose its scoring function.
     *
     * @param record detached memory record
     * @param rank one-based ordinal in provider rank order
     * @param provenance record origin and citation
     * @return rank-only result
     */
    public static MemorySearchResult ranked(MemoryRecord record, int rank, MemoryProvenance provenance) {
        return new MemorySearchResult(record, Double.NaN, rank, provenance);
    }
}
