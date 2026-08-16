// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.conformance.BehaviorFixture;
import com.microsoft.agents.conformance.ConformanceFixtureLoader;
import com.microsoft.agents.conformance.ConformanceValue;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ObservabilityConformanceTest {
    @Test
    void jcfTelemetry003_shouldBindHierarchyPrivacyMetricsAndTerminalSettlement() {
        // Arrange
        BehaviorFixture fixture =
                (BehaviorFixture) new ConformanceFixtureLoader().loadDefault().requireCase("JCF-TELEMETRY-003");
        InMemorySpanExporter spans = InMemorySpanExporter.create();
        InMemoryMetricReader metrics = InMemoryMetricReader.create();
        try (SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(spans))
                        .build();
                SdkMeterProvider meterProvider =
                        SdkMeterProvider.builder().registerMetricReader(metrics).build();
                OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                        .setTracerProvider(tracerProvider)
                        .setMeterProvider(meterProvider)
                        .build()) {
            AgentFrameworkTelemetry telemetry = AgentFrameworkTelemetry.builder(openTelemetry)
                    .instrumentationVersion("conformance")
                    .providerName(text(fixture.input().require("providerName")))
                    .build();
            TelemetryOperation workflow = TelemetryOperation.start(
                    telemetry,
                    "invoke_workflow conformance",
                    SpanKind.INTERNAL,
                    "invoke_workflow",
                    null,
                    TelemetryContext.WORKFLOW_ACTIVE,
                    Attributes.empty());
            TelemetryOperation agent = TelemetryOperation.start(
                    telemetry,
                    "invoke_agent conformance",
                    SpanKind.INTERNAL,
                    "invoke_agent",
                    null,
                    TelemetryContext.AGENT_ACTIVE,
                    Attributes.empty(),
                    workflow.context(),
                    () -> {});
            TelemetryOperation chat = TelemetryOperation.start(
                    telemetry,
                    "chat conformance",
                    SpanKind.CLIENT,
                    "chat",
                    telemetry.providerName(),
                    TelemetryContext.CHAT_ACTIVE,
                    Attributes.empty(),
                    agent.context(),
                    () -> {});
            TelemetryOperation tool = TelemetryOperation.start(
                    telemetry,
                    "execute_tool conformance",
                    SpanKind.INTERNAL,
                    "execute_tool",
                    null,
                    TelemetryContext.TOOL_ACTIVE,
                    Attributes.empty(),
                    agent.context(),
                    () -> {});

            // Act
            chat.success();
            tool.cancelled();
            tool.cancelled();
            agent.success();
            workflow.success();

            // Assert
            Map<String, SpanData> byOperation = spans.getFinishedSpanItems().stream()
                    .collect(Collectors.toMap(
                            span -> span.getAttributes().get(AttributeKey.stringKey(GenAiAttributes.OPERATION_NAME)),
                            Function.identity()));
            assertThat(byOperation).hasSize(integer(fixture.expected().require("spanCount")));
            assertThat(byOperation.keySet())
                    .containsExactlyInAnyOrderElementsOf(strings(fixture.input().require("operations")));
            SpanData workflowSpan = byOperation.get("invoke_workflow");
            SpanData agentSpan = byOperation.get("invoke_agent");
            SpanData chatSpan = byOperation.get("chat");
            SpanData toolSpan = byOperation.get("execute_tool");
            assertThat(workflowSpan.getParentSpanContext().isValid())
                    .isEqualTo(!bool(fixture.expected().require("workflowParentRoot")));
            assertThat(agentSpan.getParentSpanContext().getSpanId())
                    .isEqualTo(workflowSpan.getSpanContext().getSpanId());
            assertThat(chatSpan.getParentSpanContext().getSpanId())
                    .isEqualTo(agentSpan.getSpanContext().getSpanId());
            assertThat(toolSpan.getParentSpanContext().getSpanId())
                    .isEqualTo(agentSpan.getSpanContext().getSpanId());
            assertThat(toolSpan.getAttributes().get(AttributeKey.stringKey(GenAiAttributes.OUTCOME)))
                    .isEqualTo(text(fixture.expected().require("cancelledOutcome")));
            assertThat(spans.getFinishedSpanItems())
                    .filteredOn(span -> span.getName().startsWith("execute_tool"))
                    .hasSize(bool(fixture.expected().require("terminalIdempotent")) ? 1 : 2);
            assertThat(telemetry.contentPolicy().captureContent())
                    .isEqualTo(bool(fixture.expected().require("contentCapturedByDefault")));
            assertThat(metrics.collectAllMetrics())
                    .extracting(metric -> metric.getName())
                    .containsAll(strings(fixture.expected().require("durationMetrics")));
        }
    }

    private static String text(ConformanceValue value) {
        return ((ConformanceValue.StringValue) value).value();
    }

    private static boolean bool(ConformanceValue value) {
        return ((ConformanceValue.BooleanValue) value).value();
    }

    private static int integer(ConformanceValue value) {
        return ((ConformanceValue.NumberValue) value).value().intValueExact();
    }

    private static List<String> strings(ConformanceValue value) {
        return ((ConformanceValue.ArrayValue) value)
                .values().stream().map(ObservabilityConformanceTest::text).toList();
    }
}
