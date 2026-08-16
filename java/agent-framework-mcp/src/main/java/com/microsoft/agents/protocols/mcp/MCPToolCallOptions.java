// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Configures one cancellable MCP tool call.
 */
public final class MCPToolCallOptions {
    private final Duration timeout;

    private final RunCancellation cancellation;

    private final StateValue progressToken;

    private final Map<String, StateValue> metadata;

    private MCPToolCallOptions(Builder builder) {
        timeout = MCPValidation.positive(builder.timeout, "timeout");
        cancellation = Objects.requireNonNull(builder.cancellation, "cancellation");
        progressToken = builder.progressToken;
        metadata = MCPValidation.copyMap(builder.metadata, "metadata");
        if (metadata.containsKey("progressToken")) {
            throw new com.microsoft.agents.core.ValidationException(
                    "metadata must not contain reserved progressToken.");
        }
    }

    /**
     * Creates a builder using the supplied default timeout.
     *
     * @param timeout positive timeout
     * @return call-options builder
     */
    public static Builder builder(Duration timeout) {
        return new Builder(timeout);
    }

    /**
     * Returns the per-call deadline.
     *
     * @return timeout
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Returns caller-owned cancellation.
     *
     * @return cancellation signal
     */
    public RunCancellation cancellation() {
        return cancellation;
    }

    /**
     * Returns the optional JSON-shaped progress token.
     *
     * @return progress token, or {@code null}
     */
    public StateValue progressToken() {
        return progressToken;
    }

    /**
     * Returns immutable MCP request metadata.
     *
     * @return metadata
     */
    public Map<String, StateValue> metadata() {
        return metadata;
    }

    /** Builds one call-options value. */
    public static final class Builder {
        private final Duration timeout;

        private RunCancellation cancellation = new DefaultRunCancellation();

        private StateValue progressToken;

        private Map<String, StateValue> metadata = Map.of();

        private Builder(Duration timeout) {
            this.timeout = timeout;
        }

        /**
         * Sets caller-owned cancellation.
         *
         * @param cancellation cancellation signal
         * @return this builder
         */
        public Builder cancellation(RunCancellation cancellation) {
            this.cancellation = cancellation;
            return this;
        }

        /**
         * Sets an optional progress token.
         *
         * @param token JSON-shaped token
         * @return this builder
         */
        public Builder progressToken(StateValue token) {
            progressToken = token;
            return this;
        }

        /**
         * Sets MCP request metadata.
         *
         * @param metadata metadata
         * @return this builder
         */
        public Builder metadata(Map<String, StateValue> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Creates immutable call options.
         *
         * @return call options
         */
        public MCPToolCallOptions build() {
            return new MCPToolCallOptions(this);
        }
    }
}
