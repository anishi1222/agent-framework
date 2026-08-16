// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;

/** Configures file-based skill discovery. */
public final class FileSkillsSourceOptions {
    /** Default resource extensions. */
    public static final Set<String> DEFAULT_RESOURCE_EXTENSIONS =
            Set.of(".md", ".json", ".yaml", ".yml", ".csv", ".xml", ".txt");

    /** Default executable script extensions. */
    public static final Set<String> DEFAULT_SCRIPT_EXTENSIONS = Set.of(".py");

    /** Default per-skill resource and script scan depth. */
    public static final int DEFAULT_SEARCH_DEPTH = 2;

    private final Set<String> resourceExtensions;
    private final Set<String> scriptExtensions;
    private final int searchDepth;
    private final FileSkillScriptRunner scriptRunner;
    private final BiPredicate<String, String> resourceFilter;
    private final BiPredicate<String, String> scriptFilter;

    private FileSkillsSourceOptions(Builder builder) {
        resourceExtensions = normalizeExtensions(builder.resourceExtensions, "resourceExtensions");
        scriptExtensions = normalizeExtensions(builder.scriptExtensions, "scriptExtensions");
        searchDepth = builder.searchDepth;
        if (searchDepth < 1) {
            throw new IllegalArgumentException("searchDepth must be at least 1.");
        }
        scriptRunner = builder.scriptRunner;
        resourceFilter = builder.resourceFilter;
        scriptFilter = builder.scriptFilter;
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
     * Returns default options.
     *
     * @return file-source defaults
     */
    public static FileSkillsSourceOptions defaults() {
        return builder().build();
    }

    /** Returns normalized resource extensions. */
    public Set<String> resourceExtensions() {
        return resourceExtensions;
    }

    /** Returns normalized script extensions. */
    public Set<String> scriptExtensions() {
        return scriptExtensions;
    }

    /** Returns the per-skill scan depth. */
    public int searchDepth() {
        return searchDepth;
    }

    /** Returns the optional file-script runner. */
    public FileSkillScriptRunner scriptRunner() {
        return scriptRunner;
    }

    /** Returns the optional resource filter. */
    public BiPredicate<String, String> resourceFilter() {
        return resourceFilter;
    }

    /** Returns the optional script filter. */
    public BiPredicate<String, String> scriptFilter() {
        return scriptFilter;
    }

    /** Builds immutable file-source options. */
    public static final class Builder {
        private Set<String> resourceExtensions = DEFAULT_RESOURCE_EXTENSIONS;
        private Set<String> scriptExtensions = DEFAULT_SCRIPT_EXTENSIONS;
        private int searchDepth = DEFAULT_SEARCH_DEPTH;
        private FileSkillScriptRunner scriptRunner;
        private BiPredicate<String, String> resourceFilter;
        private BiPredicate<String, String> scriptFilter;

        private Builder() {}

        /** Sets resource extensions. */
        public Builder resourceExtensions(Set<String> resourceExtensions) {
            this.resourceExtensions = Objects.requireNonNull(resourceExtensions, "resourceExtensions");
            return this;
        }

        /** Sets script extensions. */
        public Builder scriptExtensions(Set<String> scriptExtensions) {
            this.scriptExtensions = Objects.requireNonNull(scriptExtensions, "scriptExtensions");
            return this;
        }

        /** Sets the per-skill scan depth. */
        public Builder searchDepth(int searchDepth) {
            this.searchDepth = searchDepth;
            return this;
        }

        /** Sets the file-script runner. */
        public Builder scriptRunner(FileSkillScriptRunner scriptRunner) {
            this.scriptRunner = scriptRunner;
            return this;
        }

        /** Sets the resource inclusion filter. */
        public Builder resourceFilter(BiPredicate<String, String> resourceFilter) {
            this.resourceFilter = resourceFilter;
            return this;
        }

        /** Sets the script inclusion filter. */
        public Builder scriptFilter(BiPredicate<String, String> scriptFilter) {
            this.scriptFilter = scriptFilter;
            return this;
        }

        /**
         * Builds immutable options.
         *
         * @return options
         */
        public FileSkillsSourceOptions build() {
            return new FileSkillsSourceOptions(this);
        }
    }

    private static Set<String> normalizeExtensions(Set<String> extensions, String name) {
        Objects.requireNonNull(extensions, name);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String extension : extensions) {
            Objects.requireNonNull(extension, name + " entry");
            String normalized = extension.toLowerCase(java.util.Locale.ROOT);
            if (normalized.isBlank() || !normalized.startsWith(".") || normalized.indexOf('/', 1) >= 0) {
                throw new IllegalArgumentException(name + " entries must be extensions beginning with '.'.");
            }
            result.add(normalized);
        }
        return java.util.Collections.unmodifiableSet(result);
    }
}
