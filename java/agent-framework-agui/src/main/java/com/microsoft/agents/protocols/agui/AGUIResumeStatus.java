// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

/** Identifies the two official interrupt resume statuses. */
public enum AGUIResumeStatus {
    /** The caller supplied a response. */
    RESOLVED("resolved"),
    /** The caller abandoned the interrupt without meaningful input. */
    CANCELLED("cancelled");

    private final String value;

    AGUIResumeStatus(String value) {
        this.value = value;
    }

    /**
     * Returns the lower-case wire value.
     *
     * @return wire value
     */
    public String value() {
        return value;
    }

    /**
     * Resolves a resume status.
     *
     * @param value wire value
     * @return status
     */
    public static AGUIResumeStatus fromValue(String value) {
        for (AGUIResumeStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw AGUIValidation.invalid("Unknown AG-UI resume status.");
    }
}
