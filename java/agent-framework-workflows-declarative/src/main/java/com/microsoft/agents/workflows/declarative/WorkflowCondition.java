// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows.declarative;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Associates a runtime payload type with a caller-owned routing predicate.
 *
 * @param <T> accepted payload type
 * @param payloadType runtime payload type
 * @param predicate caller-owned predicate
 */
public record WorkflowCondition<T>(Class<T> payloadType, Predicate<? super T> predicate) {
    /** Creates a validated typed condition. */
    public WorkflowCondition {
        Objects.requireNonNull(payloadType, "payloadType");
        Objects.requireNonNull(predicate, "predicate");
    }
}
