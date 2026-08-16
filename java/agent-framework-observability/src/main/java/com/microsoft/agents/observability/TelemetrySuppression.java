// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;

/**
 * Suppresses Agent Framework instrumentation within an explicit lexical scope.
 *
 * <p>Suppression is context-local, propagates through OpenTelemetry context wrapping, and never
 * mutates global SDK configuration.
 */
public final class TelemetrySuppression {
    private static final ContextKey<Boolean> SUPPRESS_ALL =
            ContextKey.named("com.microsoft.agents.observability.suppress");

    private TelemetrySuppression() {}

    /**
     * Enters a suppression scope.
     *
     * @return framework-owned scope that must be closed
     */
    public static SuppressionScope suppress() {
        Scope scope = Context.current().with(SUPPRESS_ALL, true).makeCurrent();
        return scope::close;
    }

    /**
     * Reports whether current execution is suppressed.
     *
     * @return suppression state
     */
    public static boolean isSuppressed() {
        return Boolean.TRUE.equals(Context.current().get(SUPPRESS_ALL));
    }

    /** Represents an AutoCloseable suppression scope without exposing an OpenTelemetry scope type. */
    @FunctionalInterface
    public interface SuppressionScope extends AutoCloseable {
        /** Restores the prior context. */
        @Override
        void close();
    }
}
