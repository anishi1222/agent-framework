// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.UsageContent;
import com.microsoft.agents.core.UsageDetails;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.SpanKind;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Decorates a provider-neutral chat client with current GenAI client spans and metrics.
 *
 * <p>The delegate remains caller-owned unless {@code closeDelegate} is explicitly enabled. Message
 * payloads are omitted by default and internal summarization requests are automatically suppressed.
 */
@SuppressWarnings("try")
public final class OpenTelemetryChatClient implements ChatClient {
    private final ChatClient delegate;

    private final AgentFrameworkTelemetry telemetry;

    private final String providerName;

    private final TelemetrySanitizer sanitizer;

    private final boolean closeDelegate;

    /**
     * Creates a non-owning decorator using the provider configured on {@code telemetry}.
     *
     * @param delegate caller-owned chat client
     * @param telemetry telemetry configuration with a provider name
     */
    public OpenTelemetryChatClient(ChatClient delegate, AgentFrameworkTelemetry telemetry) {
        this(delegate, telemetry, requireProvider(telemetry), false);
    }

    /**
     * Creates a chat-client decorator.
     *
     * @param delegate chat client
     * @param telemetry telemetry configuration
     * @param providerName non-blank GenAI semantic-convention provider name
     * @param closeDelegate whether closing this decorator closes the delegate
     */
    public OpenTelemetryChatClient(
            ChatClient delegate, AgentFrameworkTelemetry telemetry, String providerName, boolean closeDelegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
        this.telemetry = java.util.Objects.requireNonNull(telemetry, "telemetry");
        if (providerName == null || providerName.isBlank()) {
            throw new IllegalArgumentException("providerName must not be blank.");
        }
        this.providerName = providerName;
        this.sanitizer = new TelemetrySanitizer(telemetry);
        this.closeDelegate = closeDelegate;
    }

    @Override
    public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
        require(request, cancellation);
        if (suppressed(request)) {
            return delegate.completeAsync(request, cancellation);
        }
        TelemetryOperation operation = start(request, cancellation, false);
        CompletionStage<ChatResponse> stage;
        try {
            stage = operation.callWithContext(() -> delegate.completeAsync(request, cancellation));
        } catch (RuntimeException failure) {
            operation.failure(failure);
            throw failure;
        }
        return TelemetryStages.observe(stage, operation, response -> recordResponse(operation, response));
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> completeStreaming(
            ChatClientRequest request, RunCancellation cancellation) {
        require(request, cancellation);
        if (suppressed(request)) {
            return delegate.completeStreaming(request, cancellation);
        }
        AtomicReference<TelemetryOperation> operationReference = new AtomicReference<>();
        StreamingUsageCapture usageCapture = new StreamingUsageCapture();
        StreamingPayloadCapture outputCapture = new StreamingPayloadCapture(telemetry, sanitizer);
        return new TelemetryPublisher<>(
                () -> {
                    TelemetryOperation operation = start(request, cancellation, true);
                    operationReference.set(operation);
                    return operation;
                },
                () -> delegate.completeStreaming(request, cancellation),
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

    private TelemetryOperation start(ChatClientRequest request, RunCancellation cancellation, boolean streaming) {
        String model = request.options().model();
        AttributesBuilder attributes = Attributes.builder();
        if (model != null) {
            attributes.put(GenAiAttributes.REQUEST_MODEL, model);
        }
        attributes.put("gen_ai.request.stream", streaming);
        TelemetryOperation operation = TelemetryOperation.start(
                telemetry,
                model == null ? "chat" : "chat " + model,
                SpanKind.CLIENT,
                "chat",
                providerName,
                TelemetryContext.CHAT_ACTIVE,
                attributes.build(),
                request.runContext() == null
                        ? io.opentelemetry.context.Context.current()
                        : telemetry
                                .contextRegistry()
                                .agentParent(request.runContext().metadata()),
                () -> {});
        operation.stringAttribute(
                GenAiAttributes.CONVERSATION_ID,
                () -> sanitizer.identifier(request.options().conversationId()));
        operation.stringAttribute(GenAiAttributes.INPUT_MESSAGES, () -> sanitizer.messages(request.messages()));
        return operation;
    }

    private void recordResponse(TelemetryOperation operation, ChatResponse response) {
        if (response == null) {
            throw new IllegalStateException("Chat client completed with null response.");
        }
        operation.stringAttribute(GenAiAttributes.RESPONSE_MODEL, response.model());
        operation.stringAttribute(GenAiAttributes.RESPONSE_ID, () -> sanitizer.identifier(response.responseId()));
        operation.stringAttribute(
                GenAiAttributes.CONVERSATION_ID, () -> sanitizer.identifier(response.conversationId()));
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
            ChatResponseUpdate update,
            StreamingPayloadCapture outputCapture,
            StreamingUsageCapture usageCapture) {
        if (update == null) {
            throw new IllegalStateException("Chat client emitted a null update.");
        }
        operation.stringAttribute(GenAiAttributes.RESPONSE_MODEL, update.model());
        operation.stringAttribute(GenAiAttributes.RESPONSE_ID, () -> sanitizer.identifier(update.responseId()));
        operation.stringAttribute(GenAiAttributes.CONVERSATION_ID, () -> sanitizer.identifier(update.conversationId()));
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
            outputCapture.add(update.role() == null ? Role.ASSISTANT : update.role(), update.contents());
        }
    }

    private void recordUsage(TelemetryOperation operation, UsageDetails usage) {
        if (usage == null) {
            return;
        }
        operation.usageAttributes(usage);
        telemetry.metrics().recordUsage(usage, operation.metricAttributes());
    }

    private boolean suppressed(ChatClientRequest request) {
        return TelemetryContext.suppressed(TelemetryContext.CHAT_ACTIVE) || TelemetryContext.requestSuppressed(request);
    }

    private static void require(ChatClientRequest request, RunCancellation cancellation) {
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(cancellation, "cancellation");
    }

    private static String requireProvider(AgentFrameworkTelemetry telemetry) {
        java.util.Objects.requireNonNull(telemetry, "telemetry");
        String provider = telemetry.providerName();
        if (provider == null) {
            throw new IllegalArgumentException("telemetry.providerName must be configured for chat instrumentation.");
        }
        return provider;
    }
}
