// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation.foundry;

import java.util.List;

/**
 * Represents one bounded evaluation or discovery page.
 *
 * @param <T> item type
 * @param items immutable items
 * @param nextCursor optional next cursor
 * @param hasMore whether another page may exist
 */
public record FoundryEvaluationPage<T>(List<T> items, String nextCursor, boolean hasMore) {
    /** Creates and validates a page. */
    public FoundryEvaluationPage {
        items = items == null ? List.of() : List.copyOf(items);
        if (hasMore && (nextCursor == null || nextCursor.isBlank())) {
            throw new IllegalArgumentException("hasMore requires nextCursor.");
        }
    }
}
