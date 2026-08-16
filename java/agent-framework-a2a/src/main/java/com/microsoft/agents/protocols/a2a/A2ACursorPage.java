// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import java.util.List;

/**
 * Represents one cursor-paginated result page.
 *
 * @param <T> item type
 * @param items ordered items
 * @param nextPageToken opaque next-page token, or {@code null}
 * @param pageSize effective page size
 * @param totalSize optional total size, or {@code null}
 */
public record A2ACursorPage<T>(List<T> items, String nextPageToken, int pageSize, Long totalSize) {
    /** Creates a validated immutable page. */
    public A2ACursorPage {
        items = A2AValidation.list(items, "items");
        nextPageToken = A2AValidation.optionalNonBlank(nextPageToken, "nextPageToken");
        A2AValidation.positive(pageSize, "pageSize");
        if (totalSize != null && totalSize < 0) {
            throw new com.microsoft.agents.core.ValidationException("totalSize must not be negative.");
        }
    }

    /**
     * Reports whether another page is available.
     *
     * @return next-page flag
     */
    public boolean hasNextPage() {
        return nextPageToken != null;
    }
}
