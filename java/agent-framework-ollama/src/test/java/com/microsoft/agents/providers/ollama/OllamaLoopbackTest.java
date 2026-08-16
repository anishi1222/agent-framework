// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.ollama;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.ReasoningContent;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StructuredOutputOptions;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OllamaLoopbackTest {
    @Test
    void finite_shouldMapThinkingToolUsageAndFinish() throws Exception {
        try (Loopback server = new Loopback(exchange -> {
                    assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                            .contains(
                                    "\"stream\":false", "\"model\":\"test-model\"", "\"format\":{\"type\":\"object\"}");
                    respond(exchange, 200, """
                    {"model":"test-model","created_at":"2026-08-10T00:00:00Z",
                     "message":{"role":"assistant","content":"answer","thinking":"reason",
                       "tool_calls":[{"id":"call-1","function":{"name":"lookup","arguments":{"x":1}}}]},
                     "done":true,"done_reason":"tool_calls","prompt_eval_count":4,"eval_count":3}
                    """);
                });
                OllamaChatClient client = client(server.uri())) {
            var response = client.completeAsync(new ChatClientRequest(
                            List.of(Message.text(Role.USER, "hello")),
                            ChatOptions.builder()
                                    .structuredOutput(StructuredOutputOptions.jsonSchema(
                                            "answer", Map.of("type", StateValue.string("object"))))
                                    .build()))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertThat(response.finishReason()).isEqualTo(com.microsoft.agents.core.FinishReason.TOOL_CALLS);
            assertThat(response.usage().totalTokens()).contains(java.math.BigInteger.valueOf(7));
            assertThat(response.messages().getFirst().contents())
                    .anyMatch(ReasoningContent.class::isInstance)
                    .anyMatch(FunctionCallContent.class::isInstance);
        }
    }

    @Test
    void streaming_shouldParseRealNdjsonAndUsageOnlyTerminal() throws Exception {
        try (Loopback server = new Loopback(exchange -> respond(
                        exchange,
                        200,
                        "{\"model\":\"test-model\",\"created_at\":\"2026-08-10T00:00:00Z\","
                                + "\"message\":{\"role\":\"assistant\",\"content\":\"hel\"},\"done\":false}\n"
                                + "{\"model\":\"test-model\",\"created_at\":\"2026-08-10T00:00:00Z\","
                                + "\"message\":{\"role\":\"assistant\",\"content\":\"lo\",\"tool_calls\":["
                                + "{\"index\":0,\"id\":\"call-2\",\"function\":{\"name\":\"lookup\","
                                + "\"arguments\":{\"city\":\"Paris\"}}}]},\"done\":false}\n"
                                + "{\"model\":\"test-model\",\"created_at\":\"2026-08-10T00:00:00Z\","
                                + "\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,"
                                + "\"done_reason\":\"tool_calls\",\"prompt_eval_count\":5,\"eval_count\":4}\n"));
                OllamaChatClient client = client(server.uri())) {
            List<ChatResponseUpdate> updates = collect(client.completeStreaming(
                    new ChatClientRequest(List.of(Message.text(Role.USER, "hello")), ChatOptions.empty())));

            assertThat(updates.stream().map(ChatResponseUpdate::text).reduce("", String::concat))
                    .isEqualTo("hello");
            ChatResponseUpdate terminal = updates.getLast();
            assertThat(terminal.usage().totalTokens()).contains(java.math.BigInteger.valueOf(9));
            assertThat(terminal.contents().getFirst())
                    .isEqualTo(new FunctionCallContent(
                            "call-2", "lookup", StateValue.object(Map.of("city", StateValue.string("Paris")))));
        }
    }

    @Test
    void streaming_shouldPreserveRepeatedStringDeltasAndMergeObjectFragments() throws Exception {
        try (Loopback server = new Loopback(exchange -> respond(
                        exchange,
                        200,
                        "{\"model\":\"test-model\",\"message\":{\"role\":\"assistant\",\"tool_calls\":["
                                + "{\"index\":0,\"id\":\"call-r\",\"function\":{\"name\":\"repeat\","
                                + "\"arguments\":\"{\\\"digits\\\":1\"}},"
                                + "{\"index\":1,\"id\":\"call-o\",\"function\":{\"name\":\"object\","
                                + "\"arguments\":{\"left\":\"0\",\"same\":\"value\"}}}]},\"done\":false}\n"
                                + "{\"model\":\"test-model\",\"message\":{\"role\":\"assistant\",\"tool_calls\":["
                                + "{\"index\":0,\"id\":\"call-r\",\"function\":{\"name\":\"repeat\","
                                + "\"arguments\":\"1,\\\"zeros\\\":\\\"0\"}},"
                                + "{\"index\":1,\"id\":\"call-o\",\"function\":{\"name\":\"object\","
                                + "\"arguments\":{\"right\":\"0\",\"same\":\"value\"}}}]},\"done\":false}\n"
                                + "{\"model\":\"test-model\",\"message\":{\"role\":\"assistant\",\"tool_calls\":["
                                + "{\"index\":0,\"id\":\"call-r\",\"function\":{\"name\":\"repeat\","
                                + "\"arguments\":\"0\\\",\\\"equal\\\":\\\"same\"}}]},\"done\":false}\n"
                                + "{\"model\":\"test-model\",\"message\":{\"role\":\"assistant\",\"tool_calls\":["
                                + "{\"index\":0,\"id\":\"call-r\",\"function\":{\"name\":\"repeat\","
                                + "\"arguments\":\"same\\\"}\"}}]},\"done\":false}\n"
                                + "{\"model\":\"test-model\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},"
                                + "\"done\":true,\"done_reason\":\"tool_calls\"}\n"));
                OllamaChatClient client = client(server.uri())) {
            List<ChatResponseUpdate> updates = collect(client.completeStreaming(
                    new ChatClientRequest(List.of(Message.text(Role.USER, "hello")), ChatOptions.empty())));

            assertThat(updates.getLast().contents())
                    .containsExactly(
                            new FunctionCallContent(
                                    "call-r",
                                    "repeat",
                                    StateValue.object(Map.of(
                                            "digits",
                                            StateValue.integer(11),
                                            "zeros",
                                            StateValue.string("00"),
                                            "equal",
                                            StateValue.string("samesame")))),
                            new FunctionCallContent(
                                    "call-o",
                                    "object",
                                    StateValue.object(Map.of(
                                            "left",
                                            StateValue.string("0"),
                                            "right",
                                            StateValue.string("0"),
                                            "same",
                                            StateValue.string("value")))));
        }
    }

    @Test
    void errorCancellationAndSecurity_shouldRemainSanitizedAndExplicit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        try (Loopback server = new Loopback(exchange -> {
                    if (calls.getAndIncrement() == 0) {
                        exchange.getResponseHeaders().set("x-request-id", "req-1");
                        byte[] body = "{\"error\":\"secret-body\"}".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(500, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                        return;
                    }
                    started.countDown();
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    respond(exchange, 200, "{}");
                });
                OllamaChatClient client = client(server.uri())) {
            assertThatThrownBy(() -> client.completeAsync(new ChatClientRequest(
                                    List.of(Message.text(Role.USER, "hello")), ChatOptions.empty()))
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(OllamaProviderException.class)
                    .hasMessageNotContaining("secret-body");

            var handle = client.startCompletion(
                    new ChatClientRequest(List.of(Message.text(Role.USER, "wait")), ChatOptions.empty()));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            handle.cancel();
            assertThatThrownBy(() -> handle.resultAsync().toCompletableFuture().join())
                    .hasRootCauseInstanceOf(RunCancelledException.class);
        }

        assertThatThrownBy(() -> OllamaChatClientOptions.builder()
                        .model("test")
                        .endpoint("http://remote.example/api")
                        .allowedHosts(java.util.Set.of("remote.example"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThat(OllamaChatClientOptions.builder()
                        .model("test")
                        .bearerToken("super-secret")
                        .build()
                        .toString())
                .doesNotContain("super-secret");
    }

    @Test
    void malformedDuplicateJson_shouldBeRejected() throws Exception {
        try (Loopback server = new Loopback(
                        exchange -> respond(exchange, 200, "{\"model\":\"one\",\"model\":\"two\",\"done\":true}"));
                OllamaChatClient client = client(server.uri())) {
            assertThatThrownBy(() -> client.completeAsync(new ChatClientRequest(
                                    List.of(Message.text(Role.USER, "hello")), ChatOptions.empty()))
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(com.microsoft.agents.core.SerializationException.class);
        }
    }

    private static OllamaChatClient client(URI endpoint) {
        return OllamaChatClient.builder()
                .options(OllamaChatClientOptions.builder()
                        .model("test-model")
                        .endpoint(endpoint)
                        .timeout(Duration.ofSeconds(5))
                        .build())
                .build();
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

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
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
            server.createContext("/api/chat", handler::handle);
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
