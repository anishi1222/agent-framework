// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import com.microsoft.agents.core.ValidationException;
import java.time.Duration;

/**
 * Configures bounded SDK throttling retries and an adapter operation deadline.
 *
 * @param maxThrottlingRetryAttempts maximum SDK retries after 429
 * @param maxThrottlingRetryWait cumulative SDK retry wait bound
 * @param operationTimeout end-to-end adapter operation deadline
 */
public record CosmosRetryOptions(
        int maxThrottlingRetryAttempts, Duration maxThrottlingRetryWait, Duration operationTimeout) {
    /** Creates validated bounded retry options. */
    public CosmosRetryOptions {
        if (maxThrottlingRetryAttempts < 0 || maxThrottlingRetryAttempts > 100) {
            throw new ValidationException("maxThrottlingRetryAttempts must be between 0 and 100.");
        }
        maxThrottlingRetryWait = CosmosValidation.requireNonNull(maxThrottlingRetryWait, "maxThrottlingRetryWait");
        operationTimeout = CosmosValidation.requireNonNull(operationTimeout, "operationTimeout");
        if (maxThrottlingRetryWait.isNegative()
                || maxThrottlingRetryWait.isZero()
                || maxThrottlingRetryWait.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new ValidationException("maxThrottlingRetryWait must be positive and at most 10 minutes.");
        }
        if (operationTimeout.isNegative()
                || operationTimeout.isZero()
                || operationTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new ValidationException("operationTimeout must be positive and at most 10 minutes.");
        }
    }

    /**
     * Returns conservative retry defaults.
     *
     * @return default retry options
     */
    public static CosmosRetryOptions defaults() {
        return new CosmosRetryOptions(9, Duration.ofSeconds(30), Duration.ofSeconds(30));
    }
}
