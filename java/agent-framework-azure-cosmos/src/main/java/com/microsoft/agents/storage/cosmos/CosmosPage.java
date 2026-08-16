// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import java.util.List;

/**
 * Contains one immutable bounded adapter page and opaque continuation cursor.
 *
 * @param items detached items
 * @param cursor next-page cursor, or {@code null}
 * @param diagnostics sanitized request diagnostics
 * @param <T> item type
 */
public record CosmosPage<T>(List<T> items, String cursor, CosmosOperationDiagnostics diagnostics) {
    /** Creates a validated immutable page. */
    public CosmosPage {
        items = List.copyOf(CosmosValidation.requireNonNull(items, "items"));
        items.forEach(item -> CosmosValidation.requireNonNull(item, "item"));
        if (cursor != null && cursor.isBlank()) {
            throw new com.microsoft.agents.core.ValidationException("cursor must not be blank when present.");
        }
    }
}
