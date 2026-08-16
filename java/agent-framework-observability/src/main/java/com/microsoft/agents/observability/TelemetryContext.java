// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.agents.context.SummarizationCompactionStrategy;
import com.microsoft.agents.core.StateValue;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;

final class TelemetryContext {
    static final ContextKey<Boolean> AGENT_ACTIVE = ContextKey.named("com.microsoft.agents.observability.agent.active");

    static final ContextKey<Boolean> CHAT_ACTIVE = ContextKey.named("com.microsoft.agents.observability.chat.active");

    static final ContextKey<Boolean> TOOL_ACTIVE = ContextKey.named("com.microsoft.agents.observability.tool.active");

    static final ContextKey<Boolean> WORKFLOW_ACTIVE =
            ContextKey.named("com.microsoft.agents.observability.workflow.active");

    private TelemetryContext() {}

    static boolean suppressed(ContextKey<Boolean> operationKey) {
        return TelemetrySuppression.isSuppressed()
                || Boolean.TRUE.equals(Context.current().get(operationKey));
    }

    static Context operationContext(Span span, ContextKey<Boolean> operationKey) {
        return Context.current().with(span).with(operationKey, true);
    }

    static boolean requestSuppressed(ChatClientRequest request) {
        StateValue value =
                request.options().metadata().get(SummarizationCompactionStrategy.SUPPRESS_INSTRUMENTATION_METADATA_KEY);
        return value instanceof StateValue.BooleanValue bool && bool.value();
    }
}
