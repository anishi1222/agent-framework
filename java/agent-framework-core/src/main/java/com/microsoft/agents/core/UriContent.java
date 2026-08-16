// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * Represents media or data addressed by an external URI.
 *
 * @param uri absolute non-data URI
 * @param mediaType optional media type
 * @param metadata immutable additive metadata
 */
public record UriContent(URI uri, String mediaType, Map<String, StateValue> metadata) implements Content {
    /** Creates validated URI content. */
    public UriContent {
        Objects.requireNonNull(uri, "uri");
        if (!uri.isAbsolute() || "data".equalsIgnoreCase(uri.getScheme())) {
            throw new ValidationException("uri must be an absolute non-data URI.");
        }
        mediaType = CoreValidation.optionalNonBlank(mediaType, "mediaType");
        metadata = CoreValidation.copyStateMap(metadata, "metadata");
    }

    /**
     * Creates URI content without metadata.
     *
     * @param uri absolute non-data URI
     * @param mediaType optional media type
     */
    public UriContent(URI uri, String mediaType) {
        this(uri, mediaType, Map.of());
    }

    @Override
    public String kind() {
        return "uri";
    }
}
