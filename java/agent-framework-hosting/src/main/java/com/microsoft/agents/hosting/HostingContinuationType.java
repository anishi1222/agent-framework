// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

/** Identifies the retained production continuation represented by an opaque process-local token. */
public enum HostingContinuationType {
    /** A chat-agent tool approval continuation. */
    APPROVAL("approval"),
    /** A workflow checkpoint continuation. */
    WORKFLOW_CHECKPOINT("workflow-checkpoint"),
    /** An application input continuation. */
    INPUT("input");

    private final String value;

    HostingContinuationType(String value) {
        this.value = value;
    }

    /**
     * Returns the stable wire value.
     *
     * @return wire value
     */
    public String value() {
        return value;
    }

    /**
     * Parses a stable wire value.
     *
     * @param value wire value
     * @return continuation type
     */
    public static HostingContinuationType fromValue(String value) {
        for (HostingContinuationType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new HostingException(HostingErrorCode.MALFORMED_REQUEST, "Unknown continuation type.");
    }
}
