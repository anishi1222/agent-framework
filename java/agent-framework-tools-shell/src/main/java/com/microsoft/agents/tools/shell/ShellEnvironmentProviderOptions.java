// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Configures shell-environment context probing. */
public final class ShellEnvironmentProviderOptions {
    private final List<String> probeTools;
    private final ShellFamily overrideFamily;
    private final Duration probeTimeout;
    private final Function<ShellEnvironmentSnapshot, String> instructionsFormatter;

    private ShellEnvironmentProviderOptions(Builder builder) {
        probeTools = List.copyOf(builder.probeTools);
        if (probeTools.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("probeTools must contain non-blank values.");
        }
        overrideFamily = builder.overrideFamily;
        probeTimeout = Objects.requireNonNull(builder.probeTimeout, "probeTimeout");
        if (probeTimeout.isZero() || probeTimeout.isNegative()) {
            throw new IllegalArgumentException("probeTimeout must be positive.");
        }
        instructionsFormatter = builder.instructionsFormatter;
    }

    /**
     * Returns a new options builder.
     *
     * @return provider options builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns default provider options.
     *
     * @return options probing common developer CLIs
     */
    public static ShellEnvironmentProviderOptions defaults() {
        return builder().build();
    }

    /** Returns CLI names probed with {@code --version}. */
    public List<String> probeTools() {
        return probeTools;
    }

    /** Returns an optional shell-family override. */
    public ShellFamily overrideFamily() {
        return overrideFamily;
    }

    /** Returns the per-probe timeout. */
    public Duration probeTimeout() {
        return probeTimeout;
    }

    /** Returns an optional instructions formatter. */
    public Function<ShellEnvironmentSnapshot, String> instructionsFormatter() {
        return instructionsFormatter;
    }

    /** Builds immutable shell-environment provider options. */
    public static final class Builder {
        private List<String> probeTools = List.of("git", "dotnet", "node", "python", "docker");
        private ShellFamily overrideFamily;
        private Duration probeTimeout = Duration.ofSeconds(5);
        private Function<ShellEnvironmentSnapshot, String> instructionsFormatter;

        private Builder() {}

        /** Sets CLI names to probe. */
        public Builder probeTools(List<String> probeTools) {
            this.probeTools = Objects.requireNonNull(probeTools, "probeTools");
            return this;
        }

        /** Overrides automatic shell-family detection. */
        public Builder overrideFamily(ShellFamily overrideFamily) {
            this.overrideFamily = overrideFamily;
            return this;
        }

        /** Sets the per-probe timeout. */
        public Builder probeTimeout(Duration probeTimeout) {
            this.probeTimeout = probeTimeout;
            return this;
        }

        /** Sets a custom snapshot-to-instructions formatter. */
        public Builder instructionsFormatter(Function<ShellEnvironmentSnapshot, String> instructionsFormatter) {
            this.instructionsFormatter = instructionsFormatter;
            return this;
        }

        /**
         * Builds immutable options.
         *
         * @return provider options
         */
        public ShellEnvironmentProviderOptions build() {
            return new ShellEnvironmentProviderOptions(this);
        }
    }
}
