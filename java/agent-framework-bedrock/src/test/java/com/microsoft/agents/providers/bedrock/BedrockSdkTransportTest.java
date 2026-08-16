// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import com.microsoft.agents.tools.ToolMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStart;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlockStart;

class BedrockSdkTransportTest {
    @Test
    void productionPath_shouldUseRealSdkModelsAndWireVisitorToOneExactTerminal() throws Exception {
        FakeSdkClient sdk = new FakeSdkClient();
        sdk.finiteResponse = response("finite");
        sdk.events = toolEvents();
        BedrockChatClientOptions options = options();
        try (BedrockSdkTransport transport = new BedrockSdkTransport(options, sdk, false)) {
            var finite = transport
                    .completeAsync(request(), options, new DefaultRunCancellation())
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            List<ChatResponseUpdate> updates = collect(
                            transport.completeStreaming(request(), options, new DefaultRunCancellation()))
                    .get(5, TimeUnit.SECONDS);

            assertThat(finite.text()).isEqualTo("finite");
            assertThat(sdk.finiteRequest.get().modelId()).isEqualTo("test-model");
            assertThat(sdk.streamRequest.get().toolConfig().tools()).hasSize(1);
            assertThat(updates).hasSize(2);
            assertThat(updates.getFirst().contents())
                    .containsExactly(new FunctionCallContent(
                            "call-1", "lookup", StateValue.object(Map.of("city", StateValue.string("Paris")))));
            assertThat(updates.getLast().finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
            assertThat(updates.getLast().contents()).isEmpty();
            assertThat(updates.stream().filter(update -> update.finishReason() != null))
                    .hasSize(1);
        }
        assertThat(sdk.closed).isFalse();
    }

    @Test
    void mappedAndDeclaredBounds_shouldFailBeforePublishingOrReturning() {
        FakeSdkClient mapped = new FakeSdkClient();
        mapped.finiteResponse = response("x".repeat(512));
        BedrockChatClientOptions mappedOptions = BedrockChatClientOptions.builder()
                .model("test-model")
                .maxResponseBytes(256)
                .build();
        try (BedrockSdkTransport transport = new BedrockSdkTransport(mappedOptions, mapped, false)) {
            assertThatThrownBy(() -> transport
                            .completeAsync(request(), mappedOptions, new DefaultRunCancellation())
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(BedrockProviderException.class)
                    .rootCause()
                    .extracting("kind")
                    .isEqualTo("mapped_response_too_large");
        }

        FakeSdkClient declared = new FakeSdkClient();
        declared.finiteResponse = responseWithDeclaredLength("ok", 4096);
        BedrockChatClientOptions declaredOptions = BedrockChatClientOptions.builder()
                .model("test-model")
                .maxResponseBytes(1024)
                .build();
        try (BedrockSdkTransport transport = new BedrockSdkTransport(declaredOptions, declared, false)) {
            assertThatThrownBy(() -> transport
                            .completeAsync(request(), declaredOptions, new DefaultRunCancellation())
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(BedrockProviderException.class)
                    .rootCause()
                    .extracting("kind")
                    .isEqualTo("declared_response_too_large");
        }

        FakeSdkClient event = new FakeSdkClient();
        event.events = List.of(
                ConverseStreamOutput.messageStartBuilder()
                        .role(ConversationRole.ASSISTANT)
                        .build(),
                ConverseStreamOutput.contentBlockDeltaBuilder()
                        .contentBlockIndex(0)
                        .delta(ContentBlockDelta.fromText("x".repeat(128)))
                        .build(),
                ConverseStreamOutput.contentBlockStopBuilder()
                        .contentBlockIndex(0)
                        .build(),
                ConverseStreamOutput.messageStopBuilder()
                        .stopReason(StopReason.END_TURN)
                        .build());
        BedrockChatClientOptions eventOptions = BedrockChatClientOptions.builder()
                .model("test-model")
                .maxEventBytes(64)
                .maxResponseBytes(1024)
                .build();
        try (BedrockSdkTransport transport = new BedrockSdkTransport(eventOptions, event, false)) {
            assertThatThrownBy(() -> collect(
                                    transport.completeStreaming(request(), eventOptions, new DefaultRunCancellation()))
                            .join())
                    .hasRootCauseInstanceOf(BedrockProviderException.class)
                    .rootCause()
                    .extracting("kind")
                    .isEqualTo("mapped_event_too_large");
        }
    }

    @Test
    void cancellationLateFailureAndOwnership_shouldBeExplicit() {
        FakeSdkClient cancelled = new FakeSdkClient();
        cancelled.autoCompleteEvents = false;
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        BedrockChatClientOptions options = options();
        try (BedrockSdkTransport transport = new BedrockSdkTransport(options, cancelled, false)) {
            CompletableFuture<List<ChatResponseUpdate>> result =
                    collect(transport.completeStreaming(request(), options, cancellation));
            cancellation.cancel();
            assertThatThrownBy(result::join).hasRootCauseInstanceOf(RunCancelledException.class);
            assertThat(cancelled.streamFuture.isCancelled()).isTrue();
        }
        assertThat(cancelled.closed).isFalse();

        FakeSdkClient late = new FakeSdkClient();
        late.events = toolEvents();
        late.completeHandlerBeforeFuture = true;
        late.autoCompleteEvents = false;
        try (BedrockSdkTransport transport = new BedrockSdkTransport(options, late, false)) {
            CompletableFuture<List<ChatResponseUpdate>> result =
                    collect(transport.completeStreaming(request(), options, new DefaultRunCancellation()));
            late.streamFuture.completeExceptionally(new IllegalStateException("late secret"));
            assertThatThrownBy(result::join)
                    .hasRootCauseInstanceOf(BedrockProviderException.class)
                    .hasMessageNotContaining("late secret");
        }

        FakeSdkClient owned = new FakeSdkClient();
        new BedrockSdkTransport(options, owned, true).close();
        assertThat(owned.closed).isTrue();
    }

    private static BedrockChatClientOptions options() {
        return BedrockChatClientOptions.builder().model("test-model").build();
    }

    private static ChatClientRequest request() {
        return new ChatClientRequest(
                List.of(Message.text(Role.USER, "hello")), ChatOptions.empty(), List.of(tool()), ToolMode.AUTO, null);
    }

    private static ToolMetadata tool() {
        return new ToolMetadata(
                "lookup",
                "Looks up a city.",
                Set.of(ToolCapability.FUNCTION),
                ToolApprovalMode.NEVER_REQUIRE,
                StateValue.object(Map.of("type", StateValue.string("object"))),
                StateValue.object(Map.of("type", StateValue.string("object"))));
    }

    private static ConverseResponse response(String text) {
        return ConverseResponse.builder()
                .output(ConverseOutput.fromMessage(
                        software.amazon.awssdk.services.bedrockruntime.model.Message.builder()
                                .role(ConversationRole.ASSISTANT)
                                .content(ContentBlock.fromText(text))
                                .build()))
                .stopReason(StopReason.END_TURN)
                .usage(TokenUsage.builder()
                        .inputTokens(1)
                        .outputTokens(1)
                        .totalTokens(2)
                        .build())
                .build();
    }

    private static ConverseResponse responseWithDeclaredLength(String text, long length) {
        ConverseResponse.Builder builder = response(text).toBuilder();
        builder.sdkHttpResponse(SdkHttpResponse.builder()
                .statusCode(200)
                .putHeader("Content-Length", Long.toString(length))
                .build());
        return builder.build();
    }

    private static List<ConverseStreamOutput> toolEvents() {
        return List.of(
                ConverseStreamOutput.messageStartBuilder()
                        .role(ConversationRole.ASSISTANT)
                        .build(),
                ConverseStreamOutput.contentBlockStartBuilder()
                        .contentBlockIndex(0)
                        .start(ContentBlockStart.fromToolUse(ToolUseBlockStart.builder()
                                .toolUseId("call-1")
                                .name("lookup")
                                .build()))
                        .build(),
                ConverseStreamOutput.contentBlockDeltaBuilder()
                        .contentBlockIndex(0)
                        .delta(ContentBlockDelta.fromToolUse(
                                ToolUseBlockDelta.builder().input("{\"city\":").build()))
                        .build(),
                ConverseStreamOutput.contentBlockDeltaBuilder()
                        .contentBlockIndex(0)
                        .delta(ContentBlockDelta.fromToolUse(
                                ToolUseBlockDelta.builder().input("\"Paris\"}").build()))
                        .build(),
                ConverseStreamOutput.contentBlockStopBuilder()
                        .contentBlockIndex(0)
                        .build(),
                ConverseStreamOutput.messageStopBuilder()
                        .stopReason(StopReason.TOOL_USE)
                        .build(),
                ConverseStreamOutput.metadataBuilder()
                        .usage(TokenUsage.builder()
                                .inputTokens(2)
                                .outputTokens(1)
                                .totalTokens(3)
                                .build())
                        .build());
    }

    private static CompletableFuture<List<ChatResponseUpdate>> collect(Flow.Publisher<ChatResponseUpdate> publisher) {
        ArrayList<ChatResponseUpdate> values = new ArrayList<>();
        CompletableFuture<List<ChatResponseUpdate>> result = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ChatResponseUpdate item) {
                values.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.complete(List.copyOf(values));
            }
        });
        return result;
    }

    private static final class FakeSdkClient implements BedrockSdkTransport.SdkClient {
        private final AtomicReference<ConverseRequest> finiteRequest = new AtomicReference<>();

        private final AtomicReference<ConverseStreamRequest> streamRequest = new AtomicReference<>();

        private ConverseResponse finiteResponse = response("ok");

        private List<ConverseStreamOutput> events = List.of();

        private CompletableFuture<Void> streamFuture = new CompletableFuture<>();

        private boolean autoCompleteEvents = true;

        private boolean completeHandlerBeforeFuture = true;

        private boolean closed;

        @Override
        public CompletableFuture<ConverseResponse> converse(ConverseRequest request) {
            finiteRequest.set(request);
            return CompletableFuture.completedFuture(finiteResponse);
        }

        @Override
        public CompletableFuture<Void> converseStream(
                ConverseStreamRequest request, ConverseStreamResponseHandler handler) {
            streamRequest.set(request);
            handler.responseReceived(ConverseStreamResponse.builder().build());
            if (!events.isEmpty()) {
                handler.onEventStream(subscriber -> subscriber.onSubscribe(new org.reactivestreams.Subscription() {
                    private boolean done;

                    @Override
                    public void request(long count) {
                        if (done) {
                            return;
                        }
                        done = true;
                        events.forEach(subscriber::onNext);
                        subscriber.onComplete();
                    }

                    @Override
                    public void cancel() {
                        done = true;
                    }
                }));
            }
            if (autoCompleteEvents || completeHandlerBeforeFuture && !events.isEmpty()) {
                handler.complete();
            }
            if (autoCompleteEvents) {
                streamFuture.complete(null);
            }
            return streamFuture;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
