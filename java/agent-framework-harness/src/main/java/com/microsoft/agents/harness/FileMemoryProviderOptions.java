// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import com.microsoft.agents.agents.AgentSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Function;

/** Configures session-scoped file-memory paths and guidance. */
public final class FileMemoryProviderOptions {
    private final String sourceId;

    private final String instructions;

    private final Function<AgentSession, String> scope;

    private FileMemoryProviderOptions(Builder builder) {
        sourceId = requireNonBlank(builder.sourceId, "sourceId");
        instructions = requireNonBlank(builder.instructions, "instructions");
        scope = Objects.requireNonNull(builder.scope, "scope");
    }

    /** Returns collision-resistant session-id scoped defaults. */
    public static FileMemoryProviderOptions defaults() {
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

    /** Returns file-memory guidance. */
    public String instructions() {
        return instructions;
    }

    /** Returns the session-to-folder scope resolver. */
    public Function<AgentSession, String> scope() {
        return scope;
    }

    /** Builds immutable file-memory options. */
    public static final class Builder {
        private String sourceId = FileMemoryProvider.DEFAULT_SOURCE_ID;

        private String instructions = FileMemoryProvider.DEFAULT_INSTRUCTIONS;

        private Function<AgentSession, String> scope = FileMemoryProviderOptions::defaultScope;

        private Builder() {}

        /** Sets the provider identifier. */
        public Builder sourceId(String sourceId) {
            this.sourceId = sourceId;
            return this;
        }

        /** Sets file-memory guidance. */
        public Builder instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        /** Sets the session-to-folder resolver. */
        public Builder scope(Function<AgentSession, String> scope) {
            this.scope = scope;
            return this;
        }

        /** Creates immutable options. */
        public FileMemoryProviderOptions build() {
            return new FileMemoryProviderOptions(this);
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private static String defaultScope(AgentSession session) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(session, "session")
                            .sessionId()
                            .getBytes(StandardCharsets.UTF_8));
            return "session-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable.", failure);
        }
    }
}
