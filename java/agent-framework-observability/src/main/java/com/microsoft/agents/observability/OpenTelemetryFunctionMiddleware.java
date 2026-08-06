// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import com.microsoft.agents.agents.FunctionMiddleware;
import com.microsoft.agents.agents.FunctionMiddlewareContext;
import com.microsoft.agents.agents.FunctionMiddlewareNext;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.ToolInvocationContext;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import java.util.concurrent.CompletionStage;

/** Instruments actual local function-tool execution with GenAI tool spans and metrics. */
@SuppressWarnings("try")
public final class OpenTelemetryFunctionMiddleware implements FunctionMiddleware {
    private final AgentFrameworkTelemetry telemetry;

    private final TelemetrySanitizer sanitizer;

    /**
     * Creates function middleware.
     *
     * @param telemetry telemetry configuration
     */
    public OpenTelemetryFunctionMiddleware(AgentFrameworkTelemetry telemetry) {
        this.telemetry = java.util.Objects.requireNonNull(telemetry, "telemetry");
        this.sanitizer = new TelemetrySanitizer(telemetry);
    }

    @Override
    public CompletionStage<StateValue> invokeAsync(FunctionMiddlewareContext context, FunctionMiddlewareNext next) {
        java.util.Objects.requireNonNull(context, "context");
        java.util.Objects.requireNonNull(next, "next");
        if (TelemetryContext.suppressed(TelemetryContext.TOOL_ACTIVE)) {
            return next.invokeAsync(context);
        }
        String toolName = context.invocation().tool().name();
        ToolInvocationContext invocation = context.invocation().invocation();
        TelemetryOperation operation = TelemetryOperation.start(
                telemetry,
                "execute_tool " + toolName,
                SpanKind.INTERNAL,
                "execute_tool",
                null,
                TelemetryContext.TOOL_ACTIVE,
                Attributes.builder()
                        .put(GenAiAttributes.TOOL_NAME, toolName)
                        .put(GenAiAttributes.TOOL_TYPE, "function")
                        .build(),
                telemetry.contextRegistry().agentParent(invocation.metadata()),
                () -> {});
        operation.stringAttribute(GenAiAttributes.TOOL_CALL_ID, () -> sanitizer.identifier(invocation.callId()));
        operation.stringAttribute(
                GenAiAttributes.INVOCATION_ID,
                () -> sanitizer.identifier(invocation.invocationId().value()));
        if (context.session() != null) {
            operation.stringAttribute(
                    GenAiAttributes.CONVERSATION_ID,
                    () -> sanitizer.identifier(context.session().sessionId()));
        }
        operation.stringAttribute(
                GenAiAttributes.TOOL_CALL_ARGUMENTS,
                () -> sanitizer.state(context.invocation().arguments()));
        CompletionStage<StateValue> stage;
        try {
            stage = operation.callWithContext(() -> next.invokeAsync(context));
        } catch (RuntimeException failure) {
            operation.failure(failure);
            throw failure;
        }
        return TelemetryStages.observe(
                stage,
                operation,
                result -> operation.stringAttribute(GenAiAttributes.TOOL_CALL_RESULT, () -> sanitizer.state(result)));
    }
}
