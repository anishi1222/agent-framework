// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import java.util.Arrays;

/** Classifies the execution boundary represented by a checkpoint. */
public enum WorkflowCheckpointStatus {
    /** More executors are pending. */
    RUNNING("running"),
    /** The run is suspended pending external input. */
    INPUT_REQUIRED("inputRequired"),
    /** The run reached a successful terminal boundary. */
    COMPLETED("completed");

    private final String value;

    WorkflowCheckpointStatus(String value) {
        this.value = value;
    }

    /**
     * Returns the stable serialized value.
     *
     * @return serialized status
     */
    public String value() {
        return value;
    }

    /**
     * Parses a serialized status.
     *
     * @param value serialized status
     * @return checkpoint status
     */
    public static WorkflowCheckpointStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equals(value))
                .findFirst()
                .orElseThrow(
                        () -> new WorkflowCheckpointException("Unknown workflow checkpoint status '" + value + "'."));
    }
}
