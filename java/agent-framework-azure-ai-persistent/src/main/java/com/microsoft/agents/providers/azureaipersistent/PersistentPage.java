// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

import java.util.List;

/**
 * Represents one bounded page.
 *
 * @param <T> item type
 * @param items immutable items
 * @param nextCursor optional continuation cursor
 * @param hasMore whether another page may exist
 */
public record PersistentPage<T>(List<T> items, String nextCursor, boolean hasMore) {
    /** Creates and defensively copies a page. */
    public PersistentPage {
        items = items == null ? List.of() : List.copyOf(items);
        if (hasMore && (nextCursor == null || nextCursor.isBlank())) {
            throw new IllegalArgumentException("hasMore requires nextCursor.");
        }
    }
}
