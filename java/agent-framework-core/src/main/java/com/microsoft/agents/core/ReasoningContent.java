// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Map;

/**
 * Represents provider-neutral reasoning text or protected reasoning data.
 *
 * @param id optional stable reasoning segment identifier
 * @param text optional visible reasoning text
 * @param protectedData optional opaque protected reasoning data
 * @param metadata immutable additive metadata
 */
public record ReasoningContent(String id, String text, String protectedData, Map<String, StateValue> metadata)
        implements Content {
    /** Creates validated reasoning content. */
    public ReasoningContent {
        id = CoreValidation.optionalNonBlank(id, "id");
        if (text == null && protectedData == null) {
            throw new ValidationException("Reasoning content requires text or protectedData.");
        }
        metadata = CoreValidation.copyStateMap(metadata, "metadata");
    }

    /**
     * Creates visible reasoning text without metadata.
     *
     * @param id optional stable segment identifier
     * @param text visible reasoning text
     */
    public ReasoningContent(String id, String text) {
        this(id, text, null, Map.of());
    }

    @Override
    public String kind() {
        return "reasoning";
    }
}
