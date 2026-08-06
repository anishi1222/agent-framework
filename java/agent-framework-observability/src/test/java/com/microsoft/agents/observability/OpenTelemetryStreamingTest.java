// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentContinuation;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.ApprovalRequiredException;
import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.UsageDetails;
import com.microsoft.agents.tools.ToolApprovalRequest;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.MeterBuilder;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerBuilder;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("try")
class OpenTelemetryStreamingTest {
    private InMemorySpanExporter spans;

    private SdkTracerProvider tracerProvider;

    private OpenTelemetrySdk openTelemetry;

    private AgentFrameworkTelemetry telemetry;

    @BeforeEach
    void setUp() {
        spans = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spans))
                .build();
        openTelemetry =
                OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        telemetry = AgentFrameworkTelemetry.builder(openTelemetry)
                .instrumentationVersion("test")
                .providerName("openai")
                .build();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    @Test
    void publisherCreatesNoSpanUntilSubscribedAndClosesExactlyOnce() {
        // Arrange
        ControlledPublisher source = new ControlledPublisher(true, null);
        OpenTelemetryChatClient client = new OpenTelemetryChatClient(new StreamingChatClient(source), telemetry);
        Flow.Publisher<ChatResponseUpdate> publisher = client.completeStreaming(request());

        // Assert before subscription
        assertThat(spans.getFinishedSpanItems()).isEmpty();

        // Act
        RecordingSubscriber subscriber = new RecordingSubscriber(false);
        publisher.subscribe(subscriber);
        subscriber.await();

        // Assert
        assertThat(spans.getFinishedSpanItems()).hasSize(1);
        assertThat(attribute(spans.getFinishedSpanItems().getFirst(), GenAiAttributes.OUTCOME))
                .isEqualTo("completed");
        assertThat(subscriber.terminalSignals).hasValue(1);
    }

    @Test
    void subscriptionCancellationClosesSpanOnceWithoutErrorStatus() {
        // Arrange
        ControlledPublisher source = new ControlledPublisher(false, null);
        OpenTelemetryChatClient client = new OpenTelemetryChatClient(new StreamingChatClient(source), telemetry);
        RecordingSubscriber subscriber = new RecordingSubscriber(false);
        client.completeStreaming(request()).subscribe(subscriber);

        // Act
        subscriber.subscription.get().cancel();
        subscriber.subscription.get().cancel();

        // Assert
        assertThat(spans.getFinishedSpanItems()).hasSize(1);
        SpanData span = spans.getFinishedSpanItems().getFirst();
        assertThat(attribute(span, GenAiAttributes.OUTCOME)).isEqualTo("cancelled");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(source.cancelled).isTrue();
    }

    @Test
    void externalCancellationClosesSpanWhenSourceNeverTerminates() {
        // Arrange
        ControlledPublisher source = new ControlledPublisher(false, null);
        OpenTelemetryChatClient client = new OpenTelemetryChatClient(new StreamingChatClient(source), telemetry);
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        RecordingSubscriber subscriber = new RecordingSubscriber(false);
        client.completeStreaming(request(), cancellation).subscribe(subscriber);

        // Act
        cancellation.cancel();

        // Assert
        assertThat(spans.getFinishedSpanItems()).hasSize(1);
        assertThat(attribute(spans.getFinishedSpanItems().getFirst(), GenAiAttributes.OUTCOME))
                .isEqualTo("cancelled");
    }

    @Test
    void subscriberFailureCancelsSourceAndRecordsErrorOnce() {
        // Arrange
        ControlledPublisher source = new ControlledPublisher(true, null);
        OpenTelemetryChatClient client = new OpenTelemetryChatClient(new StreamingChatClient(source), telemetry);
        RecordingSubscriber subscriber = new RecordingSubscriber(true);

        // Act
        client.completeStreaming(request()).subscribe(subscriber);

        // Assert
        assertThat(spans.getFinishedSpanItems()).hasSize(1);
        SpanData span = spans.getFinishedSpanItems().getFirst();
        assertThat(attribute(span, GenAiAttributes.OUTCOME)).isEqualTo("failed");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(source.cancelled).isTrue();
    }

    @Test
    void asynchronousFlowSignalsSeeOperationContextWithoutCallerLeakage() throws Exception {
        // Arrange
        AsyncPublisher source = new AsyncPublisher();
        OpenTelemetryChatClient client = new OpenTelemetryChatClient(new StreamingChatClient(source), telemetry);
        AtomicReference<String> observedSpan = new AtomicReference<>();
        CountDownLatch terminal = new CountDownLatch(1);

        // Act
        client.completeStreaming(request()).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(ChatResponseUpdate item) {
                observedSpan.set(Span.current().getSpanContext().getSpanId());
            }

            @Override
            public void onError(Throwable throwable) {
                terminal.countDown();
            }

            @Override
            public void onComplete() {
                terminal.countDown();
            }
        });
        assertThat(terminal.await(5, TimeUnit.SECONDS)).isTrue();
        source.close();

        // Assert
        SpanData span = spans.getFinishedSpanItems().getFirst();
        assertThat(observedSpan).hasValue(span.getSpanContext().getSpanId());
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void approvalRequiredAndCancellationAreNonErrorTerminalOutcomes() {
        // Arrange approval
        ToolApprovalRequest request = mock(ToolApprovalRequest.class);
        ApprovalRequiredException approval = new ApprovalRequiredException(
                new AgentContinuation("continuation", null, "run", List.of(request), false, false), List.of());
        OpenTelemetryAgent<Void> approvalAgent = new OpenTelemetryAgent<>(new FailingAgent(approval), telemetry);

        // Act approval
        try {
            approvalAgent.runAsync("approve").toCompletableFuture().join();
        } catch (RuntimeException ignored) {
            // Expected terminal boundary.
        }

        // Assert approval
        SpanData approvalSpan = spans.getFinishedSpanItems().getFirst();
        assertThat(attribute(approvalSpan, GenAiAttributes.OUTCOME)).isEqualTo("input_required");
        assertThat(approvalSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(attribute(approvalSpan, GenAiAttributes.ERROR_TYPE)).isNull();
        spans.reset();

        // Arrange cancellation
        PendingAgent pending = new PendingAgent();
        OpenTelemetryAgent<Void> cancelledAgent = new OpenTelemetryAgent<>(pending, telemetry);
        RunHandle<AgentResponse<Void>> handle = cancelledAgent.startRun("cancel", RunOptions.empty());

        // Act cancellation
        handle.cancel();
        try {
            handle.resultAsync().toCompletableFuture().join();
        } catch (RuntimeException ignored) {
            // Expected cancellation.
        }

        // Assert cancellation
        SpanData cancelledSpan = spans.getFinishedSpanItems().getFirst();
        assertThat(attribute(cancelledSpan, GenAiAttributes.OUTCOME)).isEqualTo("cancelled");
        assertThat(cancelledSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
    }

    @Test
    void lexicalSuppressionDoesNotLeakAfterScopeClose() {
        // Arrange
        ChatClient client =
                new OpenTelemetryChatClient(new StreamingChatClient(new ControlledPublisher(true, null)), telemetry);

        // Act suppressed
        try (TelemetrySuppression.SuppressionScope ignored = TelemetrySuppression.suppress()) {
            client.completeAsync(request()).toCompletableFuture().join();
        }
        assertThat(spans.getFinishedSpanItems()).isEmpty();

        // Act unsuppressed
        client.completeAsync(request()).toCompletableFuture().join();

        // Assert
        assertThat(spans.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void throwingSuccessObserverAndRecursiveFailureHandlerPreserveStageValue() {
        // Arrange
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<AgentFrameworkTelemetry> configured = new AtomicReference<>();
        AgentFrameworkTelemetry guarded = AgentFrameworkTelemetry.builder(openTelemetry)
                .instrumentationVersion("test")
                .providerName("openai")
                .instrumentationFailureHandler(failure -> {
                    failures.incrementAndGet();
                    configured.get().handleInstrumentationFailure(new IllegalStateException("recursive"));
                    throw new IllegalStateException("handler failed");
                })
                .build();
        configured.set(guarded);
        TelemetryOperation operation = operation(guarded);

        // Act
        String value = TelemetryStages.observe(CompletableFuture.completedFuture("value"), operation, ignored -> {
                    throw new IllegalStateException("observer failed");
                })
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(value).isEqualTo("value");
        assertThat(failures).hasValue(1);
        assertThat(attribute(spans.getFinishedSpanItems().getFirst(), GenAiAttributes.OUTCOME))
                .isEqualTo("completed");
    }

    @Test
    void defaultFailureHandlerRecordsTypeOnlyOnCurrentSpan() {
        // Arrange
        TelemetryOperation operation = operation(telemetry);

        // Act
        String value = TelemetryStages.observe(CompletableFuture.completedFuture("value"), operation, ignored -> {
                    throw new IllegalStateException("sensitive instrumentation detail");
                })
                .toCompletableFuture()
                .join();

        // Assert
        SpanData span = spans.getFinishedSpanItems().getFirst();
        assertThat(value).isEqualTo("value");
        assertThat(span.getEvents())
                .filteredOn(event -> "agent_framework.instrumentation.failure".equals(event.getName()))
                .singleElement()
                .satisfies(event -> assertThat(event.getAttributes().get(AttributeKey.stringKey("error.type")))
                        .isEqualTo(IllegalStateException.class.getName()));
        assertThat(span.toString()).doesNotContain("sensitive instrumentation detail");
    }

    @Test
    void stageObservationPreservesOriginalFailureAndCancellationState() {
        // Arrange failure
        RuntimeException original = new RuntimeException("original");
        CompletableFuture<String> failed = new CompletableFuture<>();
        TelemetryOperation failedOperation = operation(telemetry);

        // Act failure
        CompletionStage<String> observedFailure = TelemetryStages.observe(failed, failedOperation, ignored -> {});
        failed.completeExceptionally(original);

        // Assert failure
        assertThat(observedFailure).isSameAs(failed);
        assertThat(observedFailure
                        .handle((value, failure) -> failure)
                        .toCompletableFuture()
                        .join())
                .isSameAs(original);
        assertThat(attribute(spans.getFinishedSpanItems().getFirst(), GenAiAttributes.OUTCOME))
                .isEqualTo("failed");
        spans.reset();

        // Arrange cancellation
        CompletableFuture<String> cancelled = new CompletableFuture<>();
        TelemetryOperation cancelledOperation = operation(telemetry);

        // Act cancellation
        CompletionStage<String> observedCancellation =
                TelemetryStages.observe(cancelled, cancelledOperation, ignored -> {});
        cancelled.cancel(false);

        // Assert cancellation
        assertThat(observedCancellation).isSameAs(cancelled);
        assertThat(observedCancellation.toCompletableFuture().isCancelled()).isTrue();
        assertThat(attribute(spans.getFinishedSpanItems().getFirst(), GenAiAttributes.OUTCOME))
                .isEqualTo("cancelled");
    }

    @Test
    void spanRecordingAndFailureHandlerExceptionsDoNotChangeSuccessfulResponse() {
        // Arrange
        RuntimeException recordingFailure = new RuntimeException("recording failed");
        Span throwingSpan = mock(Span.class);
        doThrow(recordingFailure)
                .when(throwingSpan)
                .setAttribute(anyString(), org.mockito.ArgumentMatchers.<String>any());
        doThrow(recordingFailure).when(throwingSpan).end();
        SpanBuilder spanBuilder = mock(SpanBuilder.class);
        when(spanBuilder.setSpanKind(org.mockito.ArgumentMatchers.any())).thenReturn(spanBuilder);
        when(spanBuilder.setParent(org.mockito.ArgumentMatchers.any())).thenReturn(spanBuilder);
        when(spanBuilder.setAllAttributes(org.mockito.ArgumentMatchers.any())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(throwingSpan);
        Tracer tracer = mock(Tracer.class);
        when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        TracerBuilder tracerBuilder = mock(TracerBuilder.class);
        when(tracerBuilder.setInstrumentationVersion(anyString())).thenReturn(tracerBuilder);
        when(tracerBuilder.build()).thenReturn(tracer);
        MeterBuilder meterBuilder = mock(MeterBuilder.class);
        when(meterBuilder.setInstrumentationVersion(anyString())).thenReturn(meterBuilder);
        when(meterBuilder.build()).thenReturn(MeterProvider.noop().get("test"));
        OpenTelemetry throwingOpenTelemetry = mock(OpenTelemetry.class);
        when(throwingOpenTelemetry.tracerBuilder(anyString())).thenReturn(tracerBuilder);
        when(throwingOpenTelemetry.meterBuilder(anyString())).thenReturn(meterBuilder);
        AtomicInteger failures = new AtomicInteger();
        AgentFrameworkTelemetry guarded = AgentFrameworkTelemetry.builder(throwingOpenTelemetry)
                .instrumentationVersion("test")
                .providerName("openai")
                .instrumentationFailureHandler(failure -> {
                    failures.incrementAndGet();
                    throw new IllegalStateException("handler failed");
                })
                .build();
        ChatResponse response = ChatResponse.builder()
                .messages(List.of(Message.text(Role.ASSISTANT, "ok")))
                .responseId("response")
                .build();
        ChatClient delegate = new ChatClient() {
            @Override
            public CompletionStage<ChatResponse> completeAsync(
                    ChatClientRequest request, RunCancellation cancellation) {
                return CompletableFuture.completedFuture(response);
            }

            @Override
            public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                    ChatClientRequest request, RunCancellation cancellation) {
                throw new UnsupportedOperationException();
            }
        };
        OpenTelemetryChatClient client = new OpenTelemetryChatClient(delegate, guarded);

        // Act
        ChatResponse observed =
                client.completeAsync(request()).toCompletableFuture().join();

        // Assert
        assertThat(observed).isSameAs(response);
        assertThat(failures.get()).isGreaterThan(0);
    }

    @Test
    void throwingItemAndTerminalObserversPreserveValuesAndOriginalError() {
        // Arrange
        RuntimeException original = new RuntimeException("delegate failure");
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger failures = new AtomicInteger();
        AgentFrameworkTelemetry guarded = AgentFrameworkTelemetry.builder(openTelemetry)
                .instrumentationVersion("test")
                .providerName("openai")
                .instrumentationFailureHandler(failure -> {
                    failures.incrementAndGet();
                    throw new IllegalStateException("handler failed");
                })
                .build();
        TelemetryPublisher<String> publisher = new TelemetryPublisher<>(
                () -> operation(guarded),
                () -> new FixedPublisher<>(List.of("one", "two"), original, cancelled),
                item -> {
                    throw new IllegalStateException("item observer failed");
                },
                () -> {
                    throw new IllegalStateException("terminal observer failed");
                },
                new DefaultRunCancellation());
        SignalSubscriber<String> subscriber = new SignalSubscriber<>();

        // Act
        publisher.subscribe(subscriber);
        subscriber.await();

        // Assert
        assertThat(subscriber.values).containsExactly("one", "two");
        assertThat(subscriber.error).hasValue(original);
        assertThat(subscriber.onSubscribeCount).hasValue(1);
        assertThat(subscriber.terminalSignals).hasValue(1);
        assertThat(cancelled).isFalse();
        assertThat(failures).hasValue(3);
        assertThat(attribute(spans.getFinishedSpanItems().getFirst(), GenAiAttributes.OUTCOME))
                .isEqualTo("failed");
    }

    @Test
    void sourceSubscribeAfterOnSubscribeThrowSignalsOnceWithoutSecondSubscription() {
        // Arrange
        RuntimeException original = new RuntimeException("subscribe failed");
        TelemetryPublisher<String> publisher = new TelemetryPublisher<>(
                () -> operation(telemetry),
                () -> subscriber -> {
                    subscriber.onSubscribe(new EmptyTestSubscription());
                    throw original;
                },
                item -> {},
                new DefaultRunCancellation());
        SignalSubscriber<String> subscriber = new SignalSubscriber<>();

        // Act
        publisher.subscribe(subscriber);
        subscriber.await();

        // Assert
        assertThat(subscriber.onSubscribeCount).hasValue(1);
        assertThat(subscriber.terminalSignals).hasValue(1);
        assertThat(subscriber.error).hasValue(original);
        assertThat(spans.getFinishedSpanItems()).hasSize(1);
        assertThat(attribute(spans.getFinishedSpanItems().getFirst(), GenAiAttributes.OUTCOME))
                .isEqualTo("failed");
    }

    @Test
    void throwingTerminalObserverDoesNotReplaceCancellation() {
        // Arrange
        AtomicBoolean upstreamCancelled = new AtomicBoolean();
        AtomicInteger failures = new AtomicInteger();
        AgentFrameworkTelemetry guarded = AgentFrameworkTelemetry.builder(openTelemetry)
                .instrumentationVersion("test")
                .providerName("openai")
                .instrumentationFailureHandler(failure -> {
                    failures.incrementAndGet();
                    throw new IllegalStateException("handler failed");
                })
                .build();
        TelemetryPublisher<String> publisher = new TelemetryPublisher<>(
                () -> operation(guarded),
                () -> subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long count) {}

                    @Override
                    public void cancel() {
                        upstreamCancelled.set(true);
                    }
                }),
                item -> {},
                () -> {
                    throw new IllegalStateException("terminal observer failed");
                },
                new DefaultRunCancellation());
        SignalSubscriber<String> subscriber = new SignalSubscriber<>();
        publisher.subscribe(subscriber);

        // Act
        subscriber.subscription.get().cancel();

        // Assert
        assertThat(upstreamCancelled).isTrue();
        assertThat(failures).hasValue(1);
        assertThat(subscriber.terminalSignals).hasValue(0);
        assertThat(attribute(spans.getFinishedSpanItems().getFirst(), GenAiAttributes.OUTCOME))
                .isEqualTo("cancelled");
    }

    @Test
    void streamingCaptureSetsExactOutputOnceAtTerminal() {
        // Arrange
        AgentFrameworkTelemetry capturedTelemetry = AgentFrameworkTelemetry.builder(openTelemetry)
                .instrumentationVersion("test")
                .providerName("openai")
                .identifierPolicy(IdentifierPolicy.PLAIN)
                .contentPolicy(TelemetryContentPolicy.builder()
                        .captureContent(true)
                        .maxStreamingCaptureCharacters(2_048)
                        .build())
                .build();
        ChatResponseUpdate first = update("one", 0);
        ChatResponseUpdate second = ChatResponseUpdate.builder()
                .sequence(1)
                .contents(List.of(
                        new com.microsoft.agents.core.TextContent("two"),
                        new com.microsoft.agents.core.UsageContent(
                                UsageDetails.builder().outputTokens(3).build())))
                .role(Role.ASSISTANT)
                .responseId("response-1")
                .messageId("message-1")
                .model("gpt-5.4")
                .usage(UsageDetails.builder().outputTokens(2).build())
                .build();
        AtomicBoolean cancelled = new AtomicBoolean();
        OpenTelemetryChatClient client = new OpenTelemetryChatClient(
                new StreamingChatClient(new FixedPublisher<>(List.of(first, second), null, cancelled)),
                capturedTelemetry);
        SignalSubscriber<ChatResponseUpdate> subscriber = new SignalSubscriber<>();

        // Act
        client.completeStreaming(request()).subscribe(subscriber);
        subscriber.await();

        // Assert
        List<Message> expectedMessages = List.of(message(first), message(second));
        String expected = new TelemetrySanitizer(capturedTelemetry).messages(expectedMessages);
        SpanData span = spans.getFinishedSpanItems().getFirst();
        assertThat(attribute(span, GenAiAttributes.OUTPUT_MESSAGES)).isEqualTo(expected);
        assertThat(span.getAttributes().get(AttributeKey.booleanKey(GenAiAttributes.OUTPUT_MESSAGES_TRUNCATED)))
                .isNull();
        assertThat(attribute(span, GenAiAttributes.RESPONSE_ID)).isEqualTo("response-1");
        assertThat(longAttribute(span, GenAiAttributes.USAGE_OUTPUT_TOKENS)).isEqualTo(6);
        assertThat(cancelled).isFalse();
    }

    @Test
    void longStreamingCaptureRemainsBoundedWithoutDroppingUpdates() {
        // Arrange
        int updates = 2_000;
        AgentFrameworkTelemetry capturedTelemetry = AgentFrameworkTelemetry.builder(openTelemetry)
                .instrumentationVersion("test")
                .providerName("openai")
                .identifierPolicy(IdentifierPolicy.PLAIN)
                .contentPolicy(TelemetryContentPolicy.builder()
                        .captureContent(true)
                        .maxStreamingCaptureCharacters(128)
                        .build())
                .build();
        AtomicBoolean cancelled = new AtomicBoolean();
        OpenTelemetryChatClient client = new OpenTelemetryChatClient(
                new StreamingChatClient(new ManyUpdatesPublisher(updates, cancelled)), capturedTelemetry);
        SignalSubscriber<ChatResponseUpdate> subscriber = new SignalSubscriber<>();

        // Act
        client.completeStreaming(request()).subscribe(subscriber);
        subscriber.await();

        // Assert
        SpanData span = spans.getFinishedSpanItems().getFirst();
        assertThat(subscriber.values).hasSize(updates);
        assertThat(attribute(span, GenAiAttributes.OUTPUT_MESSAGES)).hasSizeLessThanOrEqualTo(128);
        assertThat(span.getAttributes().get(AttributeKey.booleanKey(GenAiAttributes.OUTPUT_MESSAGES_TRUNCATED)))
                .isTrue();
        assertThat(attribute(span, GenAiAttributes.RESPONSE_ID)).isEqualTo("response-" + (updates - 1));
        assertThat(longAttribute(span, GenAiAttributes.USAGE_OUTPUT_TOKENS))
                .isEqualTo((long) updates * (updates + 1) / 2);
        assertThat(cancelled).isFalse();
    }

    @Test
    void streamingCaptureStopsTraversingOversizedAtomicUpdateAtBound() {
        // Arrange
        AgentFrameworkTelemetry capturedTelemetry = AgentFrameworkTelemetry.builder(openTelemetry)
                .instrumentationVersion("test")
                .providerName("openai")
                .contentPolicy(TelemetryContentPolicy.builder()
                        .captureContent(true)
                        .maxStreamingCaptureCharacters(64)
                        .build())
                .build();
        AtomicInteger reads = new AtomicInteger();
        List<com.microsoft.agents.core.Content> hugeContents = new AbstractList<>() {
            @Override
            public com.microsoft.agents.core.Content get(int index) {
                reads.incrementAndGet();
                return new com.microsoft.agents.core.TextContent("x".repeat(100));
            }

            @Override
            public int size() {
                return 1_000_000;
            }
        };
        StreamingPayloadCapture capture =
                new StreamingPayloadCapture(capturedTelemetry, new TelemetrySanitizer(capturedTelemetry));

        // Act
        capture.add(Role.ASSISTANT, hugeContents);

        // Assert
        assertThat(capture.truncated()).isTrue();
        assertThat(capture.capturedCharacters()).isLessThanOrEqualTo(64);
        assertThat(reads.get()).isLessThan(10);
    }

    private static ChatClientRequest request() {
        return new ChatClientRequest(
                List.of(Message.text(Role.USER, "hello")),
                ChatOptions.builder().model("gpt-5.4").build());
    }

    private static String attribute(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }

    private static Long longAttribute(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.longKey(key));
    }

    private static ChatResponseUpdate update() {
        return ChatResponseUpdate.builder()
                .contents(List.of(new com.microsoft.agents.core.TextContent("chunk")))
                .role(Role.ASSISTANT)
                .model("gpt-5.4")
                .build();
    }

    private static ChatResponseUpdate update(String text, int sequence) {
        return ChatResponseUpdate.builder()
                .sequence(sequence)
                .contents(List.of(new com.microsoft.agents.core.TextContent(text)))
                .role(Role.ASSISTANT)
                .responseId("response-" + sequence)
                .messageId("message-" + sequence)
                .model("gpt-5.4")
                .usage(UsageDetails.builder().outputTokens(sequence + 1).build())
                .build();
    }

    private static Message message(ChatResponseUpdate update) {
        return new Message(
                update.role(), update.contents(), update.authorName(), update.messageId(), update.metadata());
    }

    private static TelemetryOperation operation(AgentFrameworkTelemetry telemetry) {
        return TelemetryOperation.start(
                telemetry,
                "test",
                SpanKind.INTERNAL,
                "chat",
                "openai",
                TelemetryContext.CHAT_ACTIVE,
                Attributes.empty());
    }

    private static final class StreamingChatClient implements ChatClient {
        private final Flow.Publisher<ChatResponseUpdate> publisher;

        private StreamingChatClient(Flow.Publisher<ChatResponseUpdate> publisher) {
            this.publisher = publisher;
        }

        @Override
        public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
            return CompletableFuture.completedFuture(ChatResponse.builder()
                    .messages(List.of(Message.text(Role.ASSISTANT, "done")))
                    .build());
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                ChatClientRequest request, RunCancellation cancellation) {
            return publisher;
        }
    }

    private static final class ControlledPublisher implements Flow.Publisher<ChatResponseUpdate> {
        private final boolean emit;

        private final Throwable failure;

        private final AtomicBoolean cancelled = new AtomicBoolean();

        private ControlledPublisher(boolean emit, Throwable failure) {
            this.emit = emit;
            this.failure = failure;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ChatResponseUpdate> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicBoolean requested = new AtomicBoolean();

                @Override
                public void request(long count) {
                    if (count <= 0 || !requested.compareAndSet(false, true) || !emit) {
                        return;
                    }
                    subscriber.onNext(update());
                    if (failure == null) {
                        subscriber.onComplete();
                    } else {
                        subscriber.onError(failure);
                    }
                }

                @Override
                public void cancel() {
                    cancelled.set(true);
                }
            });
        }
    }

    private static final class FixedPublisher<T> implements Flow.Publisher<T> {
        private final List<T> values;

        private final Throwable failure;

        private final AtomicBoolean cancelled;

        private FixedPublisher(List<T> values, Throwable failure, AtomicBoolean cancelled) {
            this.values = values;
            this.failure = failure;
            this.cancelled = cancelled;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super T> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicBoolean requested = new AtomicBoolean();

                @Override
                public void request(long count) {
                    if (count <= 0 || !requested.compareAndSet(false, true)) {
                        return;
                    }
                    values.forEach(subscriber::onNext);
                    if (failure == null) {
                        subscriber.onComplete();
                    } else {
                        subscriber.onError(failure);
                    }
                }

                @Override
                public void cancel() {
                    cancelled.set(true);
                }
            });
        }
    }

    private static final class ManyUpdatesPublisher implements Flow.Publisher<ChatResponseUpdate> {
        private final int count;

        private final AtomicBoolean cancelled;

        private ManyUpdatesPublisher(int count, AtomicBoolean cancelled) {
            this.count = count;
            this.cancelled = cancelled;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ChatResponseUpdate> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicBoolean requested = new AtomicBoolean();

                @Override
                public void request(long demand) {
                    if (demand <= 0 || !requested.compareAndSet(false, true)) {
                        return;
                    }
                    for (int index = 0; index < count; index++) {
                        subscriber.onNext(update("chunk-" + index, index));
                    }
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                    cancelled.set(true);
                }
            });
        }
    }

    private static final class EmptyTestSubscription implements Flow.Subscription {
        @Override
        public void request(long count) {}

        @Override
        public void cancel() {}
    }

    private static final class SignalSubscriber<T> implements Flow.Subscriber<T> {
        private final ArrayList<T> values = new ArrayList<>();

        private final AtomicReference<Throwable> error = new AtomicReference<>();

        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

        private final AtomicInteger onSubscribeCount = new AtomicInteger();

        private final AtomicInteger terminalSignals = new AtomicInteger();

        private final CountDownLatch terminal = new CountDownLatch(1);

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            onSubscribeCount.incrementAndGet();
            this.subscription.set(subscription);
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(T item) {
            values.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            error.set(throwable);
            terminalSignals.incrementAndGet();
            terminal.countDown();
        }

        @Override
        public void onComplete() {
            terminalSignals.incrementAndGet();
            terminal.countDown();
        }

        private void await() {
            try {
                assertThat(terminal.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }
    }

    private static final class AsyncPublisher implements Flow.Publisher<ChatResponseUpdate>, AutoCloseable {
        private final java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

        @Override
        public void subscribe(Flow.Subscriber<? super ChatResponseUpdate> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicBoolean requested = new AtomicBoolean();

                @Override
                public void request(long count) {
                    if (count > 0 && requested.compareAndSet(false, true)) {
                        executor.submit(() -> {
                            subscriber.onNext(update());
                            subscriber.onComplete();
                        });
                    }
                }

                @Override
                public void cancel() {}
            });
        }

        @Override
        public void close() {
            executor.close();
        }
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<ChatResponseUpdate> {
        private final boolean failOnNext;

        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

        private final AtomicInteger terminalSignals = new AtomicInteger();

        private final CountDownLatch terminal = new CountDownLatch(1);

        private RecordingSubscriber(boolean failOnNext) {
            this.failOnNext = failOnNext;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription.set(value);
            if (!failOnNext) {
                value.request(1);
            } else {
                value.request(1);
            }
        }

        @Override
        public void onNext(ChatResponseUpdate item) {
            if (failOnNext) {
                throw new IllegalStateException("subscriber failed");
            }
        }

        @Override
        public void onError(Throwable throwable) {
            terminalSignals.incrementAndGet();
            terminal.countDown();
        }

        @Override
        public void onComplete() {
            terminalSignals.incrementAndGet();
            terminal.countDown();
        }

        private void await() {
            try {
                assertThat(terminal.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }
    }

    private static final class FailingAgent implements Agent<Void> {
        private final Throwable failure;

        private FailingAgent(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata("agent", "Agent", null);
        }

        @Override
        public RunHandle<AgentResponse<Void>> startRun(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            RunHandleSource<AgentResponse<Void>> source = new RunHandleSource<>(cancellation);
            source.tryFail(failure);
            return source.handle();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class PendingAgent implements Agent<Void> {
        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata("pending", "Pending", null);
        }

        @Override
        public RunHandle<AgentResponse<Void>> startRun(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            return new RunHandleSource<AgentResponse<Void>>(cancellation).handle();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            throw new UnsupportedOperationException();
        }
    }
}
