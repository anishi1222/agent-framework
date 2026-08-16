// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

/** Configures shared file-access tools and their approval policy. */
public final class FileAccessProviderOptions {
    private final String sourceId;

    private final String instructions;

    private final boolean disableWriteTools;

    private final boolean disableReadOnlyToolApproval;

    private final boolean disableWriteToolApproval;

    private FileAccessProviderOptions(Builder builder) {
        sourceId = requireNonBlank(builder.sourceId, "sourceId");
        instructions = requireNonBlank(builder.instructions, "instructions");
        disableWriteTools = builder.disableWriteTools;
        disableReadOnlyToolApproval = builder.disableReadOnlyToolApproval;
        disableWriteToolApproval = builder.disableWriteToolApproval;
    }

    /** Returns secure default options. */
    public static FileAccessProviderOptions defaults() {
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

    /** Returns shared-file guidance. */
    public String instructions() {
        return instructions;
    }

    /** Returns whether mutating tools are hidden. */
    public boolean disableWriteTools() {
        return disableWriteTools;
    }

    /** Returns whether read-only tools bypass approval. */
    public boolean disableReadOnlyToolApproval() {
        return disableReadOnlyToolApproval;
    }

    /** Returns whether mutating tools bypass approval. */
    public boolean disableWriteToolApproval() {
        return disableWriteToolApproval;
    }

    /** Builds immutable shared-file options. */
    public static final class Builder {
        private String sourceId = FileAccessProvider.DEFAULT_SOURCE_ID;

        private String instructions = FileAccessProvider.DEFAULT_INSTRUCTIONS;

        private boolean disableWriteTools;

        private boolean disableReadOnlyToolApproval;

        private boolean disableWriteToolApproval;

        private Builder() {}

        /** Sets the provider identifier. */
        public Builder sourceId(String sourceId) {
            this.sourceId = sourceId;
            return this;
        }

        /** Sets shared-file guidance. */
        public Builder instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        /** Hides every mutating tool. */
        public Builder disableWriteTools(boolean disableWriteTools) {
            this.disableWriteTools = disableWriteTools;
            return this;
        }

        /** Allows read/list/search without approval. */
        public Builder disableReadOnlyToolApproval(boolean disableReadOnlyToolApproval) {
            this.disableReadOnlyToolApproval = disableReadOnlyToolApproval;
            return this;
        }

        /** Allows write/delete/replace without approval. */
        public Builder disableWriteToolApproval(boolean disableWriteToolApproval) {
            this.disableWriteToolApproval = disableWriteToolApproval;
            return this;
        }

        /** Creates immutable options. */
        public FileAccessProviderOptions build() {
            return new FileAccessProviderOptions(this);
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
