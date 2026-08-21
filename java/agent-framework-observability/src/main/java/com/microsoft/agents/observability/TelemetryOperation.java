// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import com.microsoft.agents.agents.ApprovalRequiredException;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.UsageDetails;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@SuppressWarnings("try")
final class TelemetryOperation {
    private final AgentFrameworkTelemetry telemetry;

    private final Span span;

    private final Context context;

    private final DoubleHistogram duration;

    private final Attributes metricAttributes;

    private final Runnable onEnd;

    private final long startedNanos = System.nanoTime();

    private final AtomicBoolean ended = new AtomicBoolean();

    private TelemetryOperation(
            AgentFrameworkTelemetry telemetry,
            Span span,
            Context context,
            DoubleHistogram duration,
            Attributes metricAttributes,
            Runnable onEnd) {
        this.telemetry = telemetry;
        this.span = span;
        this.context = context;
        this.duration = duration;
        this.metricAttributes = metricAttributes;
        this.onEnd = onEnd;
    }

    static TelemetryOperation start(
            AgentFrameworkTelemetry telemetry,
            String spanName,
            SpanKind kind,
            String operationName,
            String providerName,
            ContextKey<Boolean> activeKey,
            Attributes attributes) {
        return start(
                telemetry,
                spanName,
                kind,
                operationName,
                providerName,
                activeKey,
                attributes,
                Context.current(),
                () -> {});
    }

    static TelemetryOperation start(
            AgentFrameworkTelemetry telemetry,
            String spanName,
            SpanKind kind,
            String operationName,
            String providerName,
            ContextKey<Boolean> activeKey,
            Attributes attributes,
            Context parent,
            Runnable onEnd) {
        Context checkedParent = parent == null ? Context.current() : parent;
        Attributes spanAttributes = TelemetryMetrics.spanAttributes(operationName, providerName, attributes);
        Attributes metricAttributes = TelemetryMetrics.metricAttributes(operationName, providerName, attributes);
        Span span = null;
        Context context = checkedParent;
        try {
            context = checkedParent.with(activeKey, true);
        } catch (Throwable failure) {
            telemetry.handleInstrumentationFailure(failure);
        }
        try {
            SpanBuilder spanBuilder = telemetry
                    .tracer()
                    .spanBuilder(spanName)
                    .setSpanKind(kind)
                    .setParent(checkedParent)
                    .setAllAttributes(spanAttributes);
            span = spanBuilder.startSpan();
            context = checkedParent.with(span).with(activeKey, true);
        } catch (Throwable failure) {
            telemetry.handleInstrumentationFailure(failure);
        }

        DoubleHistogram duration = null;
        try {
            duration = telemetry.metrics().duration(operationName);
        } catch (Throwable failure) {
            report(telemetry, context, failure);
        }
        return new TelemetryOperation(telemetry, span, context, duration, metricAttributes, onEnd);
    }

    Context context() {
        return context;
    }

    Attributes metricAttributes() {
        return metricAttributes;
    }

    void stringAttribute(String key, String value) {
        if (value != null) {
            record(() -> span.setAttribute(key, value));
        }
    }

    void stringAttribute(String key, Supplier<String> value) {
        String captured = instrumentationValue(value);
        if (captured != null) {
            stringAttribute(key, captured);
        }
    }

    void longAttribute(String key, long value) {
        record(() -> span.setAttribute(key, value));
    }

    void booleanAttribute(String key, boolean value) {
        record(() -> span.setAttribute(key, value));
    }

    void stringListAttribute(String key, List<String> values) {
        if (values != null && !values.isEmpty()) {
            record(() -> span.setAttribute(AttributeKey.stringArrayKey(key), values));
        }
    }

    /**
     * Records the GenAI token-usage attributes carried by {@code usage}. Usage details that the provider
     * did not report are omitted, and counts wider than a signed 64-bit integer are skipped because the
     * OpenTelemetry attribute type cannot represent them.
     */
    void usageAttributes(UsageDetails usage) {
        if (usage == null) {
            return;
        }
        usageAttribute(GenAiAttributes.USAGE_INPUT_TOKENS, usage.inputTokens());
        usageAttribute(GenAiAttributes.USAGE_OUTPUT_TOKENS, usage.outputTokens());
        usageAttribute(
                GenAiAttributes.USAGE_CACHE_CREATION_INPUT_TOKENS,
                usage.integer(UsageDetails.CACHE_CREATION_INPUT_TOKENS));
        usageAttribute(
                GenAiAttributes.USAGE_CACHE_READ_INPUT_TOKENS, usage.integer(UsageDetails.CACHE_READ_INPUT_TOKENS));
        usageAttribute(
                GenAiAttributes.USAGE_REASONING_OUTPUT_TOKENS, usage.integer(UsageDetails.REASONING_OUTPUT_TOKENS));
    }

    private void usageAttribute(String key, Optional<BigInteger> value) {
        value.filter(count -> count.bitLength() <= 63).ifPresent(count -> longAttribute(key, count.longValue()));
    }

    void event(String name, Attributes attributes) {
        record(() -> span.addEvent(name, attributes));
    }

    void observeInstrumentation(Runnable observer) {
        try (Scope ignored = context.makeCurrent()) {
            observer.run();
        } catch (Throwable failure) {
            instrumentationFailure(failure);
        }
    }

    void runWithContext(Runnable action) {
        callWithContext(() -> {
            action.run();
            return null;
        });
    }

    <T> T callWithContext(Supplier<T> action) {
        Scope scope = null;
        try {
            scope = context.makeCurrent();
        } catch (Throwable failure) {
            telemetry.handleInstrumentationFailure(failure);
        }
        try {
            return action.get();
        } finally {
            if (scope != null) {
                try {
                    scope.close();
                } catch (Throwable failure) {
                    telemetry.handleInstrumentationFailure(failure);
                }
            }
        }
    }

    <T> T instrumentationValue(Supplier<T> observer) {
        try (Scope ignored = context.makeCurrent()) {
            return observer.get();
        } catch (Throwable failure) {
            instrumentationFailure(failure);
            return null;
        }
    }

    void instrumentationFailure(Throwable failure) {
        try (Scope ignored = context.makeCurrent()) {
            telemetry.handleInstrumentationFailure(failure);
        } catch (Throwable ignored) {
            // Instrumentation failure handling cannot escape.
        }
    }

    void success() {
        finish("completed", null, true);
    }

    void cancelled() {
        finish("cancelled", null, true);
    }

    void abandoned() {
        finish("abandoned", null, false);
    }

    void failure(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof RunCancelledException || cause instanceof CancellationException) {
            cancelled();
        } else if (cause instanceof ApprovalRequiredException) {
            finish("input_required", null, true);
        } else {
            finish("failed", cause, true);
        }
    }

    private void finish(String outcome, Throwable failure, boolean runEndCallback) {
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        Attributes durationAttributes = metricAttributes;
        stringAttribute(GenAiAttributes.OUTCOME, outcome);
        if (failure != null) {
            String errorType = failure.getClass().getName();
            stringAttribute(GenAiAttributes.ERROR_TYPE, errorType);
            record(() -> span.recordException(failure));
            record(() -> span.setStatus(StatusCode.ERROR));
            try {
                durationAttributes = durationAttributes.toBuilder()
                        .put(AttributeKey.stringKey(GenAiAttributes.ERROR_TYPE), errorType)
                        .build();
            } catch (Throwable instrumentationFailure) {
                telemetry.handleInstrumentationFailure(instrumentationFailure);
            }
        }
        record(() -> span.end());

        Attributes finalDurationAttributes = durationAttributes;
        if (duration != null) {
            observeInstrumentation(() ->
                    duration.record((System.nanoTime() - startedNanos) / 1_000_000_000.0, finalDurationAttributes));
        }
        if (runEndCallback) {
            try {
                runWithContext(onEnd);
            } catch (Throwable instrumentationFailure) {
                telemetry.handleInstrumentationFailure(instrumentationFailure);
            }
        }
    }

    private void record(Runnable recorder) {
        if (span == null) {
            return;
        }
        observeInstrumentation(recorder);
    }

    private static void report(AgentFrameworkTelemetry telemetry, Context context, Throwable failure) {
        try (Scope ignored = context.makeCurrent()) {
            telemetry.handleInstrumentationFailure(failure);
        } catch (Throwable ignored) {
            // Failure handling is optional instrumentation and cannot escape.
        }
    }

    static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
