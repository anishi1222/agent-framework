// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Configures explicit sensitive-content capture, redaction, and size limits.
 *
 * <p>Content capture is disabled by default. Credential-like keys are always redacted regardless of
 * this policy.
 */
public final class TelemetryContentPolicy {
    private static final int DEFAULT_MAX_VALUE_CHARACTERS = 1_024;

    private static final int DEFAULT_MAX_STREAMING_CAPTURE_CHARACTERS = 16_384;

    private final boolean captureContent;

    private final Set<String> redactedKeys;

    private final int maxValueCharacters;

    private final int maxStreamingCaptureCharacters;

    private TelemetryContentPolicy(Builder builder) {
        captureContent = builder.captureContent;
        redactedKeys = Set.copyOf(builder.redactedKeys);
        maxValueCharacters = builder.maxValueCharacters;
        maxStreamingCaptureCharacters = builder.maxStreamingCaptureCharacters;
    }

    /**
     * Returns the privacy-preserving default.
     *
     * @return disabled content policy
     */
    public static TelemetryContentPolicy disabled() {
        return builder().build();
    }

    /**
     * Creates a policy builder.
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Reports whether prompt, output, argument, and result capture is enabled.
     *
     * @return {@code true} only after explicit opt-in
     */
    public boolean captureContent() {
        return captureContent;
    }

    /**
     * Returns configured case-insensitive redaction keys.
     *
     * @return lowercase immutable keys
     */
    public Set<String> redactedKeys() {
        return redactedKeys;
    }

    /**
     * Returns the maximum sanitized characters per captured scalar value.
     *
     * @return positive character limit
     */
    public int maxValueCharacters() {
        return maxValueCharacters;
    }

    /**
     * Returns the total character bound for incrementally captured streaming output.
     *
     * @return positive bound of at least two characters
     */
    public int maxStreamingCaptureCharacters() {
        return maxStreamingCaptureCharacters;
    }

    /** Builds immutable content policies. */
    public static final class Builder {
        private boolean captureContent;

        private final LinkedHashSet<String> redactedKeys = new LinkedHashSet<>();

        private int maxValueCharacters = DEFAULT_MAX_VALUE_CHARACTERS;

        private int maxStreamingCaptureCharacters = DEFAULT_MAX_STREAMING_CAPTURE_CHARACTERS;

        private Builder() {}

        /**
         * Explicitly enables or disables content capture.
         *
         * @param captureContent whether sensitive content may be recorded
         * @return this builder
         */
        public Builder captureContent(boolean captureContent) {
            this.captureContent = captureContent;
            return this;
        }

        /**
         * Adds case-insensitive keys whose values are replaced with {@code [REDACTED]}.
         *
         * @param keys redaction keys
         * @return this builder
         */
        public Builder redactedKeys(Set<String> keys) {
            if (keys == null) {
                throw new NullPointerException("keys");
            }
            for (String key : keys) {
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("redacted key must not be blank.");
                }
                redactedKeys.add(key.toLowerCase(Locale.ROOT));
            }
            return this;
        }

        /**
         * Sets the maximum sanitized characters per scalar value.
         *
         * @param maxValueCharacters positive limit
         * @return this builder
         */
        public Builder maxValueCharacters(int maxValueCharacters) {
            if (maxValueCharacters <= 0) {
                throw new IllegalArgumentException("maxValueCharacters must be greater than zero.");
            }
            this.maxValueCharacters = maxValueCharacters;
            return this;
        }

        /**
         * Sets the total character bound for captured streaming output.
         *
         * @param maxStreamingCaptureCharacters bound including JSON array delimiters
         * @return this builder
         */
        public Builder maxStreamingCaptureCharacters(int maxStreamingCaptureCharacters) {
            if (maxStreamingCaptureCharacters < 2) {
                throw new IllegalArgumentException("maxStreamingCaptureCharacters must be at least two.");
            }
            this.maxStreamingCaptureCharacters = maxStreamingCaptureCharacters;
            return this;
        }

        /**
         * Creates the immutable policy.
         *
         * @return content policy
         */
        public TelemetryContentPolicy build() {
            return new TelemetryContentPolicy(this);
        }
    }
}
