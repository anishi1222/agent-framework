// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Configures available harness modes and instruction rendering. */
public final class AgentModeProviderOptions {
    private final String sourceId;

    private final List<AgentMode> modes;

    private final String defaultMode;

    private final String instructionTemplate;

    private AgentModeProviderOptions(Builder builder) {
        sourceId = requireNonBlank(builder.sourceId, "sourceId");
        modes = normalizeModes(builder.modes);
        defaultMode =
                normalizeName(builder.defaultMode == null ? modes.getFirst().name() : builder.defaultMode);
        if (modes.stream().noneMatch(mode -> normalizeName(mode.name()).equals(defaultMode))) {
            throw new IllegalArgumentException("defaultMode must name an available mode.");
        }
        instructionTemplate = requireNonBlank(builder.instructionTemplate, "instructionTemplate");
    }

    /** Returns default plan/execute options. */
    public static AgentModeProviderOptions defaults() {
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

    /** Returns available modes in declared order. */
    public List<AgentMode> modes() {
        return modes;
    }

    /** Returns the normalized default mode. */
    public String defaultMode() {
        return defaultMode;
    }

    /** Returns the instruction template. */
    public String instructionTemplate() {
        return instructionTemplate;
    }

    /** Builds immutable mode options. */
    public static final class Builder {
        private String sourceId = AgentModeProvider.DEFAULT_SOURCE_ID;

        private List<AgentMode> modes = AgentModeProvider.DEFAULT_MODES;

        private String defaultMode;

        private String instructionTemplate = AgentModeProvider.DEFAULT_INSTRUCTION_TEMPLATE;

        private Builder() {}

        /** Sets the provider identifier. */
        public Builder sourceId(String sourceId) {
            this.sourceId = sourceId;
            return this;
        }

        /** Sets available modes. */
        public Builder modes(List<AgentMode> modes) {
            this.modes = modes;
            return this;
        }

        /** Sets the default mode. */
        public Builder defaultMode(String defaultMode) {
            this.defaultMode = defaultMode;
            return this;
        }

        /** Sets the instruction template. */
        public Builder instructionTemplate(String instructionTemplate) {
            this.instructionTemplate = instructionTemplate;
            return this;
        }

        /** Creates immutable options. */
        public AgentModeProviderOptions build() {
            return new AgentModeProviderOptions(this);
        }
    }

    static String normalizeName(String name) {
        return requireNonBlank(name, "mode").strip().toLowerCase(Locale.ROOT);
    }

    private static List<AgentMode> normalizeModes(List<AgentMode> source) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("modes must not be empty.");
        }
        LinkedHashMap<String, AgentMode> normalized = new LinkedHashMap<>();
        for (AgentMode mode : source) {
            if (mode == null) {
                throw new NullPointerException("modes contains null");
            }
            String key = normalizeName(mode.name());
            if (normalized.putIfAbsent(key, new AgentMode(key, mode.instructions())) != null) {
                throw new IllegalArgumentException("Duplicate mode name '" + mode.name() + "'.");
            }
        }
        return List.copyOf(new ArrayList<>(normalized.values()));
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
