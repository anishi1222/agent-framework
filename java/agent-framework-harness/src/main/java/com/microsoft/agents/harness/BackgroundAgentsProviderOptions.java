// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

/** Configures background-agent provider identity and guidance. */
public final class BackgroundAgentsProviderOptions {
    private final String sourceId;

    private final String instructions;

    private BackgroundAgentsProviderOptions(Builder builder) {
        sourceId = requireNonBlank(builder.sourceId, "sourceId");
        instructions = requireNonBlank(builder.instructions, "instructions");
    }

    /** Returns default options. */
    public static BackgroundAgentsProviderOptions defaults() {
        return builder().build();
    }

    /** Creates an options builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the provider identifier. */
    public String sourceId() {
        return sourceId;
    }

    /** Returns background-agent guidance. */
    public String instructions() {
        return instructions;
    }

    /** Builds immutable background-agent options. */
    public static final class Builder {
        private String sourceId = BackgroundAgentsProvider.DEFAULT_SOURCE_ID;

        private String instructions = BackgroundAgentsProvider.DEFAULT_INSTRUCTIONS;

        private Builder() {}

        /** Sets the provider identifier. */
        public Builder sourceId(String sourceId) {
            this.sourceId = sourceId;
            return this;
        }

        /** Sets background-agent guidance. */
        public Builder instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        /** Creates immutable options. */
        public BackgroundAgentsProviderOptions build() {
            return new BackgroundAgentsProviderOptions(this);
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
