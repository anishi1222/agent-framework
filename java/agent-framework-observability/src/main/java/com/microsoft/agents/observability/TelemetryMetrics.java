// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import com.microsoft.agents.core.UsageDetails;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import java.math.BigInteger;
import java.util.Set;

final class TelemetryMetrics {
    static final Set<String> IDENTIFIER_ATTRIBUTE_KEYS = Set.of(
            GenAiAttributes.RESPONSE_ID,
            GenAiAttributes.CONVERSATION_ID,
            GenAiAttributes.RUN_ID,
            GenAiAttributes.AGENT_ID,
            GenAiAttributes.TOOL_CALL_ID,
            GenAiAttributes.INVOCATION_ID);

    private final DoubleHistogram chatDuration;

    private final DoubleHistogram agentDuration;

    private final DoubleHistogram workflowDuration;

    private final DoubleHistogram toolDuration;

    private final LongHistogram tokenUsage;

    TelemetryMetrics(Meter meter) {
        chatDuration = meter.histogramBuilder("gen_ai.client.operation.duration")
                .setUnit("s")
                .setDescription("GenAI operation duration.")
                .build();
        agentDuration = meter.histogramBuilder("gen_ai.invoke_agent.duration")
                .setUnit("s")
                .setDescription("GenAI agent invocation duration.")
                .build();
        workflowDuration = meter.histogramBuilder("gen_ai.invoke_workflow.duration")
                .setUnit("s")
                .setDescription("GenAI workflow invocation duration.")
                .build();
        toolDuration = meter.histogramBuilder("gen_ai.execute_tool.duration")
                .setUnit("s")
                .setDescription("GenAI tool execution duration.")
                .build();
        tokenUsage = meter.histogramBuilder("gen_ai.client.token.usage")
                .ofLongs()
                .setUnit("{token}")
                .setDescription("Number of input and output tokens used.")
                .build();
    }

    DoubleHistogram duration(String operation) {
        return switch (operation) {
            case "chat" -> chatDuration;
            case "invoke_agent" -> agentDuration;
            case "invoke_workflow" -> workflowDuration;
            case "execute_tool" -> toolDuration;
            default -> throw new IllegalArgumentException("Unsupported telemetry operation '" + operation + "'.");
        };
    }

    void recordUsage(UsageDetails usage, Attributes attributes) {
        if (usage == null) {
            return;
        }
        recordTokens(usage.inputTokens().orElse(null), "input", attributes);
        recordTokens(usage.outputTokens().orElse(null), "output", attributes);
    }

    private void recordTokens(BigInteger value, String type, Attributes attributes) {
        if (value == null || value.signum() < 0 || value.bitLength() > 63) {
            return;
        }
        AttributesBuilder builder = metricAttributes(attributes);
        builder.put(AttributeKey.stringKey(GenAiAttributes.TOKEN_TYPE), type);
        tokenUsage.record(value.longValue(), builder.build());
    }

    static Attributes spanAttributes(String operationName, String providerName, Attributes attributes) {
        AttributesBuilder builder = attributes.toBuilder();
        builder.put(AttributeKey.stringKey(GenAiAttributes.OPERATION_NAME), operationName);
        if (providerName != null) {
            builder.put(AttributeKey.stringKey(GenAiAttributes.PROVIDER_NAME), providerName);
        }
        return builder.build();
    }

    static Attributes metricAttributes(String operationName, String providerName, Attributes attributes) {
        AttributesBuilder builder = Attributes.builder();
        builder.put(AttributeKey.stringKey(GenAiAttributes.OPERATION_NAME), operationName);
        if (providerName != null) {
            builder.put(AttributeKey.stringKey(GenAiAttributes.PROVIDER_NAME), providerName);
        }
        copyString(attributes, builder, GenAiAttributes.REQUEST_MODEL);
        copyString(attributes, builder, GenAiAttributes.AGENT_NAME);
        copyString(attributes, builder, GenAiAttributes.WORKFLOW_NAME);
        copyString(attributes, builder, GenAiAttributes.TOOL_NAME);
        copyString(attributes, builder, GenAiAttributes.TOOL_TYPE);
        copyBoolean(attributes, builder, "agent_framework.workflow.resumed");
        return builder.build();
    }

    private static AttributesBuilder metricAttributes(Attributes attributes) {
        AttributesBuilder builder = Attributes.builder();
        copyString(attributes, builder, GenAiAttributes.OPERATION_NAME);
        copyString(attributes, builder, GenAiAttributes.PROVIDER_NAME);
        copyString(attributes, builder, GenAiAttributes.REQUEST_MODEL);
        copyString(attributes, builder, GenAiAttributes.AGENT_NAME);
        copyString(attributes, builder, GenAiAttributes.WORKFLOW_NAME);
        copyString(attributes, builder, GenAiAttributes.TOOL_NAME);
        copyString(attributes, builder, GenAiAttributes.TOOL_TYPE);
        copyString(attributes, builder, GenAiAttributes.ERROR_TYPE);
        copyBoolean(attributes, builder, "agent_framework.workflow.resumed");
        return builder;
    }

    private static void copyString(Attributes source, AttributesBuilder target, String key) {
        AttributeKey<String> attributeKey = AttributeKey.stringKey(key);
        String value = source.get(attributeKey);
        if (value != null) {
            target.put(attributeKey, value);
        }
    }

    private static void copyBoolean(Attributes source, AttributesBuilder target, String key) {
        AttributeKey<Boolean> attributeKey = AttributeKey.booleanKey(key);
        Boolean value = source.get(attributeKey);
        if (value != null) {
            target.put(attributeKey, value);
        }
    }
}
