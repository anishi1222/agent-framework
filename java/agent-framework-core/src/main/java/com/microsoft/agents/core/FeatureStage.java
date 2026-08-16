// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

/** Identifies the lifecycle stage of a public framework feature. */
public enum FeatureStage {
    /** API may change incompatibly or be removed without notice. */
    EXPERIMENTAL("experimental"),

    /** API is stabilizing and may receive minor refinements before general availability. */
    RELEASE_CANDIDATE("release_candidate");

    private final String value;

    FeatureStage(String value) {
        this.value = value;
    }

    /**
     * Returns the stable serialized stage name.
     *
     * @return lowercase stage name
     */
    public String value() {
        return value;
    }
}
