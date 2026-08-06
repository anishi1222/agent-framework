// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import java.util.List;

/**
 * Aggregates successful concurrent participant results in declaration order.
 *
 * @param <O> aggregate output type
 */
@FunctionalInterface
public interface ConcurrentAggregator<O> {
    /**
     * Aggregates immutable successful participant results.
     *
     * @param results results in participant declaration order
     * @return non-null terminal output
     */
    O aggregate(List<ParticipantResult> results);
}
