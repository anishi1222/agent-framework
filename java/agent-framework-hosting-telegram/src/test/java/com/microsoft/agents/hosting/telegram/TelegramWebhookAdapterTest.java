// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TelegramWebhookAdapterTest {
    private static final String SECRET = "telegram_secret-token";

    @Test
    void handleAsync_shouldRejectMissingWrongAndAmbiguousSecretTokens() {
        CapturingAgent agent = CapturingAgent.finite("unused");
        try (Fixture fixture = fixture(agent, new RecordingClient(), false)) {
            TelegramWebhookResponse missing =
                    await(fixture.adapter.handleAsync(request(messageUpdate(1, 10, 100, 200, "hello"), Map.of())));
            TelegramWebhookResponse wrong = await(fixture.adapter.handleAsync(request(
                    messageUpdate(1, 10, 100, 200, "hello"),
                    Map.of(TelegramWebhookAdapter.SECRET_TOKEN_HEADER, List.of("wrong")))));
            TelegramWebhookResponse ambiguous = await(fixture.adapter.handleAsync(request(
                    messageUpdate(1, 10, 100, 200, "hello"),
                    Map.of(TelegramWebhookAdapter.SECRET_TOKEN_HEADER, List.of(SECRET, SECRET)))));

            assertThat(List.of(missing, wrong, ambiguous)).allSatisfy(response -> {
                assertThat(response.statusCode()).isEqualTo(401);
                assertThat(response.errorCode()).isEqualTo(TelegramWebhookErrorCode.UNAUTHENTICATED);
            });
            assertThat(agent.finiteRuns.get()).isZero();
        }
    }

    @Test
    void handleAsync_shouldRejectMalformedOversizedAndInvalidSupportedPayloads() {
        CapturingAgent agent = CapturingAgent.finite("unused");
        TelegramWebhookOptions options = options(false).maxUpdateBytes(256).build();
        try (Fixture fixture = fixture(agent, new RecordingClient(), options)) {
            TelegramWebhookResponse malformed =
                    await(fixture.adapter.handleAsync(request("{".getBytes(StandardCharsets.UTF_8))));
            TelegramWebhookResponse oversized = await(fixture.adapter.handleAsync(request(new byte[257])));
            TelegramWebhookResponse duplicate =
                    await(fixture.adapter.handleAsync(request("""
                    {"update_id":1,"update_id":2,"message":{}}
                    """.getBytes(StandardCharsets.UTF_8))));
            TelegramWebhookResponse missingChat =
                    await(fixture.adapter.handleAsync(request("""
                    {"update_id":1,"message":{"message_id":10,"from":{"id":200},"text":"hello"}}
                    """.getBytes(StandardCharsets.UTF_8))));
            TelegramWebhookResponse zeroChat =
                    await(fixture.adapter.handleAsync(request(messageUpdate(1, 10, 0, 200, "hello"))));

            assertThat(malformed.errorCode()).isEqualTo(TelegramWebhookErrorCode.MALFORMED_UPDATE);
            assertThat(malformed.statusCode()).isEqualTo(400);
            assertThat(oversized.errorCode()).isEqualTo(TelegramWebhookErrorCode.PAYLOAD_TOO_LARGE);
            assertThat(oversized.statusCode()).isEqualTo(413);
            assertThat(duplicate.errorCode()).isEqualTo(TelegramWebhookErrorCode.MALFORMED_UPDATE);
            assertThat(missingChat.errorCode()).isEqualTo(TelegramWebhookErrorCode.INVALID_UPDATE);
            assertThat(missingChat.statusCode()).isEqualTo(422);
            assertThat(zeroChat.errorCode()).isEqualTo(TelegramWebhookErrorCode.INVALID_UPDATE);
            assertThat(agent.finiteRuns.get()).isZero();
        }
    }

    @Test
    void handleAsync_shouldAcknowledgeUnsupportedUpdatesWithoutDispatchOrOutboundCalls() {
        CapturingAgent agent = CapturingAgent.finite("unused");
        RecordingClient client = new RecordingClient();
        try (Fixture fixture = fixture(agent, client, false)) {
            TelegramWebhookResponse callback =
                    await(fixture.adapter.handleAsync(request("""
                    {"update_id":7,"callback_query":{"id":"query","data":"hello"}}
                    """.getBytes(StandardCharsets.UTF_8))));
            TelegramWebhookResponse photoOnly =
                    await(fixture.adapter.handleAsync(request("""
                    {"update_id":8,"message":{"message_id":10,
                    "chat":{"id":100,"type":"private"},"from":{"id":200},"photo":[]}}
                    """.getBytes(StandardCharsets.UTF_8))));

            assertThat(callback.disposition()).isEqualTo(TelegramWebhookDisposition.UNSUPPORTED);
            assertThat(callback.statusCode()).isEqualTo(204);
            assertThat(callback.updateId()).isEqualTo(7);
            assertThat(photoOnly.disposition()).isEqualTo(TelegramWebhookDisposition.UNSUPPORTED);
            assertThat(agent.finiteRuns.get()).isZero();
            assertThat(client.requests).isEmpty();
        }
    }

    @Test
    void handleAsync_shouldMapFiniteRunAndPreserveSafePrincipalIsolation() {
        CapturingAgent agent = CapturingAgent.finite("assistant reply");
        RecordingClient client = new RecordingClient();
        try (Fixture fixture = fixture(agent, client, false)) {
            TelegramWebhookResponse first =
                    await(fixture.adapter.handleAsync(request(messageUpdate(11, 21, 100, 200, "hello"))));
            TelegramWebhookResponse second =
                    await(fixture.adapter.handleAsync(request(messageUpdate(12, 22, 100, 201, "hola"))));
            TelegramWebhookResponse third =
                    await(fixture.adapter.handleAsync(request(messageUpdate(13, 23, 101, 200, "bonjour"))));

            assertThat(List.of(first, second, third)).allSatisfy(response -> {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.disposition()).isEqualTo(TelegramWebhookDisposition.PROCESSED);
            });
            assertThat(agent.messages).extracting(Message::text).containsExactly("hello", "hola", "bonjour");
            assertThat(agent.isolationIds)
                    .containsExactly(
                            "telegram:bot:999:chat:100:user:200",
                            "telegram:bot:999:chat:100:user:201",
                            "telegram:bot:999:chat:101:user:200")
                    .doesNotHaveDuplicates();
            assertThat(agent.principalIds)
                    .containsExactly(
                            "telegram:bot:999:user:200", "telegram:bot:999:user:201", "telegram:bot:999:user:200");
            assertThat(client.requests)
                    .containsExactly(
                            new TelegramSendMessageRequest(100, "assistant reply"),
                            new TelegramSendMessageRequest(100, "assistant reply"),
                            new TelegramSendMessageRequest(101, "assistant reply"));
            assertThat(first.outboundMessageId()).isEqualTo(1001);
        }
    }

    @Test
    void handleAsync_shouldAggregateStreamingTextAndTruncateAtConfiguredBoundary() {
        CapturingAgent agent = CapturingAgent.streaming("ab", "cdef", "gh");
        RecordingClient client = new RecordingClient();
        TelegramWebhookOptions options = options(true).maxOutboundTextLength(6).build();
        try (Fixture fixture = fixture(agent, client, options)) {
            TelegramWebhookResponse response =
                    await(fixture.adapter.handleAsync(request(messageUpdate(15, 25, -100, 200, "stream"))));

            assertThat(response.disposition()).isEqualTo(TelegramWebhookDisposition.PROCESSED);
            assertThat(client.requests).containsExactly(new TelegramSendMessageRequest(-100, "abcdef"));
            assertThat(agent.streamingRuns.get()).isEqualTo(1);
        }
    }

    @Test
    void handleAsync_shouldReturnNoResponseFallbackWhenAgentProducesNoText() {
        CapturingAgent agent = CapturingAgent.finite("");
        RecordingClient client = new RecordingClient();
        try (Fixture fixture = fixture(agent, client, false)) {
            TelegramWebhookResponse response =
                    await(fixture.adapter.handleAsync(request(messageUpdate(16, 26, 100, 200, "hello"))));

            assertThat(response.disposition()).isEqualTo(TelegramWebhookDisposition.PROCESSED);
            assertThat(client.requests).containsExactly(new TelegramSendMessageRequest(100, "(no response)"));
        }
    }

    @Test
    void handleAsync_shouldMapAgentAndOutboundFailuresWithoutLeakingExceptionText() {
        CapturingAgent failingAgent = CapturingAgent.failing();
        try (Fixture fixture = fixture(failingAgent, new RecordingClient(), false)) {
            TelegramWebhookResponse dispatchFailure =
                    await(fixture.adapter.handleAsync(request(messageUpdate(17, 27, 100, 200, "hello"))));

            assertThat(dispatchFailure.statusCode()).isEqualTo(500);
            assertThat(dispatchFailure.errorCode()).isEqualTo(TelegramWebhookErrorCode.DISPATCH_FAILED);
        }

        CapturingAgent agent = CapturingAgent.finite("reply");
        RecordingClient failingClient = new RecordingClient();
        failingClient.failure = new TelegramBotException(TelegramBotErrorCode.API_ERROR, 200, 400);
        try (Fixture fixture = fixture(agent, failingClient, false)) {
            TelegramWebhookResponse outboundFailure =
                    await(fixture.adapter.handleAsync(request(messageUpdate(18, 28, 100, 200, "hello"))));

            assertThat(outboundFailure.statusCode()).isEqualTo(502);
            assertThat(outboundFailure.errorCode()).isEqualTo(TelegramWebhookErrorCode.OUTBOUND_FAILED);
        }
    }

    @Test
    void handleAsync_shouldPropagateCallerCancellationToActiveAgentRun() {
        CapturingAgent agent = CapturingAgent.pending();
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        try (Fixture fixture = fixture(agent, new RecordingClient(), false)) {
            CompletionStage<TelegramWebhookResponse> responseStage =
                    fixture.adapter.handleAsync(request(messageUpdate(19, 29, 100, 200, "wait"), cancellation));
            await(agent.started);

            cancellation.cancel();
            TelegramWebhookResponse response = await(responseStage);

            assertThat(response.statusCode()).isEqualTo(499);
            assertThat(response.disposition()).isEqualTo(TelegramWebhookDisposition.CANCELLED);
            assertThat(response.errorCode()).isEqualTo(TelegramWebhookErrorCode.CANCELLED);
            await(agent.cancelled);
        }
    }

    @Test
    void handleAsync_shouldCancelActiveRunWhenProcessingDeadlineExpires() {
        CapturingAgent agent = CapturingAgent.pending();
        TelegramWebhookOptions options =
                options(false).processingTimeout(Duration.ofMillis(75)).build();
        try (Fixture fixture = fixture(agent, new RecordingClient(), options)) {
            TelegramWebhookResponse response =
                    await(fixture.adapter.handleAsync(request(messageUpdate(20, 30, 100, 200, "wait"))));

            assertThat(response.statusCode()).isEqualTo(504);
            assertThat(response.errorCode()).isEqualTo(TelegramWebhookErrorCode.TIMEOUT);
            await(agent.cancelled);
        }
    }

    private static Fixture fixture(CapturingAgent agent, RecordingClient client, boolean streaming) {
        return fixture(agent, client, options(streaming).build());
    }

    private static Fixture fixture(CapturingAgent agent, RecordingClient client, TelegramWebhookOptions options) {
        HostingRegistry registry = new HostingRegistry();
        registry.registerAgent("telegram-agent", agent, true, false, Map.of());
        HostingDispatcher dispatcher = new HostingDispatcher(registry, HostingLimits.defaults());
        TelegramWebhookAdapter adapter = new TelegramWebhookAdapter(dispatcher, client, options);
        return new Fixture(dispatcher, adapter);
    }

    private static TelegramWebhookOptions.Builder options(boolean streaming) {
        return TelegramWebhookOptions.builder()
                .botId(999)
                .routeId("telegram-agent")
                .webhookSecretToken(SECRET)
                .streaming(streaming)
                .processingTimeout(Duration.ofSeconds(2));
    }

    private static TelegramWebhookRequest request(byte[] body) {
        return request(body, new DefaultRunCancellation());
    }

    private static TelegramWebhookRequest request(byte[] body, RunCancellation cancellation) {
        return request(body, Map.of(TelegramWebhookAdapter.SECRET_TOKEN_HEADER, List.of(SECRET)), cancellation);
    }

    private static TelegramWebhookRequest request(byte[] body, Map<String, List<String>> extraHeaders) {
        return request(body, extraHeaders, new DefaultRunCancellation());
    }

    private static TelegramWebhookRequest request(
            byte[] body, Map<String, List<String>> extraHeaders, RunCancellation cancellation) {
        java.util.LinkedHashMap<String, List<String>> headers = new java.util.LinkedHashMap<>();
        headers.put("Content-Type", List.of("application/json; charset=utf-8"));
        headers.putAll(extraHeaders);
        return new TelegramWebhookRequest("POST", headers, body, cancellation);
    }

    private static byte[] messageUpdate(long updateId, long messageId, long chatId, long userId, String text) {
        return ("{\"update_id\":%d,\"message\":{\"message_id\":%d,"
                        + "\"chat\":{\"id\":%d,\"type\":\"private\"},"
                        + "\"from\":{\"id\":%d},\"text\":\"%s\"}}")
                .formatted(updateId, messageId, chatId, userId, text)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
    }

    private static final class Fixture implements AutoCloseable {
        private final HostingDispatcher dispatcher;

        private final TelegramWebhookAdapter adapter;

        private Fixture(HostingDispatcher dispatcher, TelegramWebhookAdapter adapter) {
            this.dispatcher = dispatcher;
            this.adapter = adapter;
        }

        @Override
        public void close() {
            adapter.close();
            dispatcher.close();
        }
    }

    private static final class RecordingClient implements TelegramBotClient {
        private final ArrayList<TelegramSendMessageRequest> requests = new ArrayList<>();

        private RuntimeException failure;

        @Override
        public CompletionStage<TelegramSendMessageResult> sendMessageAsync(
                TelegramSendMessageRequest request, RunCancellation cancellation) {
            requests.add(request);
            if (failure != null) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(new TelegramSendMessageResult(1000L + requests.size()));
        }
    }

    private static final class CapturingAgent implements Agent<Void> {
        private enum Mode {
            FINITE,
            STREAMING,
            FAILING,
            PENDING
        }

        private final Mode mode;

        private final String finiteText;

        private final List<String> streamingText;

        private final AtomicInteger finiteRuns = new AtomicInteger();

        private final AtomicInteger streamingRuns = new AtomicInteger();

        private final ArrayList<Message> messages = new ArrayList<>();

        private final ArrayList<String> principalIds = new ArrayList<>();

        private final ArrayList<String> isolationIds = new ArrayList<>();

        private final CompletableFuture<Void> started = new CompletableFuture<>();

        private final CompletableFuture<Void> cancelled = new CompletableFuture<>();

        private CapturingAgent(Mode mode, String finiteText, List<String> streamingText) {
            this.mode = mode;
            this.finiteText = finiteText;
            this.streamingText = streamingText;
        }

        static CapturingAgent finite(String text) {
            return new CapturingAgent(Mode.FINITE, text, List.of());
        }

        static CapturingAgent streaming(String... text) {
            return new CapturingAgent(Mode.STREAMING, null, List.of(text));
        }

        static CapturingAgent failing() {
            return new CapturingAgent(Mode.FAILING, null, List.of());
        }

        static CapturingAgent pending() {
            return new CapturingAgent(Mode.PENDING, null, List.of());
        }

        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata("telegram-agent", "Telegram test agent", "test");
        }

        @Override
        public RunHandle<AgentResponse<Void>> startRun(
                List<Message> input, RunOptions options, RunCancellation cancellation) {
            finiteRuns.incrementAndGet();
            capture(input, options);
            if (mode == Mode.FAILING) {
                RunHandleSource<AgentResponse<Void>> source = new RunHandleSource<>(cancellation);
                source.tryFail(new IllegalStateException("secret failure details"));
                return source.handle();
            }
            if (mode == Mode.PENDING) {
                started.complete(null);
                cancellation.cancelledAsync().whenComplete((ignored, failure) -> cancelled.complete(null));
                return new RunHandleSource<AgentResponse<Void>>(cancellation).handle();
            }
            AgentResponse<Void> response = AgentResponse.<Void>builder()
                    .messages(finiteText.isEmpty() ? List.of() : List.of(Message.text(Role.ASSISTANT, finiteText)))
                    .build();
            RunHandleSource<AgentResponse<Void>> source = new RunHandleSource<>(cancellation);
            source.tryComplete(response);
            return source.handle();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> input, RunOptions options, RunCancellation cancellation) {
            streamingRuns.incrementAndGet();
            capture(input, options);
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private int index;

                private boolean done;

                @Override
                public void request(long count) {
                    if (done || count <= 0) {
                        return;
                    }
                    while (count-- > 0 && index < streamingText.size()) {
                        String next = streamingText.get(index);
                        long sequence = index;
                        index++;
                        subscriber.onNext(AgentResponseUpdate.builder()
                                .sequence(sequence)
                                .role(Role.ASSISTANT)
                                .contents(List.of(new TextContent(next)))
                                .build());
                    }
                    if (!done && index == streamingText.size()) {
                        done = true;
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    done = true;
                    cancellation.cancel();
                }
            });
        }

        private void capture(List<Message> input, RunOptions options) {
            messages.add(input.getFirst());
            principalIds.add(stringMetadata(options, "hosting.principalId"));
            isolationIds.add(stringMetadata(options, "hosting.isolationId"));
        }

        private static String stringMetadata(RunOptions options, String name) {
            return ((StateValue.StringValue) options.metadata().get(name)).value();
        }
    }
}
