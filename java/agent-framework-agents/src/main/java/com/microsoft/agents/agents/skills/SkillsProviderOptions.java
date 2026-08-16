// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.tools.ToolApprovalMode;
import java.util.Objects;

/** Configures skill advertisement and tool approval. */
public final class SkillsProviderOptions {
    private final String sourceId;
    private final String instructionTemplate;
    private final ToolApprovalMode loadApprovalMode;
    private final ToolApprovalMode resourceApprovalMode;
    private final ToolApprovalMode scriptApprovalMode;

    private SkillsProviderOptions(Builder builder) {
        sourceId = requireNonBlank(builder.sourceId, "sourceId");
        instructionTemplate = Objects.requireNonNull(builder.instructionTemplate, "instructionTemplate");
        if (!instructionTemplate.contains("{skills}")) {
            throw new IllegalArgumentException("instructionTemplate must contain a '{skills}' placeholder.");
        }
        loadApprovalMode = Objects.requireNonNull(builder.loadApprovalMode, "loadApprovalMode");
        resourceApprovalMode = Objects.requireNonNull(builder.resourceApprovalMode, "resourceApprovalMode");
        scriptApprovalMode = Objects.requireNonNull(builder.scriptApprovalMode, "scriptApprovalMode");
    }

    /**
     * Returns a new options builder.
     *
     * @return options builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns secure default options.
     *
     * @return default options
     */
    public static SkillsProviderOptions defaults() {
        return builder().build();
    }

    /** Returns the context-provider identifier. */
    public String sourceId() {
        return sourceId;
    }

    /** Returns the skill-advertisement template. */
    public String instructionTemplate() {
        return instructionTemplate;
    }

    /** Returns the load-skill approval mode. */
    public ToolApprovalMode loadApprovalMode() {
        return loadApprovalMode;
    }

    /** Returns the resource-read approval mode. */
    public ToolApprovalMode resourceApprovalMode() {
        return resourceApprovalMode;
    }

    /** Returns the script-run approval mode. */
    public ToolApprovalMode scriptApprovalMode() {
        return scriptApprovalMode;
    }

    /** Builds immutable provider options. */
    public static final class Builder {
        private String sourceId = SkillsProvider.DEFAULT_SOURCE_ID;
        private String instructionTemplate = SkillsProvider.DEFAULT_INSTRUCTION_TEMPLATE;
        private ToolApprovalMode loadApprovalMode = ToolApprovalMode.ALWAYS_REQUIRE;
        private ToolApprovalMode resourceApprovalMode = ToolApprovalMode.ALWAYS_REQUIRE;
        private ToolApprovalMode scriptApprovalMode = ToolApprovalMode.ALWAYS_REQUIRE;

        private Builder() {}

        /** Sets the provider identifier. */
        public Builder sourceId(String sourceId) {
            this.sourceId = sourceId;
            return this;
        }

        /** Sets the skill-advertisement template. */
        public Builder instructionTemplate(String instructionTemplate) {
            this.instructionTemplate = instructionTemplate;
            return this;
        }

        /** Sets load-skill approval. */
        public Builder loadApprovalMode(ToolApprovalMode loadApprovalMode) {
            this.loadApprovalMode = loadApprovalMode;
            return this;
        }

        /** Sets resource-read approval. */
        public Builder resourceApprovalMode(ToolApprovalMode resourceApprovalMode) {
            this.resourceApprovalMode = resourceApprovalMode;
            return this;
        }

        /** Sets script-run approval. */
        public Builder scriptApprovalMode(ToolApprovalMode scriptApprovalMode) {
            this.scriptApprovalMode = scriptApprovalMode;
            return this;
        }

        /**
         * Builds immutable options.
         *
         * @return provider options
         */
        public SkillsProviderOptions build() {
            return new SkillsProviderOptions(this);
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
