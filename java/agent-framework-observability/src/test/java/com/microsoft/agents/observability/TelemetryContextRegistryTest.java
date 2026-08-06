// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TelemetryContextRegistryTest {
    private InMemorySpanExporter spans;

    private SdkTracerProvider tracerProvider;

    private OpenTelemetry openTelemetry;

    @BeforeEach
    void setUp() {
        spans = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spans))
                .build();
        openTelemetry =
                OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    @Test
    void normalTerminalCompletionRemovesContextDeterministically() {
        // Arrange
        MutableClock clock = new MutableClock();
        AgentFrameworkTelemetry telemetry = telemetry(clock, 4, Duration.ofMinutes(1));
        TelemetryOperation operation = agentOperation(telemetry, "normal");
        telemetry.contextRegistry().registerAgent("normal", operation.context(), operation::abandoned);

        // Act
        operation.success();

        // Assert
        assertThat(telemetry.contextRegistry().size()).isZero();
        assertThat(outcomes()).containsExactly("completed");
    }

    @Test
    void abandonedRunExpiresOnAccessAndEndsSpanWithoutMaintenanceThread() {
        // Arrange
        MutableClock clock = new MutableClock();
        AgentFrameworkTelemetry telemetry = telemetry(clock, 4, Duration.ofSeconds(5));
        TelemetryOperation operation = workflowOperation(telemetry, "hung");
        telemetry.contextRegistry().registerWorkflow("hung", operation.context(), operation::abandoned);

        // Act
        clock.advance(Duration.ofSeconds(5));
        int retained = telemetry.contextRegistry().size();

        // Assert
        assertThat(retained).isZero();
        assertThat(outcomes()).containsExactly("abandoned");
    }

    @Test
    void maximumBoundEvictsOldestRunAndReleasesRemainingRunsNormally() {
        // Arrange
        MutableClock clock = new MutableClock();
        AgentFrameworkTelemetry telemetry = telemetry(clock, 2, Duration.ofHours(1));
        TelemetryOperation first = agentOperation(telemetry, "first");
        TelemetryOperation second = agentOperation(telemetry, "second");
        TelemetryOperation third = agentOperation(telemetry, "third");

        // Act
        telemetry.contextRegistry().registerAgent("first", first.context(), first::abandoned);
        clock.advance(Duration.ofMillis(1));
        telemetry.contextRegistry().registerAgent("second", second.context(), second::abandoned);
        clock.advance(Duration.ofMillis(1));
        telemetry.contextRegistry().registerAgent("third", third.context(), third::abandoned);

        // Assert bound and oldest eviction
        assertThat(telemetry.contextRegistry().size()).isEqualTo(2);
        assertThat(outcomes()).containsExactly("abandoned");

        // Act normal terminal cleanup for retained entries
        second.success();
        third.cancelled();

        // Assert
        assertThat(telemetry.contextRegistry().size()).isZero();
        assertThat(outcomes()).containsExactlyInAnyOrder("abandoned", "completed", "cancelled");
    }

    private AgentFrameworkTelemetry telemetry(Clock clock, int maximumEntries, Duration abandonedRunTtl) {
        return AgentFrameworkTelemetry.builder(openTelemetry)
                .instrumentationVersion("test")
                .contextRegistryOptions(TelemetryContextRegistryOptions.builder()
                        .clock(clock)
                        .maximumEntries(maximumEntries)
                        .abandonedRunTtl(abandonedRunTtl)
                        .build())
                .build();
    }

    private static TelemetryOperation agentOperation(AgentFrameworkTelemetry telemetry, String correlationId) {
        return TelemetryOperation.start(
                telemetry,
                "invoke_agent",
                SpanKind.INTERNAL,
                "invoke_agent",
                null,
                TelemetryContext.AGENT_ACTIVE,
                Attributes.empty(),
                Context.current(),
                () -> telemetry.contextRegistry().removeAgent(correlationId));
    }

    private static TelemetryOperation workflowOperation(AgentFrameworkTelemetry telemetry, String correlationId) {
        return TelemetryOperation.start(
                telemetry,
                "invoke_workflow",
                SpanKind.INTERNAL,
                "invoke_workflow",
                null,
                TelemetryContext.WORKFLOW_ACTIVE,
                Attributes.empty(),
                Context.current(),
                () -> telemetry.contextRegistry().removeWorkflow(correlationId));
    }

    private List<String> outcomes() {
        return spans.getFinishedSpanItems().stream()
                .map(TelemetryContextRegistryTest::outcome)
                .toList();
    }

    private static String outcome(SpanData span) {
        return span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(GenAiAttributes.OUTCOME));
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.EPOCH;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
