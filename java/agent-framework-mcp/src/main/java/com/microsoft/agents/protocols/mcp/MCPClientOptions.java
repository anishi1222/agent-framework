// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.tools.ToolApprovalMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Configures MCP client lifecycle, callbacks, bounds, and remote-tool approval.
 *
 * <p>Sampling and elicitation are not advertised unless an explicit handler is configured. Remote
 * tools require approval by default because an MCP peer is outside the local trust boundary.
 *
 * <p>The {@link Builder#maxSamplingRequests(int)} value is a cumulative cap for the entire lifetime
 * of one {@link MCPClient}. It is not reset per operation, transport session, or reconnect; create a
 * new client to start a new sampling-request budget.
 */
public final class MCPClientOptions {
    private final String clientName;

    private final String clientVersion;

    private final Duration requestTimeout;

    private final Duration initializationTimeout;

    private final Duration closeTimeout;

    private final MCPLimits limits;

    private final List<MCPRoot> roots;

    private final MCPSamplingHandler samplingHandler;

    private final MCPElicitationHandler formElicitationHandler;

    private final MCPElicitationHandler urlElicitationHandler;

    private final ToolApprovalMode remoteToolApprovalMode;

    private final int maxSamplingTokens;

    private final int maxSamplingRequests;

    private MCPClientOptions(Builder builder) {
        clientName = MCPValidation.nonBlank(builder.clientName, "clientName");
        clientVersion = MCPValidation.nonBlank(builder.clientVersion, "clientVersion");
        requestTimeout = MCPValidation.positive(builder.requestTimeout, "requestTimeout");
        initializationTimeout = MCPValidation.positive(builder.initializationTimeout, "initializationTimeout");
        closeTimeout = MCPValidation.positive(builder.closeTimeout, "closeTimeout");
        limits = Objects.requireNonNull(builder.limits, "limits");
        roots = List.copyOf(builder.roots);
        samplingHandler = builder.samplingHandler;
        formElicitationHandler = builder.formElicitationHandler;
        urlElicitationHandler = builder.urlElicitationHandler;
        remoteToolApprovalMode = Objects.requireNonNull(builder.remoteToolApprovalMode, "remoteToolApprovalMode");
        maxSamplingTokens = MCPValidation.positive(builder.maxSamplingTokens, "maxSamplingTokens");
        maxSamplingRequests = MCPValidation.positive(builder.maxSamplingRequests, "maxSamplingRequests");
    }

    /**
     * Creates a builder with secure defaults.
     *
     * @return options builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the advertised client name.
     *
     * @return client name
     */
    public String clientName() {
        return clientName;
    }

    /**
     * Returns the advertised client version.
     *
     * @return client version
     */
    public String clientVersion() {
        return clientVersion;
    }

    /**
     * Returns the global SDK request timeout.
     *
     * @return request timeout
     */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /**
     * Returns the initialization timeout.
     *
     * @return initialization timeout
     */
    public Duration initializationTimeout() {
        return initializationTimeout;
    }

    /**
     * Returns the graceful close timeout.
     *
     * @return close timeout
     */
    public Duration closeTimeout() {
        return closeTimeout;
    }

    /**
     * Returns finite boundary limits.
     *
     * @return limits
     */
    public MCPLimits limits() {
        return limits;
    }

    /**
     * Returns roots advertised to the server.
     *
     * @return immutable roots
     */
    public List<MCPRoot> roots() {
        return roots;
    }

    /**
     * Returns the sampling handler, or {@code null}.
     *
     * @return optional handler
     */
    public MCPSamplingHandler samplingHandler() {
        return samplingHandler;
    }

    /**
     * Returns the form elicitation handler, or {@code null}.
     *
     * @return optional handler
     */
    public MCPElicitationHandler formElicitationHandler() {
        return formElicitationHandler;
    }

    /**
     * Returns the URL elicitation handler, or {@code null}.
     *
     * @return optional handler
     */
    public MCPElicitationHandler urlElicitationHandler() {
        return urlElicitationHandler;
    }

    /**
     * Returns approval policy applied to discovered remote tools.
     *
     * @return approval mode
     */
    public ToolApprovalMode remoteToolApprovalMode() {
        return remoteToolApprovalMode;
    }

    /**
     * Returns the maximum token count accepted from a sampling request.
     *
     * @return positive token limit
     */
    public int maxSamplingTokens() {
        return maxSamplingTokens;
    }

    /**
     * Returns the cumulative sampling-request cap for the entire client lifetime.
     *
     * <p>The count is not reset per operation, transport session, or reconnect.
     *
     * @return positive per-client-lifetime request limit
     */
    public int maxSamplingRequests() {
        return maxSamplingRequests;
    }

    /** Builds immutable client options. */
    public static final class Builder {
        private String clientName = "agent-framework-java-mcp";

        private String clientVersion = "0.1.0-SNAPSHOT";

        private Duration requestTimeout = Duration.ofSeconds(30);

        private Duration initializationTimeout = Duration.ofSeconds(20);

        private Duration closeTimeout = Duration.ofSeconds(5);

        private MCPLimits limits = MCPLimits.defaults();

        private final List<MCPRoot> roots = new ArrayList<>();

        private MCPSamplingHandler samplingHandler;

        private MCPElicitationHandler formElicitationHandler;

        private MCPElicitationHandler urlElicitationHandler;

        private ToolApprovalMode remoteToolApprovalMode = ToolApprovalMode.ALWAYS_REQUIRE;

        private int maxSamplingTokens = 4096;

        private int maxSamplingRequests = 25;

        private Builder() {}

        /**
         * Sets advertised implementation information.
         *
         * @param name client name
         * @param version client version
         * @return this builder
         */
        public Builder clientInfo(String name, String version) {
            clientName = name;
            clientVersion = version;
            return this;
        }

        /**
         * Sets the global request timeout.
         *
         * @param timeout positive timeout
         * @return this builder
         */
        public Builder requestTimeout(Duration timeout) {
            requestTimeout = timeout;
            return this;
        }

        /**
         * Sets the initialization timeout.
         *
         * @param timeout positive timeout
         * @return this builder
         */
        public Builder initializationTimeout(Duration timeout) {
            initializationTimeout = timeout;
            return this;
        }

        /**
         * Sets the graceful close timeout.
         *
         * @param timeout positive timeout
         * @return this builder
         */
        public Builder closeTimeout(Duration timeout) {
            closeTimeout = timeout;
            return this;
        }

        /**
         * Sets finite boundary limits.
         *
         * @param limits limits
         * @return this builder
         */
        public Builder limits(MCPLimits limits) {
            this.limits = limits;
            return this;
        }

        /**
         * Replaces roots advertised to servers.
         *
         * @param roots roots
         * @return this builder
         */
        public Builder roots(List<MCPRoot> roots) {
            this.roots.clear();
            this.roots.addAll(MCPValidation.copyList(roots, "roots"));
            return this;
        }

        /**
         * Advertises sampling only with the supplied security gate and handler.
         *
         * @param handler explicit sampling handler
         * @return this builder
         */
        public Builder samplingHandler(MCPSamplingHandler handler) {
            samplingHandler = handler;
            return this;
        }

        /**
         * Advertises in-band form elicitation.
         *
         * @param handler explicit elicitation handler
         * @return this builder
         */
        public Builder formElicitationHandler(MCPElicitationHandler handler) {
            formElicitationHandler = handler;
            return this;
        }

        /**
         * Advertises out-of-band HTTPS URL elicitation.
         *
         * @param handler explicit elicitation handler
         * @return this builder
         */
        public Builder urlElicitationHandler(MCPElicitationHandler handler) {
            urlElicitationHandler = handler;
            return this;
        }

        /**
         * Sets approval policy for discovered remote tools.
         *
         * @param mode approval mode
         * @return this builder
         */
        public Builder remoteToolApprovalMode(ToolApprovalMode mode) {
            remoteToolApprovalMode = mode;
            return this;
        }

        /**
         * Sets the maximum token count accepted from one server-initiated sampling request.
         *
         * @param maximum positive token limit
         * @return this builder
         */
        public Builder maxSamplingTokens(int maximum) {
            maxSamplingTokens = maximum;
            return this;
        }

        /**
         * Sets the cumulative sampling-request cap for the entire client lifetime.
         *
         * <p>The count is not reset per operation, transport session, or reconnect. A new
         * {@link MCPClient} starts a new budget.
         *
         * @param maximum positive per-client-lifetime request limit
         * @return this builder
         */
        public Builder maxSamplingRequests(int maximum) {
            maxSamplingRequests = maximum;
            return this;
        }

        /**
         * Creates immutable options.
         *
         * @return client options
         */
        public MCPClientOptions build() {
            return new MCPClientOptions(this);
        }
    }
}
