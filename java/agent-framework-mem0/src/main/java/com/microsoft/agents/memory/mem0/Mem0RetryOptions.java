// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.memory.mem0;

import com.microsoft.agents.core.ValidationException;
import java.time.Duration;
import java.util.Objects;

/**
 * Defines bounded retries for idempotent Mem0 search, event, and scoped-clear requests.
 */
public final class Mem0RetryOptions {
    private static final Mem0RetryOptions DEFAULTS = builder().build();

    private final int maxRetries;

    private final Duration initialDelay;

    private final Duration maxDelay;

    private final Duration maxRetryAfter;

    private Mem0RetryOptions(Builder builder) {
        if (builder.maxRetries < 0 || builder.maxRetries > 10) {
            throw new ValidationException("maxRetries must be between 0 and 10.");
        }
        maxRetries = builder.maxRetries;
        initialDelay = positive(builder.initialDelay, "initialDelay");
        maxDelay = positive(builder.maxDelay, "maxDelay");
        maxRetryAfter = positive(builder.maxRetryAfter, "maxRetryAfter");
        if (maxDelay.compareTo(initialDelay) < 0) {
            throw new ValidationException("maxDelay must not be less than initialDelay.");
        }
    }

    /**
     * Returns conservative default retry settings.
     *
     * @return shared immutable defaults
     */
    public static Mem0RetryOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Creates a retry-options builder.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the number of retries after the initial request.
     *
     * @return retry count
     */
    public int maxRetries() {
        return maxRetries;
    }

    /**
     * Returns the initial exponential-backoff delay.
     *
     * @return initial delay
     */
    public Duration initialDelay() {
        return initialDelay;
    }

    /**
     * Returns the maximum exponential-backoff delay.
     *
     * @return maximum delay
     */
    public Duration maxDelay() {
        return maxDelay;
    }

    /**
     * Returns the maximum accepted {@code Retry-After} delay.
     *
     * @return retry-after cap
     */
    public Duration maxRetryAfter() {
        return maxRetryAfter;
    }

    @Override
    public String toString() {
        return "Mem0RetryOptions{maxRetries="
                + maxRetries
                + ", initialDelay="
                + initialDelay
                + ", maxDelay="
                + maxDelay
                + ", maxRetryAfter="
                + maxRetryAfter
                + '}';
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new ValidationException(name + " must be positive.");
        }
        return value;
    }

    /** Builds immutable {@link Mem0RetryOptions}. */
    public static final class Builder {
        private int maxRetries = 2;

        private Duration initialDelay = Duration.ofMillis(200);

        private Duration maxDelay = Duration.ofSeconds(2);

        private Duration maxRetryAfter = Duration.ofSeconds(30);

        private Builder() {}

        /**
         * Sets the retry count after the initial request.
         *
         * @param value value from 0 through 10
         * @return this builder
         */
        public Builder maxRetries(int value) {
            maxRetries = value;
            return this;
        }

        /**
         * Sets the initial backoff.
         *
         * @param value positive delay
         * @return this builder
         */
        public Builder initialDelay(Duration value) {
            initialDelay = value;
            return this;
        }

        /**
         * Sets the maximum backoff.
         *
         * @param value positive delay
         * @return this builder
         */
        public Builder maxDelay(Duration value) {
            maxDelay = value;
            return this;
        }

        /**
         * Sets the maximum accepted retry-after delay.
         *
         * @param value positive delay
         * @return this builder
         */
        public Builder maxRetryAfter(Duration value) {
            maxRetryAfter = value;
            return this;
        }

        /**
         * Creates immutable retry settings.
         *
         * @return retry options
         */
        public Mem0RetryOptions build() {
            return new Mem0RetryOptions(this);
        }
    }
}
