// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

/** Classifies deterministic workflow lifecycle events. */
public enum WorkflowEventType {
    /** A logical workflow run started. */
    RUN_STARTED("run-started"),
    /** A superstep started. */
    SUPERSTEP_STARTED("superstep-started"),
    /** One node invocation started. */
    NODE_STARTED("node-started"),
    /** One node invocation completed successfully. */
    NODE_COMPLETED("node-completed"),
    /** One node invocation failed. */
    NODE_FAILED("node-failed"),
    /** Cancellation was requested for one pending branch. */
    CANCELLATION_REQUESTED("cancellation-requested"),
    /** One pending branch observed cancellation. */
    NODE_CANCELLED("node-cancelled"),
    /** One output was delivered to all fan-out targets. */
    FAN_OUT("fan-out"),
    /** One fan-in source value was buffered. */
    FAN_IN_BUFFERED("fan-in-buffered"),
    /** A complete fan-in epoch was released. */
    FAN_IN_RELEASED("fan-in-released"),
    /** Concurrent state writes committed atomically. */
    STATE_COMMITTED("state-committed"),
    /** A superstep completed successfully. */
    SUPERSTEP_COMPLETED("superstep-completed"),
    /** The designated output node produced a value. */
    OUTPUT("output"),
    /** A workflow checkpoint was saved. */
    CHECKPOINT_SAVED("checkpoint-saved"),
    /** A workflow checkpoint was loaded. */
    CHECKPOINT_LOADED("checkpoint-loaded"),
    /** A workflow resumed from a validated checkpoint. */
    WORKFLOW_RESUMED("workflow-resumed"),
    /** The run completed successfully. */
    RUN_COMPLETED("run-completed"),
    /** The run failed. */
    RUN_FAILED("run-failed"),
    /** The run was cancelled. */
    RUN_CANCELLED("run-cancelled");

    private final String value;

    WorkflowEventType(String value) {
        this.value = value;
    }

    /**
     * Returns the stable event discriminator.
     *
     * @return event discriminator
     */
    public String value() {
        return value;
    }
}
