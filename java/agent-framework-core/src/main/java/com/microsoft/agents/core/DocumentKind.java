// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Arrays;

/**
 * Identifies a framework-owned persisted document schema.
 */
public enum DocumentKind {
    /** Agent-session snapshot document. */
    AGENT_SESSION("agent-session"),
    /** Workflow-checkpoint document. */
    WORKFLOW_CHECKPOINT("workflow-checkpoint");

    private final String value;

    DocumentKind(String value) {
        this.value = value;
    }

    /**
     * Returns the stable envelope discriminator.
     *
     * @return document-kind value
     */
    public String value() {
        return value;
    }

    /**
     * Parses a stable envelope discriminator.
     *
     * @param value discriminator
     * @return document kind
     * @throws SerializationException when the discriminator is unknown
     */
    public static DocumentKind fromValue(String value) {
        return Arrays.stream(values())
                .filter(kind -> kind.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new SerializationException(
                        SerializationError.WRONG_DOCUMENT_KIND, "Unknown document kind '" + value + "'."));
    }
}
