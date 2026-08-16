// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;

/**
 * Receives failures raised only by optional observability instrumentation.
 *
 * <p>The framework prevents handler failures and recursive handler invocation from escaping into the
 * application. Implementations must not assume that an active recording span exists and should avoid
 * recording exception messages or application values that may contain sensitive data.
 */
@FunctionalInterface
public interface InstrumentationFailureHandler {
    /**
     * Handles one instrumentation failure.
     *
     * @param failure instrumentation failure
     */
    void handle(Throwable failure);

    /**
     * Returns the privacy-safe default handler.
     *
     * <p>When a recording span is current, the handler adds an internal event containing only the
     * exception type. If no recording span is available, or if the span implementation rejects the
     * event, handling is a no-op. No exception message or application value is logged.
     *
     * @return default handler
     */
    static InstrumentationFailureHandler recordOnCurrentSpan() {
        return failure -> {
            Span span = Span.current();
            if (span.getSpanContext().isValid() && span.isRecording()) {
                span.addEvent(
                        "agent_framework.instrumentation.failure",
                        Attributes.builder()
                                .put("error.type", failure.getClass().getName())
                                .build());
            }
        };
    }

    /**
     * Returns a handler that intentionally discards instrumentation failures.
     *
     * @return no-op handler
     */
    static InstrumentationFailureHandler noOp() {
        return failure -> {};
    }
}
