// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

/** Classifies the explicit terminal boundary of one hosted execution phase. */
public enum HostingOutcomeStatus {
    /** The execution completed successfully. */
    COMPLETED("completed"),
    /** The execution is suspended pending non-approval input. */
    INPUT_REQUIRED("input-required"),
    /** The execution is suspended pending tool approval. */
    APPROVAL_REQUIRED("approval-required"),
    /** The execution failed with a sanitized error. */
    FAILED("failed"),
    /** The execution was cancelled. */
    CANCELLED("cancelled"),
    /** The execution was cancelled because a bounded stream overflowed. */
    OVERFLOW("overflow");

    private final String value;

    HostingOutcomeStatus(String value) {
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
}
