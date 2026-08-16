// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

/**
 * Defines stable session-timeline log levels supported by the official SDK.
 */
public enum GitHubCopilotLogLevel {
    /** Informational session message. */
    INFO("info"),
    /** Warning session message. */
    WARNING("warning"),
    /** Error session message. */
    ERROR("error");

    private final String sdkValue;

    GitHubCopilotLogLevel(String sdkValue) {
        this.sdkValue = sdkValue;
    }

    String sdkValue() {
        return sdkValue;
    }
}
