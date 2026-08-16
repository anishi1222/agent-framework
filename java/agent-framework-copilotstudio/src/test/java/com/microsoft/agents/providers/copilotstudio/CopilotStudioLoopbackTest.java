// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StructuredOutputOptions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CopilotStudioLoopbackTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    @Test
    void exactDirectToEngineWire_shouldStartSendDeduplicatePollMapCardsAndRefreshTokens() throws Exception {
        AtomicInteger tokenCalls = new AtomicInteger();
        try (FakeService service = new FakeService()) {
            CopilotStudioClientOptions options = service.options(CopilotStudioLimits.defaults(), Duration.ofMillis(5));
            try (CopilotStudioClient client = CopilotStudioClient.builder()
                    .options(options)
                    .tokenProvider(cancellation -> {
                        int call = tokenCalls.incrementAndGet();
                        return java.util.concurrent.CompletableFuture.completedStage(new CopilotStudioAccessToken(
                                "token-" + call, Instant.now().plusMillis(call == 1 ? 80 : 60_000)));
                    })
                    .build()) {
                DefaultRunCancellation cancellation = new DefaultRunCancellation();
                CopilotStudioConversation conversation = client.startConversationAsync(cancellation)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);

                assertThat(conversation.conversationId()).isEqualTo("conversation-1");
                assertThat(conversation.cursor().lastEventId()).isEqualTo("1");
                Thread.sleep(100);

                CopilotStudioActivity activity =
                        CopilotStudioActivity.message("activity-1", conversation.conversationId(), "oauth please");
                List<CopilotStudioEvent> events = client.sendActivityAsync(
                                conversation.conversationId(), activity, conversation.cursor(), cancellation)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);

                assertThat(events)
                        .extracting(event -> event.cursor().lastEventId())
                        .containsExactly("2", "3", "4");
                assertThat(events)
                        .extracting(CopilotStudioEvent::type)
                        .containsExactly(
                                CopilotStudioEventType.TYPING,
                                CopilotStudioEventType.TYPING,
                                CopilotStudioEventType.OAUTH_REQUIRED);
                assertThat(events.getLast().activity().attachments())
                        .singleElement()
                        .satisfies(attachment -> {
                            assertThat(attachment.contentType()).contains("oauth");
                            assertThat(attachment.content()).isNotNull();
                        });
                assertThat(service.authorizationHeaders).containsExactly("Bearer token-1", "Bearer token-2");
                assertThat(service.lastActivity.path("activity").path("id").asText())
                        .isEqualTo("activity-1");
                assertThat(service.lastActivity.path("activity").path("text").asText())
                        .isEqualTo("oauth please");

                List<CopilotStudioEvent> polled = client.pollActivitiesAsync(
                                conversation.conversationId(), events.getLast().cursor(), cancellation)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                assertThat(service.lastEventIds).contains("4");
                assertThat(polled)
                        .singleElement()
                        .extracting(event -> event.cursor().lastEventId())
                        .isEqualTo("5");
            }
        }
        assertThat(tokenCalls.get()).isEqualTo(2);
    }

    @Test
    void chatAndAgent_shouldSendOnlyNewestUserActivityAndReserveStableIds() throws Exception {
        try (FakeService service = new FakeService();
                CopilotStudioClient client = service.client()) {
            CopilotStudioChatClient chat = new CopilotStudioChatClient(client);
            assertThatThrownBy(() -> chat.completeAsync(new ChatClientRequest(
                                    List.of(Message.text(Role.USER, "structured")),
                                    ChatOptions.builder()
                                            .structuredOutput(StructuredOutputOptions.jsonSchema(
                                                    "answer", Map.of("type", StateValue.string("object"))))
                                            .build()))
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage("Copilot Studio does not support ChatOptions.structuredOutput.");
            var response = chat.completeAsync(new ChatClientRequest(
                            List.of(
                                    Message.text(Role.USER, "old"),
                                    Message.text(Role.ASSISTANT, "prior"),
                                    Message.builder(Role.USER)
                                            .messageId("stable-1")
                                            .contents(List.of(new com.microsoft.agents.core.TextContent("new")))
                                            .build()),
                            ChatOptions.empty()))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertThat(response.text()).isEqualTo("final:new");
            assertThat(service.lastActivity.path("activity").path("text").asText())
                    .isEqualTo("new");

            CopilotStudioAgent agent = new CopilotStudioAgent(client, new AgentMetadata("studio", "Studio", "Test"));
            AgentSession session = new AgentSession("principal-session");
            Message stable = Message.builder(Role.USER)
                    .messageId("stable-agent")
                    .contents(List.of(new com.microsoft.agents.core.TextContent("agent turn")))
                    .build();
            var first = agent.startRun(session, List.of(stable), RunOptions.empty(), new DefaultRunCancellation())
                    .resultAsync()
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertThat(first.text()).isEqualTo("final:agent turn");
            assertThat(session.state().get(CopilotStudioAgent.CONVERSATION_ID_STATE_KEY))
                    .isPresent();
            assertThat(session.state().get(CopilotStudioAgent.RECEIVED_ACTIVITY_IDS_STATE_KEY))
                    .isPresent();
            assertThatThrownBy(() -> agent.startRun(
                                    session, List.of(stable), RunOptions.empty(), new DefaultRunCancellation())
                            .resultAsync()
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(CopilotStudioException.class)
                    .hasRootCauseMessage("Stable input activity ID was already submitted by this session.");
            agent.close();
        }
    }

    @Test
    void redirectAndLineLimit_shouldFailClosedWithoutFollowing() throws Exception {
        try (FakeService service = new FakeService()) {
            service.redirect = true;
            try (CopilotStudioClient client = service.client()) {
                assertThatThrownBy(() -> client.startConversationAsync()
                                .toCompletableFuture()
                                .get(5, TimeUnit.SECONDS))
                        .hasRootCauseInstanceOf(CopilotStudioException.class);
                assertThat(service.redirectTargetCalls.get()).isZero();
            }
        }

        CopilotStudioLimits tiny = new CopilotStudioLimits(1024, 1024, 128, 64, 16, 64, 100, 8, 16, 2);
        try (FakeService service = new FakeService()) {
            service.oversized = true;
            try (CopilotStudioClient client = service.client(tiny)) {
                assertThatThrownBy(() -> client.startConversationAsync()
                                .toCompletableFuture()
                                .get(5, TimeUnit.SECONDS))
                        .hasRootCauseInstanceOf(CopilotStudioException.class)
                        .hasRootCauseMessage("Copilot Studio SSE line exceeds the configured limit.");
            }
        }
    }

    @Test
    void adaptiveInputAndSubscriptionCancellation_shouldRemainExplicitAndAbortTheStream() throws Exception {
        try (FakeService service = new FakeService();
                CopilotStudioClient client = service.client()) {
            CopilotStudioConversation conversation =
                    client.startConversationAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
            List<CopilotStudioEvent> events = client.sendActivityAsync(
                            conversation.conversationId(),
                            CopilotStudioActivity.message(
                                    "input-activity", conversation.conversationId(), "input please"),
                            conversation.cursor(),
                            new DefaultRunCancellation())
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertThat(events.getLast().type()).isEqualTo(CopilotStudioEventType.INPUT_REQUIRED);
            assertThat(events.getLast().activity().attachments())
                    .singleElement()
                    .extracting(CopilotStudioAttachment::adaptiveCard)
                    .satisfies(card -> {
                        assertThat(card).isNotNull();
                        assertThat(card.actions())
                                .singleElement()
                                .extracting(CopilotStudioCardAction::type)
                                .isEqualTo("Action.Submit");
                    });

            service.holdSubscribe = true;
            DefaultRunCancellation cancellation = new DefaultRunCancellation();
            CountDownLatch received = new CountDownLatch(1);
            client.subscribeStreaming(conversation.conversationId(), conversation.cursor(), cancellation)
                    .subscribe(new Flow.Subscriber<>() {
                        private Flow.Subscription subscription;

                        @Override
                        public void onSubscribe(Flow.Subscription subscription) {
                            this.subscription = subscription;
                            subscription.request(1);
                        }

                        @Override
                        public void onNext(CopilotStudioEvent item) {
                            subscription.cancel();
                            received.countDown();
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            received.countDown();
                        }

                        @Override
                        public void onComplete() {
                            received.countDown();
                        }
                    });
            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static final class FakeService implements AutoCloseable {
        private final HttpServer server;

        private final CopyOnWriteArrayList<String> authorizationHeaders = new CopyOnWriteArrayList<>();

        private final CopyOnWriteArrayList<String> lastEventIds = new CopyOnWriteArrayList<>();

        private volatile JsonNode lastActivity;

        private volatile boolean redirect;

        private volatile boolean oversized;

        private volatile boolean holdSubscribe;

        private final AtomicInteger redirectTargetCalls = new AtomicInteger();

        private FakeService() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.start();
        }

        private CopilotStudioClient client() {
            return client(CopilotStudioLimits.defaults());
        }

        private CopilotStudioClient client(CopilotStudioLimits limits) {
            return CopilotStudioClient.builder()
                    .options(options(limits, Duration.ofMinutes(1)))
                    .tokenProvider(cancellation -> java.util.concurrent.CompletableFuture.completedStage(
                            new CopilotStudioAccessToken("token", Instant.now().plusSeconds(3600))))
                    .build();
        }

        private CopilotStudioClientOptions options(CopilotStudioLimits limits, Duration refreshSkew) {
            URI endpoint = URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort()
                    + "/copilotstudio/dataverse-backed/authenticated/bots/test-bot");
            return CopilotStudioClientOptions.builder()
                    .tenantId(TENANT)
                    .endpoint(endpoint)
                    .allowInsecureLoopback(true)
                    .tokenRefreshSkew(refreshSkew)
                    .requestTimeout(Duration.ofSeconds(3))
                    .reconnectTimeout(Duration.ofSeconds(3))
                    .limits(limits)
                    .build();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/redirect-target")) {
                redirectTargetCalls.incrementAndGet();
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String lastEventId = exchange.getRequestHeaders().getFirst("Last-Event-ID");
            if (lastEventId != null) {
                lastEventIds.add(lastEventId);
            }
            if (redirect) {
                exchange.getResponseHeaders().add("Location", "/redirect-target");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            if (oversized) {
                sendSse(exchange, "data: " + "x".repeat(256) + "\n\n");
                return;
            }
            if (path.endsWith("/subscribe")) {
                if (holdSubscribe) {
                    byte[] first = activityEvent("2", "held-1", "message", "held", "conversation-1", null)
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(first);
                    exchange.getResponseBody().flush();
                    try {
                        Thread.sleep(2_000);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                    return;
                }
                sendSse(exchange, activityEvent("5", "poll-1", "message", "polled", "conversation-1", null));
                return;
            }
            if (path.endsWith("/conversations")) {
                exchange.getResponseHeaders().add(CopilotStudioProtocol.CONVERSATION_ID_HEADER, "conversation-1");
                sendSse(exchange, activityEvent("1", "start-1", "message", "welcome", "conversation-1", null));
                return;
            }
            lastActivity = JSON.readTree(exchange.getRequestBody());
            String text = lastActivity.path("activity").path("text").asText();
            StringBuilder stream = new StringBuilder();
            stream.append(activityEvent("2", "typing-1", "typing", "fin", "conversation-1", null));
            stream.append(activityEvent("2", "typing-duplicate", "typing", "duplicate", "conversation-1", null));
            stream.append(activityEvent("1", "typing-old", "typing", "old", "conversation-1", null));
            stream.append(activityEvent("3", "typing-2", "typing", "al:", "conversation-1", null));
            String attachment;
            if (text.contains("oauth")) {
                attachment = "[{\"contentType\":\"application/vnd.microsoft.card.oauth\","
                        + "\"content\":{\"connectionName\":\"x\"}}]";
            } else if (text.contains("input")) {
                attachment = "[{\"contentType\":\"application/vnd.microsoft.card.adaptive\","
                        + "\"content\":{\"type\":\"AdaptiveCard\",\"version\":\"1.5\","
                        + "\"body\":[{\"type\":\"Input.Text\",\"id\":\"answer\"}],"
                        + "\"actions\":[{\"type\":\"Action.Submit\",\"title\":\"Continue\","
                        + "\"data\":{\"action\":\"continue\"}}]}}]";
            } else {
                attachment = "[]";
            }
            stream.append(activityEvent("4", "message-1", "message", "final:" + text, "conversation-1", attachment));
            sendSse(exchange, stream.toString());
        }

        private static String activityEvent(
                String eventId,
                String activityId,
                String type,
                String text,
                String conversationId,
                String attachments) {
            String attachmentJson = attachments == null ? "[]" : attachments;
            return "id: "
                    + eventId
                    + "\nevent: activity\ndata: {\"id\":\""
                    + activityId
                    + "\",\"type\":\""
                    + type
                    + "\",\"text\":\""
                    + text
                    + "\",\"timestamp\":\"2026-08-12T00:00:00Z\","
                    + "\"from\":{\"id\":\"bot\",\"name\":\"Bot\"},"
                    + "\"conversation\":{\"id\":\""
                    + conversationId
                    + "\"},\"attachments\":"
                    + attachmentJson
                    + "}\n\n";
        }

        private static void sendSse(HttpExchange exchange, String content) throws IOException {
            byte[] body = content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
