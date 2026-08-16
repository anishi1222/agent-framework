// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azurecontentunderstanding;

import com.microsoft.agents.core.StateValue;

/**
 * Defines an analyzer resource.
 *
 * @param analyzerId analyzer identifier
 * @param definition complete analyzer JSON-shaped definition
 */
public record ContentAnalyzerRequest(String analyzerId, StateValue.ObjectValue definition) {
    /** Creates and validates an analyzer request. */
    public ContentAnalyzerRequest {
        if (analyzerId == null || analyzerId.isBlank()) {
            throw new IllegalArgumentException("analyzerId must not be blank.");
        }
        definition = java.util.Objects.requireNonNull(definition, "definition");
    }
}
