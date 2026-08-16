// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

/** Configures protocol-specific AG-UI hosting behavior without transport-framework types. */
public final class AGUIHostingOptions {
    private final AGUIConcurrentRunPolicy concurrentRunPolicy;

    private final boolean includeRunInput;

    private final boolean capabilitiesEnabled;

    private final int maxStoreRetries;

    private AGUIHostingOptions(Builder builder) {
        concurrentRunPolicy = java.util.Objects.requireNonNull(builder.concurrentRunPolicy, "concurrentRunPolicy");
        includeRunInput = builder.includeRunInput;
        capabilitiesEnabled = builder.capabilitiesEnabled;
        if (builder.maxStoreRetries <= 0) {
            throw new IllegalArgumentException("maxStoreRetries must be greater than zero.");
        }
        maxStoreRetries = builder.maxStoreRetries;
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
     * Returns secure defaults.
     *
     * @return defaults
     */
    public static AGUIHostingOptions defaults() {
        return builder().build();
    }

    /**
     * Returns the same-thread concurrent-run policy.
     *
     * @return policy
     */
    public AGUIConcurrentRunPolicy concurrentRunPolicy() {
        return concurrentRunPolicy;
    }

    /**
     * Reports whether {@code RUN_STARTED.input} echoes the validated request.
     *
     * @return echo setting
     */
    public boolean includeRunInput() {
        return includeRunInput;
    }

    /**
     * Reports whether the namespaced capability document is served.
     *
     * @return capability setting
     */
    public boolean capabilitiesEnabled() {
        return capabilitiesEnabled;
    }

    /**
     * Returns the bounded optimistic-store retry count.
     *
     * @return retry count
     */
    public int maxStoreRetries() {
        return maxStoreRetries;
    }

    /** Builds immutable {@link AGUIHostingOptions}. */
    public static final class Builder {
        private AGUIConcurrentRunPolicy concurrentRunPolicy = AGUIConcurrentRunPolicy.REJECT;

        private boolean includeRunInput;

        private boolean capabilitiesEnabled = true;

        private int maxStoreRetries = 3;

        private Builder() {}

        /**
         * Sets the same-thread concurrent-run policy.
         *
         * @param value policy
         * @return this builder
         */
        public Builder concurrentRunPolicy(AGUIConcurrentRunPolicy value) {
            concurrentRunPolicy = java.util.Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Enables validated request echoing on {@code RUN_STARTED}.
         *
         * @return this builder
         */
        public Builder includeRunInput() {
            includeRunInput = true;
            return this;
        }

        /**
         * Disables the namespaced capability document.
         *
         * @return this builder
         */
        public Builder disableCapabilities() {
            capabilitiesEnabled = false;
            return this;
        }

        /**
         * Sets the positive optimistic-store retry bound.
         *
         * @param value retry count
         * @return this builder
         */
        public Builder maxStoreRetries(int value) {
            maxStoreRetries = value;
            return this;
        }

        /**
         * Creates immutable options.
         *
         * @return options
         */
        public AGUIHostingOptions build() {
            return new AGUIHostingOptions(this);
        }
    }
}
