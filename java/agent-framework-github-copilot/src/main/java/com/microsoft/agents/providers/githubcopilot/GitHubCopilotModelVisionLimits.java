// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import java.util.List;

/**
 * Describes image-input limits reported by the official SDK.
 *
 * @param supportedMediaTypes immutable supported media types
 * @param maxPromptImages maximum images per prompt
 * @param maxPromptImageSize maximum image size in bytes
 */
public record GitHubCopilotModelVisionLimits(
        List<String> supportedMediaTypes, int maxPromptImages, int maxPromptImageSize) {
    /** Creates and defensively copies vision limits. */
    public GitHubCopilotModelVisionLimits {
        supportedMediaTypes = supportedMediaTypes == null ? List.of() : List.copyOf(supportedMediaTypes);
        if (maxPromptImages < 0 || maxPromptImageSize < 0) {
            throw new IllegalArgumentException("Vision limits must not be negative.");
        }
    }
}
