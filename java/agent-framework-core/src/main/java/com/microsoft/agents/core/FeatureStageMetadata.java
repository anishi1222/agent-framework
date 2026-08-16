// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Objects;

/**
 * Runtime-visible lifecycle metadata for one staged API.
 *
 * @param stage lifecycle stage
 * @param featureId stable feature identifier
 */
public record FeatureStageMetadata(FeatureStage stage, String featureId) {
    /** Creates validated immutable metadata. */
    public FeatureStageMetadata {
        stage = Objects.requireNonNull(stage, "stage");
        featureId = CoreValidation.requireNonBlank(featureId, "featureId");
    }

    /**
     * Creates a concise warning message for a staged symbol.
     *
     * @param symbolName non-blank display name
     * @return warning text
     */
    public String warningMessage(String symbolName) {
        String safeName = CoreValidation.requireNonBlank(symbolName, "symbolName");
        return switch (stage) {
            case EXPERIMENTAL ->
                "["
                        + featureId
                        + "] "
                        + safeName
                        + " is experimental and may change or be removed in future versions without notice.";
            case RELEASE_CANDIDATE ->
                "["
                        + featureId
                        + "] "
                        + safeName
                        + " is in release-candidate stage and may receive minor refinements "
                        + "before it is generally available.";
        };
    }
}
