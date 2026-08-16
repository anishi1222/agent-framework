// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.UsageContent;
import com.microsoft.agents.core.UsageDetails;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.SpanKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Decorates any agent with current GenAI local-agent spans and metrics.
 *
 * @param <T> structured response value type
 */
@SuppressWarnings("try")
public final class OpenTelemetryAgent<T> implements Agent<T> {
    private final Agent<T> delegate;

    private final AgentFrameworkTelemetry telemetry;

    private final TelemetrySanitizer sanitizer;

    private final boolean closeDelegate;

    /**
     * Creates a non-owning agent decorator.
     *
     * @param delegate caller-owned agent
     * @param telemetry telemetry configuration
     */
    public OpenTelemetryAgent(Agent<T> delegate, AgentFrameworkTelemetry telemetry) {
        this(delegate, telemetry, false);
    }

    /**
     * Creates an agent decorator.
     *
     * @param delegate agent
     * @param telemetry telemetry configuration
     * @param closeDelegate whether closing this decorator closes the agent
     */
    public OpenTelemetryAgent(Agent<T> delegate, AgentFrameworkTelemetry telemetry, boolean closeDelegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
        this.telemetry = java.util.Objects.requireNonNull(telemetry, "telemetry");
        this.sanitizer = new TelemetrySanitizer(telemetry);
        this.closeDelegate = closeDelegate;
    }

    @Override
    public AgentMetadata metadata() {
        return delegate.metadata();
    }

    @Override
    public RunHandle<AgentResponse<T>> startRun(
            List<Message> messages, RunOptions options, RunCancellation cancellation) {
        require(messages, options, cancellation);
        if (TelemetryContext.suppressed(TelemetryContext.AGENT_ACTIVE)) {
            return delegate.startRun(messages, options, cancellation);
        }
        String correlationId = UUID.randomUUID().toString();
        RunOptions observedOptions = instrumentedOptions(options, correlationId);
        TelemetryOperation operation = start(messages, options, correlationId);
        telemetry.contextRegistry().registerAgent(correlationId, operation.context(), operation::abandoned);
        RunHandle<AgentResponse<T>> handle;
        try {
            handle = operation.callWithContext(() -> delegate.startRun(messages, observedOptions, cancellation));
        } catch (RuntimeException failure) {
            operation.failure(failure);
            throw failure;
        }
        if (handle == null) {
            IllegalStateException failure = new IllegalStateException("Agent.startRun returned null.");
            operation.failure(failure);
            throw failure;
        }
        CompletionStage<AgentResponse<T>> observed = TelemetryStages.observe(
                handle.resultAsync(), operation, response -> recordResponse(operation, response));
        return new ObservedRunHandle<>(observed, handle.cancellation());
    }

    @Override
    public Flow.Publisher<AgentResponseUpdate> runStreaming(
            List<Message> messages, RunOptions options, RunCancellation cancellation) {
        require(messages, options, cancellation);
        if (TelemetryContext.suppressed(TelemetryContext.AGENT_ACTIVE)) {
            return delegate.runStreaming(messages, options, cancellation);
        }
        String correlationId = UUID.randomUUID().toString();
        RunOptions observedOptions = instrumentedOptions(options, correlationId);
        AtomicReference<TelemetryOperation> operationReference = new AtomicReference<>();
        StreamingUsageCapture usageCapture = new StreamingUsageCapture();
        StreamingPayloadCapture outputCapture = new StreamingPayloadCapture(telemetry, sanitizer);
        return new TelemetryPublisher<>(
                () -> {
                    TelemetryOperation operation = start(messages, options, correlationId);
                    telemetry.contextRegistry().registerAgent(correlationId, operation.context(), operation::abandoned);
                    operationReference.set(operation);
                    return operation;
                },
                () -> delegate.runStreaming(messages, observedOptions, cancellation),
                update -> recordUpdate(operationReference.get(), update, outputCapture, usageCapture),
                () -> {
                    TelemetryOperation operation = operationReference.get();
                    outputCapture.record(operation);
                    recordUsage(operation, usageCapture.value());
                },
                cancellation);
    }

    @Override
    public void close() {
        if (closeDelegate) {
            delegate.close();
        }
    }

    private TelemetryOperation start(List<Message> messages, RunOptions options, String correlationId) {
        AgentMetadata metadata = delegate.metadata();
        AttributesBuilder attributes = Attributes.builder();
        if (metadata.name() != null) {
            attributes.put(GenAiAttributes.AGENT_NAME, metadata.name());
        }
        TelemetryOperation operation = TelemetryOperation.start(
                telemetry,
                metadata.name() == null ? "invoke_agent" : "invoke_agent " + metadata.name(),
                SpanKind.INTERNAL,
                "invoke_agent",
                null,
                TelemetryContext.AGENT_ACTIVE,
                attributes.build(),
                telemetry.contextRegistry().workflowParent(options.metadata()),
                () -> telemetry.contextRegistry().removeAgent(correlationId));
        operation.stringAttribute(GenAiAttributes.AGENT_ID, () -> sanitizer.identifier(metadata.id()));
        operation.stringAttribute(GenAiAttributes.AGENT_DESCRIPTION, metadata.description());
        operation.stringAttribute(GenAiAttributes.INPUT_MESSAGES, () -> sanitizer.messages(messages));
        return operation;
    }

    private static RunOptions instrumentedOptions(RunOptions options, String correlationId) {
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(options.metadata());
        metadata.put(TelemetryContextRegistry.CORRELATION_METADATA_KEY, StateValue.string(correlationId));
        return new RunOptions(options.maxIterations(), options.maxFunctionCalls(), Map.copyOf(metadata));
    }

    private void recordResponse(TelemetryOperation operation, AgentResponse<T> response) {
        if (response == null) {
            throw new IllegalStateException("Agent completed with null response.");
        }
        operation.stringAttribute(GenAiAttributes.RESPONSE_ID, () -> sanitizer.identifier(response.responseId()));
        if (response.finishReason() != null) {
            operation.stringListAttribute(
                    GenAiAttributes.RESPONSE_FINISH_REASONS,
                    List.of(response.finishReason().value()));
        }
        recordUsage(operation, response.usage());
        operation.stringAttribute(GenAiAttributes.OUTPUT_MESSAGES, () -> sanitizer.messages(response.messages()));
    }

    private void recordUpdate(
            TelemetryOperation operation,
            AgentResponseUpdate update,
            StreamingPayloadCapture outputCapture,
            StreamingUsageCapture usageCapture) {
        if (update == null) {
            throw new IllegalStateException("Agent emitted a null update.");
        }
        operation.stringAttribute(GenAiAttributes.RESPONSE_ID, () -> sanitizer.identifier(update.responseId()));
        if (update.finishReason() != null) {
            operation.stringListAttribute(
                    GenAiAttributes.RESPONSE_FINISH_REASONS,
                    List.of(update.finishReason().value()));
        }
        AttributesBuilder eventAttributes = Attributes.builder();
        if (update.sequence() != null) {
            eventAttributes.put("agent_framework.update.sequence", update.sequence());
        }
        operation.event("agent_framework.gen_ai.response.chunk", eventAttributes.build());
        update.contents().stream()
                .filter(UsageContent.class::isInstance)
                .map(UsageContent.class::cast)
                .map(UsageContent::usage)
                .forEach(usageCapture::add);
        usageCapture.add(update.usage());
        if (!update.contents().isEmpty()) {
            outputCapture.add(
                    update.role() == null ? com.microsoft.agents.core.Role.ASSISTANT : update.role(),
                    update.contents());
        }
    }

    private void recordUsage(TelemetryOperation operation, UsageDetails usage) {
        if (usage == null) {
            return;
        }
        usage.inputTokens()
                .filter(value -> value.bitLength() <= 63)
                .ifPresent(value -> operation.longAttribute(GenAiAttributes.USAGE_INPUT_TOKENS, value.longValue()));
        usage.outputTokens()
                .filter(value -> value.bitLength() <= 63)
                .ifPresent(value -> operation.longAttribute(GenAiAttributes.USAGE_OUTPUT_TOKENS, value.longValue()));
    }

    private static void require(List<Message> messages, RunOptions options, RunCancellation cancellation) {
        List.copyOf(messages);
        java.util.Objects.requireNonNull(options, "options");
        java.util.Objects.requireNonNull(cancellation, "cancellation");
    }
}
