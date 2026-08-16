// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azurecontentunderstanding;

import com.microsoft.agents.core.StateValue;

/**
 * Represents one extracted document, image, audio, or video content unit.
 *
 * @param kind content kind
 * @param mimeType detected MIME type
 * @param analyzerId producing analyzer
 * @param category optional classification
 * @param path optional source path
 * @param markdown optional extracted Markdown
 * @param fields framework-owned extracted fields
 */
public record AnalyzedContent(
        String kind,
        String mimeType,
        String analyzerId,
        String category,
        String path,
        String markdown,
        StateValue.ObjectValue fields) {
    /** Creates and validates analyzed content. */
    public AnalyzedContent {
        if (kind == null || kind.isBlank() || mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("kind and mimeType must not be blank.");
        }
        fields = fields == null ? StateValue.object(java.util.Map.of()) : fields;
    }
}
