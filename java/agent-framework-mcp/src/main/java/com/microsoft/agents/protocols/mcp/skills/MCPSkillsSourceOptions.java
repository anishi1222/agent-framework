// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp.skills;

import com.microsoft.agents.agents.skills.FileSkillsSourceOptions;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/** Configures MCP skill index and archive processing. */
public final class MCPSkillsSourceOptions {
    /** Default archive file-count limit. */
    public static final int DEFAULT_ARCHIVE_MAX_FILE_COUNT = 20;

    /** Default compressed archive byte limit. */
    public static final int DEFAULT_ARCHIVE_MAX_SIZE_BYTES = 1024 * 1024;

    /** Default total uncompressed archive byte limit. */
    public static final int DEFAULT_ARCHIVE_MAX_UNCOMPRESSED_SIZE_BYTES = 1024 * 1024;

    private final Path archiveDirectory;
    private final Set<String> archiveResourceExtensions;
    private final int archiveResourceSearchDepth;
    private final int archiveMaxFileCount;
    private final int archiveMaxSizeBytes;
    private final int archiveMaxUncompressedSizeBytes;

    private MCPSkillsSourceOptions(Builder builder) {
        archiveDirectory = builder.archiveDirectory == null
                ? null
                : builder.archiveDirectory.toAbsolutePath().normalize();
        archiveResourceExtensions = Set.copyOf(builder.archiveResourceExtensions);
        archiveResourceSearchDepth = builder.archiveResourceSearchDepth;
        archiveMaxFileCount = positive(builder.archiveMaxFileCount, "archiveMaxFileCount");
        archiveMaxSizeBytes = positive(builder.archiveMaxSizeBytes, "archiveMaxSizeBytes");
        archiveMaxUncompressedSizeBytes =
                positive(builder.archiveMaxUncompressedSizeBytes, "archiveMaxUncompressedSizeBytes");
        if (archiveResourceSearchDepth < 1) {
            throw new IllegalArgumentException("archiveResourceSearchDepth must be at least 1.");
        }
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
    public static MCPSkillsSourceOptions defaults() {
        return builder().build();
    }

    /** Returns the optional archive extraction directory. */
    public Path archiveDirectory() {
        return archiveDirectory;
    }

    /** Returns extensions surfaced as archive resources. */
    public Set<String> archiveResourceExtensions() {
        return archiveResourceExtensions;
    }

    /** Returns archive resource scan depth. */
    public int archiveResourceSearchDepth() {
        return archiveResourceSearchDepth;
    }

    /** Returns archive file-count limit. */
    public int archiveMaxFileCount() {
        return archiveMaxFileCount;
    }

    /** Returns compressed archive byte limit. */
    public int archiveMaxSizeBytes() {
        return archiveMaxSizeBytes;
    }

    /** Returns total uncompressed archive byte limit. */
    public int archiveMaxUncompressedSizeBytes() {
        return archiveMaxUncompressedSizeBytes;
    }

    /** Builds immutable MCP skill options. */
    public static final class Builder {
        private Path archiveDirectory;
        private Set<String> archiveResourceExtensions = FileSkillsSourceOptions.DEFAULT_RESOURCE_EXTENSIONS;
        private int archiveResourceSearchDepth = FileSkillsSourceOptions.DEFAULT_SEARCH_DEPTH;
        private int archiveMaxFileCount = DEFAULT_ARCHIVE_MAX_FILE_COUNT;
        private int archiveMaxSizeBytes = DEFAULT_ARCHIVE_MAX_SIZE_BYTES;
        private int archiveMaxUncompressedSizeBytes = DEFAULT_ARCHIVE_MAX_UNCOMPRESSED_SIZE_BYTES;

        private Builder() {}

        /** Sets the archive extraction directory. */
        public Builder archiveDirectory(Path archiveDirectory) {
            this.archiveDirectory = archiveDirectory;
            return this;
        }

        /** Sets archive resource extensions. */
        public Builder archiveResourceExtensions(Set<String> archiveResourceExtensions) {
            this.archiveResourceExtensions =
                    Objects.requireNonNull(archiveResourceExtensions, "archiveResourceExtensions");
            return this;
        }

        /** Sets archive resource scan depth. */
        public Builder archiveResourceSearchDepth(int archiveResourceSearchDepth) {
            this.archiveResourceSearchDepth = archiveResourceSearchDepth;
            return this;
        }

        /** Sets archive file-count limit. */
        public Builder archiveMaxFileCount(int archiveMaxFileCount) {
            this.archiveMaxFileCount = archiveMaxFileCount;
            return this;
        }

        /** Sets compressed archive byte limit. */
        public Builder archiveMaxSizeBytes(int archiveMaxSizeBytes) {
            this.archiveMaxSizeBytes = archiveMaxSizeBytes;
            return this;
        }

        /** Sets total uncompressed archive byte limit. */
        public Builder archiveMaxUncompressedSizeBytes(int archiveMaxUncompressedSizeBytes) {
            this.archiveMaxUncompressedSizeBytes = archiveMaxUncompressedSizeBytes;
            return this;
        }

        /**
         * Builds immutable options.
         *
         * @return options
         */
        public MCPSkillsSourceOptions build() {
            return new MCPSkillsSourceOptions(this);
        }
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }
}
