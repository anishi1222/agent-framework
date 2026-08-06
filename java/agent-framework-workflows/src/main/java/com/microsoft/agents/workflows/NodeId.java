// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

/**
 * Identifies one node in an immutable workflow graph.
 *
 * @param value stable non-blank node identifier
 */
public record NodeId(String value) implements Comparable<NodeId> {
    /** Creates a validated node identifier. */
    public NodeId {
        value = WorkflowValidation.requireNonBlank(value, "node id");
    }

    @Override
    public int compareTo(NodeId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
