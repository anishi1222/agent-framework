// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.memory;

import com.microsoft.agents.core.ValidationException;

/**
 * Describes one bounded scoped memory listing.
 *
 * @param scope mandatory tenant and application scope
 * @param filter immutable metadata filter
 * @param pageSize maximum records in one page
 * @param cursor optional opaque continuation cursor
 */
public record MemoryListRequest(MemoryScope scope, MemoryFilter filter, int pageSize, String cursor) {
    /** Maximum records accepted in one page. */
    public static final int MAX_PAGE_SIZE = 100;

    /** Creates a validated list request. */
    public MemoryListRequest {
        scope = MemoryValidation.requireNonNull(scope, "scope");
        filter = filter == null ? MemoryFilter.none() : filter;
        if (pageSize <= 0 || pageSize > MAX_PAGE_SIZE) {
            throw new ValidationException("pageSize must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        cursor = MemoryValidation.optionalNonBlank(cursor, "cursor");
    }
}
