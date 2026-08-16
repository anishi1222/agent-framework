// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import java.util.List;

/**
 * Describes one model reported by the Copilot CLI.
 *
 * @param id model identifier
 * @param name display name
 * @param supportsVision whether image input is supported
 * @param supportsReasoningEffort whether reasoning effort is configurable
 * @param maxPromptTokens optional prompt-token limit
 * @param maxContextWindowTokens context-window limit
 * @param visionLimits optional image-input limits
 * @param billing optional billing information
 * @param supportedReasoningEfforts immutable supported values
 * @param defaultReasoningEffort optional default value
 */
public record GitHubCopilotModel(
        String id,
        String name,
        boolean supportsVision,
        boolean supportsReasoningEffort,
        Integer maxPromptTokens,
        int maxContextWindowTokens,
        GitHubCopilotModelVisionLimits visionLimits,
        GitHubCopilotModelBilling billing,
        List<String> supportedReasoningEfforts,
        String defaultReasoningEffort) {
    /** Creates and defensively copies model metadata. */
    public GitHubCopilotModel {
        if (id == null || id.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("id and name must not be blank.");
        }
        if (maxPromptTokens != null && maxPromptTokens <= 0) {
            throw new IllegalArgumentException("maxPromptTokens must be positive.");
        }
        if (maxContextWindowTokens < 0) {
            throw new IllegalArgumentException("maxContextWindowTokens must not be negative.");
        }
        supportedReasoningEfforts = List.copyOf(supportedReasoningEfforts);
        defaultReasoningEffort =
                defaultReasoningEffort == null || defaultReasoningEffort.isBlank() ? null : defaultReasoningEffort;
    }
}
