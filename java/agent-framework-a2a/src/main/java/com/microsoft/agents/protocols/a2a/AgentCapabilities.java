// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import java.util.List;

/**
 * Advertises optional A2A capabilities.
 *
 * @param streaming whether streaming operations are supported
 * @param pushNotifications whether push configuration storage is supported
 * @param extendedAgentCard whether an authenticated extended card is available
 * @param extensions ordered extension declarations
 */
public record AgentCapabilities(
        boolean streaming, boolean pushNotifications, boolean extendedAgentCard, List<AgentExtension> extensions) {
    /** Creates immutable capabilities. */
    public AgentCapabilities {
        extensions = A2AValidation.list(extensions, "extensions");
    }

    /**
     * Returns capabilities with no optional features.
     *
     * @return empty capabilities
     */
    public static AgentCapabilities none() {
        return new AgentCapabilities(false, false, false, List.of());
    }

    /**
     * Creates a capabilities builder.
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds immutable {@link AgentCapabilities}. */
    public static final class Builder {
        private boolean streaming;
        private boolean pushNotifications;
        private boolean extendedAgentCard;
        private List<AgentExtension> extensions = List.of();

        private Builder() {}

        /** Sets streaming support. */
        public Builder streaming(boolean value) {
            streaming = value;
            return this;
        }

        /** Sets push-notification configuration support. */
        public Builder pushNotifications(boolean value) {
            pushNotifications = value;
            return this;
        }

        /** Sets extended-card support. */
        public Builder extendedAgentCard(boolean value) {
            extendedAgentCard = value;
            return this;
        }

        /** Sets ordered extensions. */
        public Builder extensions(List<AgentExtension> values) {
            extensions = values;
            return this;
        }

        /** Creates immutable capabilities. */
        public AgentCapabilities build() {
            return new AgentCapabilities(streaming, pushNotifications, extendedAgentCard, extensions);
        }
    }
}
