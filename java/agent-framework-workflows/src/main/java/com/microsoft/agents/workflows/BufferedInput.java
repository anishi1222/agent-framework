// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.StateValue;
import java.util.Objects;

/**
 * Stores one pending or partially buffered input in a checkpoint.
 *
 * @param targetId target executor identifier
 * @param sourceId source executor or reserved pending-input identifier
 * @param value immutable JSON-shaped encoded value
 */
public record BufferedInput(NodeId targetId, String sourceId, StateValue value) implements Comparable<BufferedInput> {
    /** Reserved source identifier for a normal pending invocation. */
    public static final String PENDING_SOURCE = "$pending";

    private static final String PENDING_SOURCE_PREFIX = PENDING_SOURCE + ":";

    /** Creates a validated buffered input. */
    public BufferedInput {
        Objects.requireNonNull(targetId, "targetId");
        sourceId = WorkflowValidation.requireNonBlank(sourceId, "sourceId");
        Objects.requireNonNull(value, "value");
    }

    @Override
    public int compareTo(BufferedInput other) {
        int targetComparison = targetId.compareTo(other.targetId);
        return targetComparison != 0 ? targetComparison : sourceId.compareTo(other.sourceId);
    }

    static String pendingSource(String correlationId) {
        return PENDING_SOURCE_PREFIX + WorkflowValidation.requireNonBlank(correlationId, "correlationId");
    }

    static boolean isPendingSource(String sourceId) {
        return PENDING_SOURCE.equals(sourceId) || sourceId.startsWith(PENDING_SOURCE_PREFIX);
    }

    static String pendingCorrelation(String sourceId) {
        return sourceId.startsWith(PENDING_SOURCE_PREFIX) ? sourceId.substring(PENDING_SOURCE_PREFIX.length()) : null;
    }
}
