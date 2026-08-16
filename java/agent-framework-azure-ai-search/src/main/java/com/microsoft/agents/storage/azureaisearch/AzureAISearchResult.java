// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.azureaisearch;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import java.util.Map;

/**
 * Describes one bounded retrieved reference before context injection.
 *
 * @param recordId non-blank service or document identifier
 * @param text non-blank retrieved text
 * @param citation non-blank citation
 * @param score optional finite service score
 * @param rank one-based service rank
 * @param metadata sanitized provider metadata
 */
public record AzureAISearchResult(
        String recordId, String text, String citation, Double score, int rank, Map<String, StateValue> metadata) {
    private static final int MAX_RECORD_ID_CHARACTERS = 2_048;

    private static final int MAX_TEXT_CHARACTERS = 100_000;

    private static final int MAX_CITATION_CHARACTERS = 2_048;

    /** Creates a validated immutable result. */
    public AzureAISearchResult {
        recordId = required(recordId, MAX_RECORD_ID_CHARACTERS, "recordId");
        text = required(text, MAX_TEXT_CHARACTERS, "text");
        citation = required(citation, MAX_CITATION_CHARACTERS, "citation");
        if (score != null && !Double.isFinite(score)) {
            throw new ValidationException("score must be finite when present.");
        }
        if (rank <= 0) {
            throw new ValidationException("rank must be positive.");
        }
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }

    @Override
    public String toString() {
        return "AzureAISearchResult[recordId=[REDACTED], text=[REDACTED], citation=[REDACTED], score="
                + score
                + ", rank="
                + rank
                + ", metadataKeys="
                + metadata.keySet()
                + "]";
    }

    private static String required(String value, int maximum, String name) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(name + " must not be blank.");
        }
        if (value.length() > maximum) {
            throw new ValidationException(name + " exceeds the supported length.");
        }
        return value;
    }
}
