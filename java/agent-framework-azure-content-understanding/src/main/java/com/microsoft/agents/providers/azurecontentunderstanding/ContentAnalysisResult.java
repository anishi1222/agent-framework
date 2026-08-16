// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azurecontentunderstanding;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Represents a terminal Content Understanding analysis.
 *
 * @param operationId operation identifier
 * @param status terminal status
 * @param analyzerId analyzer identifier
 * @param apiVersion service API version
 * @param createdAt optional creation time
 * @param stringEncoding optional span encoding
 * @param warnings immutable warnings
 * @param contents immutable extracted content
 * @param usage immutable usage dimensions
 */
public record ContentAnalysisResult(
        String operationId,
        ContentOperationStatus status,
        String analyzerId,
        String apiVersion,
        Instant createdAt,
        String stringEncoding,
        List<ContentWarning> warnings,
        List<AnalyzedContent> contents,
        Map<String, Number> usage) {
    /** Creates and defensively copies an analysis result. */
    public ContentAnalysisResult {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank.");
        }
        status = java.util.Objects.requireNonNull(status, "status");
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        contents = contents == null ? List.of() : List.copyOf(contents);
        usage = usage == null ? Map.of() : Map.copyOf(usage);
    }
}
