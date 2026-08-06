// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

/**
 * Merges concurrent branch writes in deterministic node order.
 *
 * @param <T> state value type
 */
@FunctionalInterface
public interface StateReducer<T> {
    /**
     * Merges the accumulated value with the next branch value.
     *
     * @param current accumulated value
     * @param next next value in stable branch order
     * @return merged value
     */
    T reduce(T current, T next);
}
