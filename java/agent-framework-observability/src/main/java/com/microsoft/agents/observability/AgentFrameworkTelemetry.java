// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;

/**
 * Owns immutable Agent Framework observability configuration.
 *
 * <p>The configuration uses only the supplied {@link OpenTelemetry} instance and never reads or
 * mutates the global SDK.
 */
public final class AgentFrameworkTelemetry {
    /** Default instrumentation scope name. */
    public static final String DEFAULT_INSTRUMENTATION_NAME = "com.microsoft.agents";

    private final OpenTelemetry openTelemetry;

    private final String instrumentationName;

    private final String instrumentationVersion;

    private final String providerName;

    private final IdentifierPolicy identifierPolicy;

    private final TelemetryContentPolicy contentPolicy;

    private final InstrumentationFailureHandler instrumentationFailureHandler;

    private final TelemetryContextRegistryOptions contextRegistryOptions;

    private final Tracer tracer;

    private final Meter meter;

    private final TelemetryMetrics metrics;

    private final TelemetryContextRegistry contextRegistry;

    private final ThreadLocal<Boolean> handlingInstrumentationFailure = new ThreadLocal<>();

    private AgentFrameworkTelemetry(Builder builder) {
        openTelemetry = builder.openTelemetry;
        instrumentationName = builder.instrumentationName;
        instrumentationVersion = builder.instrumentationVersion;
        providerName = builder.providerName;
        identifierPolicy = builder.identifierPolicy;
        contentPolicy = builder.contentPolicy;
        instrumentationFailureHandler = builder.instrumentationFailureHandler;
        contextRegistryOptions = builder.contextRegistryOptions;
        tracer = openTelemetry
                .tracerBuilder(instrumentationName)
                .setInstrumentationVersion(instrumentationVersion)
                .build();
        meter = openTelemetry
                .meterBuilder(instrumentationName)
                .setInstrumentationVersion(instrumentationVersion)
                .build();
        metrics = new TelemetryMetrics(meter);
        contextRegistry = new TelemetryContextRegistry(contextRegistryOptions, this::handleInstrumentationFailure);
    }

    /**
     * Creates a builder using an application-owned OpenTelemetry instance.
     *
     * @param openTelemetry application-owned API instance
     * @return builder
     */
    public static Builder builder(OpenTelemetry openTelemetry) {
        return new Builder(openTelemetry);
    }

    /**
     * Returns the configured instrumentation scope.
     *
     * @return non-blank scope name
     */
    public String instrumentationName() {
        return instrumentationName;
    }

    /**
     * Returns the configured instrumentation version.
     *
     * @return non-blank version
     */
    public String instrumentationVersion() {
        return instrumentationVersion;
    }

    /**
     * Returns the optional GenAI provider name.
     *
     * @return provider name, or {@code null}
     */
    public String providerName() {
        return providerName;
    }

    /**
     * Returns the identifier recording policy.
     *
     * @return identifier policy
     */
    public IdentifierPolicy identifierPolicy() {
        return identifierPolicy;
    }

    /**
     * Returns the content capture policy.
     *
     * @return content policy
     */
    public TelemetryContentPolicy contentPolicy() {
        return contentPolicy;
    }

    /**
     * Returns the isolated instrumentation-failure handler.
     *
     * @return failure handler
     */
    public InstrumentationFailureHandler instrumentationFailureHandler() {
        return instrumentationFailureHandler;
    }

    /**
     * Returns the bounded parent-context registry options.
     *
     * @return registry options
     */
    public TelemetryContextRegistryOptions contextRegistryOptions() {
        return contextRegistryOptions;
    }

    Tracer tracer() {
        return tracer;
    }

    Meter meter() {
        return meter;
    }

    TelemetryMetrics metrics() {
        return metrics;
    }

    TelemetryContextRegistry contextRegistry() {
        return contextRegistry;
    }

    void handleInstrumentationFailure(Throwable failure) {
        if (failure == null || Boolean.TRUE.equals(handlingInstrumentationFailure.get())) {
            return;
        }
        handlingInstrumentationFailure.set(Boolean.TRUE);
        try {
            instrumentationFailureHandler.handle(failure);
        } catch (Throwable ignored) {
            // Instrumentation failure handling must never affect the observed application.
        } finally {
            handlingInstrumentationFailure.remove();
        }
    }

    /** Builds immutable telemetry configuration. */
    public static final class Builder {
        private final OpenTelemetry openTelemetry;

        private String instrumentationName = DEFAULT_INSTRUMENTATION_NAME;

        private String instrumentationVersion = "0.1.0";

        private String providerName;

        private IdentifierPolicy identifierPolicy = IdentifierPolicy.OMIT;

        private TelemetryContentPolicy contentPolicy = TelemetryContentPolicy.disabled();

        private InstrumentationFailureHandler instrumentationFailureHandler =
                InstrumentationFailureHandler.recordOnCurrentSpan();

        private TelemetryContextRegistryOptions contextRegistryOptions = TelemetryContextRegistryOptions.defaults();

        private Builder(OpenTelemetry openTelemetry) {
            this.openTelemetry = java.util.Objects.requireNonNull(openTelemetry, "openTelemetry");
        }

        /**
         * Sets a custom instrumentation scope name.
         *
         * @param instrumentationName non-blank scope name
         * @return this builder
         */
        public Builder instrumentationName(String instrumentationName) {
            this.instrumentationName = requireNonBlank(instrumentationName, "instrumentationName");
            return this;
        }

        /**
         * Sets the instrumentation version.
         *
         * @param instrumentationVersion non-blank version
         * @return this builder
         */
        public Builder instrumentationVersion(String instrumentationVersion) {
            this.instrumentationVersion = requireNonBlank(instrumentationVersion, "instrumentationVersion");
            return this;
        }

        /**
         * Sets the GenAI provider name used by chat telemetry.
         *
         * @param providerName non-blank semantic-convention provider name
         * @return this builder
         */
        public Builder providerName(String providerName) {
            this.providerName = requireNonBlank(providerName, "providerName");
            return this;
        }

        /**
         * Sets identifier recording behavior.
         *
         * @param identifierPolicy identifier policy
         * @return this builder
         */
        public Builder identifierPolicy(IdentifierPolicy identifierPolicy) {
            this.identifierPolicy = java.util.Objects.requireNonNull(identifierPolicy, "identifierPolicy");
            return this;
        }

        /**
         * Sets the explicit content capture policy.
         *
         * @param contentPolicy content policy
         * @return this builder
         */
        public Builder contentPolicy(TelemetryContentPolicy contentPolicy) {
            this.contentPolicy = java.util.Objects.requireNonNull(contentPolicy, "contentPolicy");
            return this;
        }

        /**
         * Sets the callback for optional instrumentation failures.
         *
         * <p>The callback is recursion-guarded and any failure it raises is discarded.
         *
         * @param instrumentationFailureHandler failure handler
         * @return this builder
         */
        public Builder instrumentationFailureHandler(InstrumentationFailureHandler instrumentationFailureHandler) {
            this.instrumentationFailureHandler =
                    java.util.Objects.requireNonNull(instrumentationFailureHandler, "instrumentationFailureHandler");
            return this;
        }

        /**
         * Sets bounded active-context retention options.
         *
         * @param contextRegistryOptions registry options
         * @return this builder
         */
        public Builder contextRegistryOptions(TelemetryContextRegistryOptions contextRegistryOptions) {
            this.contextRegistryOptions =
                    java.util.Objects.requireNonNull(contextRegistryOptions, "contextRegistryOptions");
            return this;
        }

        /**
         * Creates immutable configuration.
         *
         * @return telemetry configuration
         */
        public AgentFrameworkTelemetry build() {
            return new AgentFrameworkTelemetry(this);
        }

        private static String requireNonBlank(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank.");
            }
            return value;
        }
    }
}
