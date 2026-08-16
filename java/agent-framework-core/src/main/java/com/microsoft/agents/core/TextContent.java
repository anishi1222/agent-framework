// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Map;
import java.util.Objects;

/**
 * Represents plain text content.
 *
 * @param text text, which may be empty but never {@code null}
 * @param metadata immutable additive metadata
 */
public record TextContent(String text, Map<String, StateValue> metadata) implements Content {
    /** Creates validated text content. */
    public TextContent {
        Objects.requireNonNull(text, "text");
        metadata = CoreValidation.copyStateMap(metadata, "metadata");
    }

    /**
     * Creates text content without metadata.
     *
     * @param text text, which may be empty
     */
    public TextContent(String text) {
        this(text, Map.of());
    }

    @Override
    public String kind() {
        return "text";
    }

    @Override
    public String toString() {
        return text;
    }
}
