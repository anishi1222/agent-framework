// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Bounds in-process parent-context retention for active telemetry runs.
 *
 * <p>Entries are pruned synchronously on register and lookup; no maintenance thread is created.
 * Expiration is a safety policy for abandoned runs, not an application timeout.
 */
public final class TelemetryContextRegistryOptions {
    private static final int DEFAULT_MAXIMUM_ENTRIES = 4_096;

    private static final Duration DEFAULT_ABANDONED_RUN_TTL = Duration.ofMinutes(10);

    private final int maximumEntries;

    private final Duration abandonedRunTtl;

    private final Clock clock;

    private TelemetryContextRegistryOptions(Builder builder) {
        maximumEntries = builder.maximumEntries;
        abandonedRunTtl = builder.abandonedRunTtl;
        clock = builder.clock;
    }

    /**
     * Returns bounded production defaults.
     *
     * @return default options
     */
    public static TelemetryContextRegistryOptions defaults() {
        return builder().build();
    }

    /**
     * Creates an options builder.
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the maximum retained agent and workflow contexts combined.
     *
     * @return positive entry bound
     */
    public int maximumEntries() {
        return maximumEntries;
    }

    /**
     * Returns the maximum retention time for a run that never reaches a terminal signal.
     *
     * @return positive retention duration
     */
    public Duration abandonedRunTtl() {
        return abandonedRunTtl;
    }

    /**
     * Returns the clock used for deterministic expiration.
     *
     * @return clock
     */
    public Clock clock() {
        return clock;
    }

    /** Builds immutable registry options. */
    public static final class Builder {
        private int maximumEntries = DEFAULT_MAXIMUM_ENTRIES;

        private Duration abandonedRunTtl = DEFAULT_ABANDONED_RUN_TTL;

        private Clock clock = Clock.systemUTC();

        private Builder() {}

        /**
         * Sets the maximum retained context count.
         *
         * @param maximumEntries positive combined bound
         * @return this builder
         */
        public Builder maximumEntries(int maximumEntries) {
            if (maximumEntries <= 0) {
                throw new IllegalArgumentException("maximumEntries must be greater than zero.");
            }
            this.maximumEntries = maximumEntries;
            return this;
        }

        /**
         * Sets the abandoned-run retention duration.
         *
         * @param abandonedRunTtl positive duration with millisecond precision
         * @return this builder
         */
        public Builder abandonedRunTtl(Duration abandonedRunTtl) {
            Duration checked = Objects.requireNonNull(abandonedRunTtl, "abandonedRunTtl");
            if (checked.isNegative() || checked.isZero() || checked.toMillis() <= 0) {
                throw new IllegalArgumentException("abandonedRunTtl must be positive and at least one millisecond.");
            }
            this.abandonedRunTtl = checked;
            return this;
        }

        /**
         * Sets the clock used by synchronous expiration checks.
         *
         * @param clock clock
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Creates immutable options.
         *
         * @return options
         */
        public TelemetryContextRegistryOptions build() {
            return new TelemetryContextRegistryOptions(this);
        }
    }
}
