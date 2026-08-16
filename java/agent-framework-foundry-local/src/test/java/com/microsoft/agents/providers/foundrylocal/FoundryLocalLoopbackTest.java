// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundrylocal;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
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
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FoundryLocalLoopbackTest {
    @Test
    void discovery_shouldUseDocumentedProcessNeutralEndpoints() throws Exception {
        try (Loopback server = new Loopback();
                FoundryLocalChatClient client = client(server.uri())) {
            FoundryLocalStatus status =
                    client.statusAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
            List<String> cached =
                    client.cachedModelsAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
            List<FoundryLocalModel> catalog =
                    client.catalogAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertThat(status.endpoints()).containsExactly(server.uri());
            assertThat(cached).containsExactly("local-model");
            assertThat(catalog).singleElement().satisfies(model -> {
                assertThat(model.name()).isEqualTo("local-model");
                assertThat(model.supportsToolCalling()).isTrue();
            });
            assertThat(client.capabilities().nativeProcessManagement()).isFalse();
        }
    }

    @Test
    void chat_shouldDelegateToDocumentedChatCompletionsNotResponses() throws Exception {
        try (Loopback server = new Loopback();
                FoundryLocalChatClient client = client(server.uri())) {
            var finite = client.completeAsync(new ChatClientRequest(
                            List.of(Message.text(Role.USER, "hello")),
                            ChatOptions.builder()
                                    .structuredOutput(StructuredOutputOptions.jsonSchema(
                                            "answer", Map.of("type", StateValue.string("object"))))
                                    .build()))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertThat(server.lastRequest.get())
                    .contains("\"response_format\"", "\"name\":\"answer\"", "\"strict\":true");
            List<ChatResponseUpdate> stream = collect(client.completeStreaming(
                    new ChatClientRequest(List.of(Message.text(Role.USER, "hello")), ChatOptions.empty())));

            assertThat(finite.text()).isEqualTo("local");
            assertThat(stream.stream().map(ChatResponseUpdate::text).reduce("", String::concat))
                    .isEqualTo("local");
            assertThat(finite.metadata().keySet()).noneMatch(key -> key.startsWith("mistral."));
            assertThat(server.responsesCalls).hasValue(0);
        }
    }

    @Test
    void streamingToolCalls_shouldPreserveExactFieldsArgumentsAndProviderOrder() throws Exception {
        try (Loopback server = new Loopback();
                FoundryLocalChatClient client = client(server.uri())) {
            List<ChatResponseUpdate> updates = collect(client.completeStreaming(
                    new ChatClientRequest(List.of(Message.text(Role.USER, "tool stream")), ChatOptions.empty())));

            assertThat(updates.getLast().contents())
                    .containsExactly(
                            new FunctionCallContent(
                                    "call-a",
                                    "first",
                                    StateValue.object(Map.of(
                                            "repeated", StateValue.string("same-same"),
                                            "utf8", StateValue.string("東京")))),
                            new FunctionCallContent(
                                    "call-b",
                                    "second",
                                    StateValue.object(Map.of(
                                            "digits", StateValue.integer(11),
                                            "zeros", StateValue.string("00")))));
        }
    }

    private static FoundryLocalChatClient client(URI endpoint) {
        return FoundryLocalChatClient.builder()
                .options(FoundryLocalChatClientOptions.builder()
                        .endpoint(endpoint)
                        .model("local-model")
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

    private static void respond(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static final class Loopback implements AutoCloseable {
        private final HttpServer server;

        private final java.util.concurrent.atomic.AtomicInteger responsesCalls =
                new java.util.concurrent.atomic.AtomicInteger();

        private final AtomicReference<String> lastRequest = new AtomicReference<>();

        private Loopback() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/openai/status",
                    exchange -> respond(
                            exchange,
                            200,
                            "{\"Endpoints\":[\"" + uri() + "\"],\"ModelDirPath\":\"/models\",\"PipeName\":\"pipe\"}",
                            "application/json"));
            server.createContext(
                    "/openai/models", exchange -> respond(exchange, 200, "[\"local-model\"]", "application/json"));
            server.createContext(
                    "/foundry/list",
                    exchange -> respond(
                            exchange,
                            200,
                            "{\"models\":[{\"name\":\"local-model\",\"alias\":\"local\",\"displayName\":\"Local\","
                                    + "\"providerType\":\"AzureFoundry\",\"version\":\"1\",\"modelType\":\"ONNX\","
                                    + "\"task\":\"chat-completion\",\"supportsToolCalling\":true,"
                                    + "\"license\":\"MIT\"}]}",
                            "application/json"));
            server.createContext("/v1/chat/completions", exchange -> {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                lastRequest.set(request);
                if (request.contains("\"stream\":true")) {
                    if (request.contains("tool stream")) {
                        respond(
                                exchange,
                                200,
                                "data: {\"id\":\"local-tool\",\"model\":\"local-model\","
                                        + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":["
                                        + "{\"index\":1,\"id\":\"call-b\",\"function\":{\"name\":\"second\","
                                        + "\"arguments\":\"{\\\"digits\\\":1\"}}]},"
                                        + "\"finish_reason\":null}]}\n\n"
                                        + "data: {\"id\":\"local-tool\",\"model\":\"local-model\","
                                        + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":["
                                        + "{\"index\":0,\"id\":\"call-a\",\"function\":{\"name\":\"first\","
                                        + "\"arguments\":\"{\\\"repeated\\\":\\\"same-\"}}]},"
                                        + "\"finish_reason\":null}]}\n\n"
                                        + "data: {\"id\":\"local-tool\",\"model\":\"local-model\","
                                        + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":["
                                        + "{\"index\":1,\"function\":{\"arguments\":\"1,\\\"zeros\\\":\\\"0\"}}]},"
                                        + "\"finish_reason\":null}]}\n\n"
                                        + "data: {\"id\":\"local-tool\",\"model\":\"local-model\","
                                        + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":["
                                        + "{\"index\":0,\"function\":{\"arguments\":\"same\\\","
                                        + "\\\"utf8\\\":\\\"東\"}}]},\"finish_reason\":null}]}\n\n"
                                        + "data: {\"id\":\"local-tool\",\"model\":\"local-model\","
                                        + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":["
                                        + "{\"index\":1,\"function\":{\"arguments\":\"0\\\"}\"}}]},"
                                        + "\"finish_reason\":null}]}\n\n"
                                        + "data: {\"id\":\"local-tool\",\"model\":\"local-model\","
                                        + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":["
                                        + "{\"index\":0,\"function\":{\"arguments\":\"京\\\"}\"}}]},"
                                        + "\"finish_reason\":\"tool_calls\"}]}\n\n"
                                        + "data: [DONE]\n\n",
                                "text/event-stream");
                        return;
                    }
                    respond(
                            exchange,
                            200,
                            "data: {\"id\":\"local-1\",\"model\":\"local-model\","
                                    + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"local\"},"
                                    + "\"finish_reason\":\"stop\"}]}\n\n"
                                    + "data: [DONE]\n\n",
                            "text/event-stream");
                } else {
                    respond(
                            exchange,
                            200,
                            "{\"id\":\"local-1\",\"model\":\"local-model\","
                                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                                    + "\"content\":\"local\"},\"finish_reason\":\"stop\"}],"
                                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}",
                            "application/json");
                }
            });
            server.createContext("/v1/responses", exchange -> {
                responsesCalls.incrementAndGet();
                respond(exchange, 404, "{}", "application/json");
            });
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
