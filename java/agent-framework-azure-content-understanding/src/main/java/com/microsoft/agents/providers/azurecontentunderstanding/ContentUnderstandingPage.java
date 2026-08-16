// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azurecontentunderstanding;

import java.util.List;

/**
 * Represents one bounded page of Content Understanding resources.
 *
 * @param items immutable resource items
 * @param nextCursor optional next-page cursor
 * @param hasMore whether another page is available
 * @param <T> resource type
 */
public record ContentUnderstandingPage<T>(List<T> items, String nextCursor, boolean hasMore) {
    /** Creates and validates a page. */
    public ContentUnderstandingPage {
        items = items == null ? List.of() : List.copyOf(items);
        if (hasMore && (nextCursor == null || nextCursor.isBlank())) {
            throw new IllegalArgumentException("hasMore requires nextCursor.");
        }
    }
}
