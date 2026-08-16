// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StructuredOutputOptions;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import com.microsoft.agents.tools.ToolMode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AnthropicOfficialSdkLoopbackTest {
    @Test
    void officialSdk_shouldMarshalFiniteMessagesAndParseResponse() throws Exception {
        try (Loopback server = new Loopback(exchange -> {
                    assertThat(exchange.getRequestHeaders().getFirst("x-api-key"))
                            .isEqualTo("test-key");
                    assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                            .contains(
                                    "\"model\":\"claude-test\"",
                                    "\"name\":\"lookup\"",
                                    "\"output_config\"",
                                    "\"answer\"");
                    respond(exchange, 200, """
                    {"id":"msg-1","type":"message","role":"assistant","model":"claude-test",
                     "content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn",
                     "stop_sequence":null,"usage":{"input_tokens":2,"output_tokens":1}}
                    """, "application/json");
                });
                AnthropicChatClient client = client(server.uri())) {
            ChatClientRequest request = new ChatClientRequest(
                    List.of(Message.text(Role.USER, "hello")),
                    ChatOptions.builder()
                            .structuredOutput(StructuredOutputOptions.jsonSchema(
                                    "answer",
                                    Map.of(
                                            "type",
                                            StateValue.string("object"),
                                            "properties",
                                            StateValue.object(Map.of(
                                                    "answer",
                                                    StateValue.object(Map.of("type", StateValue.string("integer"))))))))
                            .build(),
                    List.of(tool()),
                    ToolMode.AUTO,
                    null);

            var response = client.completeAsync(request).toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertThat(response.responseId()).isEqualTo("msg-1");
            assertThat(response.text()).isEqualTo("hello");
            assertThat(response.usage().totalTokens()).contains(java.math.BigInteger.valueOf(3));
        }
    }

    @Test
    void officialSdk_shouldParseStreamingSseAndEmitOneTerminal() throws Exception {
        try (Loopback server = new Loopback(exchange -> respond(
                        exchange,
                        200,
                        "event: message_start\n"
                                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg-2\","
                                + "\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-test\","
                                + "\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,"
                                + "\"usage\":{\"input_tokens\":2,\"output_tokens\":0}}}\n\n"
                                + "event: content_block_start\n"
                                + "data: {\"type\":\"content_block_start\",\"index\":0,"
                                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                                + "event: content_block_delta\n"
                                + "data: {\"type\":\"content_block_delta\",\"index\":0,"
                                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}\n\n"
                                + "event: content_block_stop\n"
                                + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                                + "event: message_delta\n"
                                + "data: {\"type\":\"message_delta\","
                                + "\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},"
                                + "\"usage\":{\"output_tokens\":1}}\n\n"
                                + "event: message_stop\n"
                                + "data: {\"type\":\"message_stop\"}\n\n",
                        "text/event-stream"));
                AnthropicChatClient client = client(server.uri())) {
            List<ChatResponseUpdate> updates = collect(client.completeStreaming(
                    new ChatClientRequest(List.of(Message.text(Role.USER, "hello")), ChatOptions.empty())));

            assertThat(updates.stream().map(ChatResponseUpdate::text).reduce("", String::concat))
                    .isEqualTo("hello");
            assertThat(updates)
                    .filteredOn(update -> update.finishReason() != null)
                    .hasSize(1);
            assertThat(updates.getLast().usage().totalTokens()).contains(java.math.BigInteger.valueOf(3));
        }
    }

    @Test
    void transportOwnershipAndRedaction_shouldBeExplicit() {
        AtomicBoolean closed = new AtomicBoolean();
        AnthropicTransport transport = new AnthropicTransport() {
            @Override
            public java.util.concurrent.CompletionStage<com.microsoft.agents.core.ChatResponse> completeAsync(
                    ChatClientRequest request,
                    AnthropicChatClientOptions options,
                    com.microsoft.agents.core.RunCancellation cancellation) {
                return CompletableFuture.failedFuture(
                        new AnthropicProviderException("test", 401, "request", "unauthorized"));
            }

            @Override
            public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                    ChatClientRequest request,
                    AnthropicChatClientOptions options,
                    com.microsoft.agents.core.RunCancellation cancellation) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        AnthropicChatClientOptions options = AnthropicChatClientOptions.builder()
                .model("test")
                .apiKey("super-secret")
                .build();
        try (AnthropicChatClient client = AnthropicChatClient.builder()
                .options(options)
                .transport(transport)
                .build()) {
            assertThatThrownBy(() -> client.completeAsync(new ChatClientRequest(
                                    List.of(Message.text(Role.USER, "hello")), ChatOptions.empty()))
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(AnthropicProviderException.class)
                    .hasMessageNotContaining("super-secret");
        }
        assertThat(closed).isFalse();
        assertThat(options.toString()).doesNotContain("super-secret");
    }

    private static AnthropicChatClient client(URI endpoint) {
        return AnthropicChatClient.builder()
                .options(AnthropicChatClientOptions.builder()
                        .model("claude-test")
                        .apiKey("test-key")
                        .endpoint(endpoint)
                        .allowedHosts(Set.of("127.0.0.1"))
                        .allowInsecureLoopback(true)
                        .timeout(Duration.ofSeconds(5))
                        .build())
                .build();
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

    private static List<ChatResponseUpdate> collect(Flow.Publisher<ChatResponseUpdate> publisher) throws Exception {
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
        return result.get(5, TimeUnit.SECONDS);
    }

    private static void respond(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("request-id", "request-1");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static final class Loopback implements AutoCloseable {
        private final HttpServer server;

        private Loopback(Handler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/messages", handler::handle);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.start();
        }

        private URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        }

        @Override
        public void close() {
            server.stop(0);
            if (server.getExecutor() instanceof java.util.concurrent.ExecutorService service) {
                service.close();
            }
        }
    }
}
