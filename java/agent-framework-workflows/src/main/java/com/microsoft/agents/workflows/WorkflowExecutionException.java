// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import java.util.Objects;

/** Identifies the node and superstep at which workflow execution failed. */
public final class WorkflowExecutionException extends WorkflowException {
    private static final long serialVersionUID = 1L;

    private final transient NodeId nodeId;

    private final int superstep;

    /**
     * Creates a node execution failure.
     *
     * @param nodeId failing node identifier
     * @param superstep zero-based superstep
     * @param cause executor failure
     */
    public WorkflowExecutionException(NodeId nodeId, int superstep, Throwable cause) {
        super(
                "Workflow node '" + Objects.requireNonNull(nodeId, "nodeId") + "' failed at superstep " + superstep
                        + ".",
                Objects.requireNonNull(cause, "cause"));
        if (superstep < 0) {
            throw new IllegalArgumentException("superstep must not be negative.");
        }
        this.nodeId = nodeId;
        this.superstep = superstep;
    }

    /**
     * Returns the failing node.
     *
     * @return failing node identifier
     */
    public NodeId nodeId() {
        return nodeId;
    }

    /**
     * Returns the zero-based failing superstep.
     *
     * @return failing superstep
     */
    public int superstep() {
        return superstep;
    }
}
