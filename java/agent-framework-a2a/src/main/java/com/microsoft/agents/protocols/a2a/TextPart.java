// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.StateValue;
import java.util.Map;
import java.util.Objects;

/**
 * Represents text content.
 *
 * @param text text, which may be empty
 * @param mediaType text media type
 * @param metadata immutable additive metadata
 */
public record TextPart(String text, String mediaType, Map<String, StateValue> metadata) implements Part {
    /** Creates validated text content. */
    public TextPart {
        text = Objects.requireNonNull(text, "text");
        mediaType = A2AValidation.nonBlank(mediaType, "mediaType");
        if (!mediaType.startsWith("text/")) {
            throw new com.microsoft.agents.core.ValidationException("TextPart mediaType must start with text/.");
        }
        metadata = A2AValidation.metadata(metadata, "metadata");
    }

    /**
     * Creates plain text without metadata.
     *
     * @param text text
     */
    public TextPart(String text) {
        this(text, "text/plain", Map.of());
    }
}
