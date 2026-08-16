// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Routes a source output when a typed predicate accepts it.
 *
 * @param <T> source output and target input type
 */
public final class ConditionalEdge<T> implements Edge {
    private final NodeId sourceId;

    private final NodeId targetId;

    private final Class<T> payloadType;

    private final Predicate<? super T> condition;

    /**
     * Creates a typed conditional edge.
     *
     * @param sourceId source node identifier
     * @param targetId target node identifier
     * @param payloadType condition payload type
     * @param condition routing predicate
     */
    public ConditionalEdge(NodeId sourceId, NodeId targetId, Class<T> payloadType, Predicate<? super T> condition) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.targetId = Objects.requireNonNull(targetId, "targetId");
        this.payloadType = Objects.requireNonNull(payloadType, "payloadType");
        this.condition = Objects.requireNonNull(condition, "condition");
    }

    @Override
    public NodeId sourceId() {
        return sourceId;
    }

    @Override
    public NodeId targetId() {
        return targetId;
    }

    /**
     * Returns the predicate payload type.
     *
     * @return payload type
     */
    public Class<T> payloadType() {
        return payloadType;
    }

    /**
     * Tests a source output with an explicit runtime type check.
     *
     * @param payload source output
     * @return whether the edge should route the payload
     */
    public boolean matches(Object payload) {
        return condition.test(payloadType.cast(Objects.requireNonNull(payload, "payload")));
    }
}
