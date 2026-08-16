// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.memory;

import java.util.List;

/**
 * Contains one immutable bounded page and an opaque continuation cursor.
 *
 * @param items detached page items
 * @param cursor next-page cursor, or {@code null}
 * @param <T> item type
 */
public record MemoryPage<T>(List<T> items, String cursor) {
    /** Creates a validated immutable page. */
    public MemoryPage {
        items = List.copyOf(MemoryValidation.requireNonNull(items, "items"));
        items.forEach(item -> MemoryValidation.requireNonNull(item, "item"));
        cursor = MemoryValidation.optionalNonBlank(cursor, "cursor");
    }
}
