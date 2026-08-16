// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azurecontentunderstanding;

import java.util.List;
import java.util.Map;

/**
 * Defines a Content Understanding analysis.
 *
 * @param analyzerId analyzer identifier
 * @param inputs non-empty content inputs
 * @param modelDeployments optional model deployment overrides
 */
public record ContentAnalysisRequest(
        String analyzerId, List<ContentInput> inputs, Map<String, String> modelDeployments) {
    /** Creates and defensively copies an analysis request. */
    public ContentAnalysisRequest {
        if (analyzerId == null || analyzerId.isBlank()) {
            throw new IllegalArgumentException("analyzerId must not be blank.");
        }
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("inputs must not be empty.");
        }
        modelDeployments = modelDeployments == null ? Map.of() : Map.copyOf(modelDeployments);
    }
}
