// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.memory.mem0;

import com.microsoft.agents.core.ValidationException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Configures the Mem0 endpoint, deadlines, event polling, limits, retries, and caller-owned
 * executors.
 */
public final class Mem0ClientOptions {
    private static final Mem0ClientOptions DEFAULTS = builder().build();

    private final Mem0Endpoint endpoint;

    private final Duration connectTimeout;

    private final Duration requestTimeout;

    private final Duration operationTimeout;

    private final Duration initialEventPollDelay;

    private final Duration maxEventPollDelay;

    private final Duration closeTimeout;

    private final Mem0RetryOptions retryOptions;

    private final Mem0LimitOptions limitOptions;

    private final ExecutorService executor;

    private final ScheduledExecutorService scheduler;

    private Mem0ClientOptions(Builder builder) {
        endpoint = Objects.requireNonNull(builder.endpoint, "endpoint");
        connectTimeout = positive(builder.connectTimeout, "connectTimeout");
        requestTimeout = positive(builder.requestTimeout, "requestTimeout");
        operationTimeout = positive(builder.operationTimeout, "operationTimeout");
        initialEventPollDelay = positive(builder.initialEventPollDelay, "initialEventPollDelay");
        maxEventPollDelay = positive(builder.maxEventPollDelay, "maxEventPollDelay");
        closeTimeout = positive(builder.closeTimeout, "closeTimeout");
        if (maxEventPollDelay.compareTo(initialEventPollDelay) < 0) {
            throw new ValidationException("maxEventPollDelay must not be less than initialEventPollDelay.");
        }
        retryOptions = Objects.requireNonNull(builder.retryOptions, "retryOptions");
        limitOptions = Objects.requireNonNull(builder.limitOptions, "limitOptions");
        executor = builder.executor;
        scheduler = builder.scheduler;
    }

    /**
     * Returns conservative hosted-Platform defaults.
     *
     * @return shared immutable defaults
     */
    public static Mem0ClientOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Creates a client-options builder.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the Mem0 endpoint. */
    public Mem0Endpoint endpoint() {
        return endpoint;
    }

    /** Returns the connection timeout. */
    public Duration connectTimeout() {
        return connectTimeout;
    }

    /** Returns the per-request timeout. */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /** Returns the total deadline for one public operation. */
    public Duration operationTimeout() {
        return operationTimeout;
    }

    /** Returns the initial asynchronous-event polling delay. */
    public Duration initialEventPollDelay() {
        return initialEventPollDelay;
    }

    /** Returns the maximum asynchronous-event polling delay. */
    public Duration maxEventPollDelay() {
        return maxEventPollDelay;
    }

    /** Returns the graceful HTTP-client close timeout. */
    public Duration closeTimeout() {
        return closeTimeout;
    }

    /** Returns bounded retry settings. */
    public Mem0RetryOptions retryOptions() {
        return retryOptions;
    }

    /** Returns finite protocol and context limits. */
    public Mem0LimitOptions limitOptions() {
        return limitOptions;
    }

    /**
     * Returns the optional caller-owned request executor.
     *
     * @return executor or {@code null} when the provider should create one
     */
    public ExecutorService executor() {
        return executor;
    }

    /**
     * Returns the optional caller-owned scheduler.
     *
     * @return scheduler or {@code null} when the provider should create one
     */
    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    @Override
    public String toString() {
        return "Mem0ClientOptions{endpoint="
                + endpoint
                + ", connectTimeout="
                + connectTimeout
                + ", requestTimeout="
                + requestTimeout
                + ", operationTimeout="
                + operationTimeout
                + ", initialEventPollDelay="
                + initialEventPollDelay
                + ", maxEventPollDelay="
                + maxEventPollDelay
                + ", closeTimeout="
                + closeTimeout
                + ", retryOptions="
                + retryOptions
                + ", limitOptions="
                + limitOptions
                + ", executor="
                + (executor == null ? "<owned>" : "<caller-owned>")
                + ", scheduler="
                + (scheduler == null ? "<owned>" : "<caller-owned>")
                + '}';
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new ValidationException(name + " must be positive.");
        }
        return value;
    }

    /** Builds immutable {@link Mem0ClientOptions}. */
    public static final class Builder {
        private Mem0Endpoint endpoint = Mem0Endpoint.platform();

        private Duration connectTimeout = Duration.ofSeconds(10);

        private Duration requestTimeout = Duration.ofSeconds(30);

        private Duration operationTimeout = Duration.ofMinutes(2);

        private Duration initialEventPollDelay = Duration.ofMillis(250);

        private Duration maxEventPollDelay = Duration.ofSeconds(2);

        private Duration closeTimeout = Duration.ofSeconds(5);

        private Mem0RetryOptions retryOptions = Mem0RetryOptions.defaults();

        private Mem0LimitOptions limitOptions = Mem0LimitOptions.defaults();

        private ExecutorService executor;

        private ScheduledExecutorService scheduler;

        private Builder() {}

        /** Sets the Mem0 endpoint. */
        public Builder endpoint(Mem0Endpoint value) {
            endpoint = value;
            return this;
        }

        /** Sets the connection timeout. */
        public Builder connectTimeout(Duration value) {
            connectTimeout = value;
            return this;
        }

        /** Sets the per-request timeout. */
        public Builder requestTimeout(Duration value) {
            requestTimeout = value;
            return this;
        }

        /** Sets the total public-operation deadline. */
        public Builder operationTimeout(Duration value) {
            operationTimeout = value;
            return this;
        }

        /** Sets the initial asynchronous-event polling delay. */
        public Builder initialEventPollDelay(Duration value) {
            initialEventPollDelay = value;
            return this;
        }

        /** Sets the maximum asynchronous-event polling delay. */
        public Builder maxEventPollDelay(Duration value) {
            maxEventPollDelay = value;
            return this;
        }

        /** Sets the graceful HTTP-client close timeout. */
        public Builder closeTimeout(Duration value) {
            closeTimeout = value;
            return this;
        }

        /** Sets bounded retry settings. */
        public Builder retryOptions(Mem0RetryOptions value) {
            retryOptions = value;
            return this;
        }

        /** Sets finite protocol and context limits. */
        public Builder limitOptions(Mem0LimitOptions value) {
            limitOptions = value;
            return this;
        }

        /**
         * Sets a caller-owned request executor that the provider will not close.
         *
         * @param value executor
         * @return this builder
         */
        public Builder executor(ExecutorService value) {
            executor = value;
            return this;
        }

        /**
         * Sets a caller-owned scheduler that the provider will not close.
         *
         * @param value scheduler
         * @return this builder
         */
        public Builder scheduler(ScheduledExecutorService value) {
            scheduler = value;
            return this;
        }

        /**
         * Creates immutable client settings.
         *
         * @return client options
         */
        public Mem0ClientOptions build() {
            return new Mem0ClientOptions(this);
        }
    }
}
