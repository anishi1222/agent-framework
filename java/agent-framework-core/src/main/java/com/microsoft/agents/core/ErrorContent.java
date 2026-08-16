// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Map;

/**
 * Represents a provider-neutral content error.
 *
 * @param message non-blank error message
 * @param errorCode optional stable error code
 * @param details optional diagnostic details
 * @param metadata immutable additive metadata
 */
public record ErrorContent(String message, String errorCode, String details, Map<String, StateValue> metadata)
        implements Content {
    /** Creates validated error content. */
    public ErrorContent {
        message = CoreValidation.requireNonBlank(message, "message");
        errorCode = CoreValidation.optionalNonBlank(errorCode, "errorCode");
        metadata = CoreValidation.copyStateMap(metadata, "metadata");
    }

    /**
     * Creates error content without metadata.
     *
     * @param message non-blank error message
     * @param errorCode optional stable error code
     * @param details optional diagnostic details
     */
    public ErrorContent(String message, String errorCode, String details) {
        this(message, errorCode, details, Map.of());
    }

    @Override
    public String kind() {
        return "error";
    }
}
