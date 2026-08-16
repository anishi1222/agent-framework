// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import com.microsoft.agents.core.AgentExecutionException;

/**
 * Reports a sanitized GitHub Copilot configuration, transport, protocol, or service failure.
 */
public final class GitHubCopilotProviderException extends AgentExecutionException {
    private static final long serialVersionUID = 1L;

    private final String kind;

    private final String code;

    /**
     * Creates a sanitized provider failure.
     *
     * @param message non-secret message
     * @param cause optional cause
     * @param kind stable failure category
     * @param code optional non-secret service or protocol code
     */
    public GitHubCopilotProviderException(String message, Throwable cause, String kind, String code) {
        super(message, cause);
        this.kind = requireNonBlank(kind, "kind");
        this.code = code == null || code.isBlank() ? null : code;
    }

    /**
     * Returns the stable failure category.
     *
     * @return failure category
     */
    public String kind() {
        return kind;
    }

    /**
     * Returns the optional service or protocol code.
     *
     * @return optional code
     */
    public String code() {
        return code;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
