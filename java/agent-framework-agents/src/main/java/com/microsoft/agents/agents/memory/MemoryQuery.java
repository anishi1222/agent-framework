// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.memory;

import com.microsoft.agents.core.ValidationException;

/**
 * Describes one bounded scoped memory search.
 *
 * @param scope mandatory tenant and application scope
 * @param text optional full-text query
 * @param embedding optional query embedding
 * @param filter immutable metadata filter
 * @param mode search strategy
 * @param topK maximum results
 * @param cursor optional opaque continuation cursor
 */
public record MemoryQuery(
        MemoryScope scope,
        String text,
        EmbeddingVector embedding,
        MemoryFilter filter,
        MemorySearchMode mode,
        int topK,
        String cursor) {
    /** Maximum results accepted by the shared contract. */
    public static final int MAX_TOP_K = 100;

    /** Creates a validated query. */
    public MemoryQuery {
        scope = MemoryValidation.requireNonNull(scope, "scope");
        text = MemoryValidation.optionalNonBlank(text, "text");
        filter = filter == null ? MemoryFilter.none() : filter;
        mode = MemoryValidation.requireNonNull(mode, "mode");
        if (topK <= 0 || topK > MAX_TOP_K) {
            throw new ValidationException("topK must be between 1 and " + MAX_TOP_K + ".");
        }
        cursor = MemoryValidation.optionalNonBlank(cursor, "cursor");
        if ((mode == MemorySearchMode.FULL_TEXT || mode == MemorySearchMode.HYBRID) && text == null) {
            throw new ValidationException(mode + " search requires text.");
        }
        if ((mode == MemorySearchMode.VECTOR || mode == MemorySearchMode.HYBRID) && embedding == null) {
            throw new ValidationException(mode + " search requires an embedding.");
        }
    }

    /**
     * Creates a query without a continuation cursor.
     *
     * @param scope mandatory tenant and application scope
     * @param text optional full-text query
     * @param embedding optional query embedding
     * @param filter immutable metadata filter
     * @param mode search strategy
     * @param topK maximum results
     */
    public MemoryQuery(
            MemoryScope scope,
            String text,
            EmbeddingVector embedding,
            MemoryFilter filter,
            MemorySearchMode mode,
            int topK) {
        this(scope, text, embedding, filter, mode, topK, null);
    }
}
