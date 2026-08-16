// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.mistral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StructuredOutputOptions;
import com.microsoft.agents.core.ValidationException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MistralLoopbackTest {
    @Test
    void finite_shouldUseRealHttpAndMapToolsUsageAndIdentifiers() throws Exception {
        try (Loopback server = new Loopback(exchange -> {
                    String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    assertThat(request)
                            .contains(
                                    "\"model\":\"test-model\"",
                                    "\"name\":\"lookup\"",
                                    "\"response_format\"",
                                    "\"name\":\"answer\"",
                                    "\"strict\":true");
                    respond(exchange, 200, """
                    {"id":"resp-1","model":"test-model","created":1,
                     "choices":[{"index":0,"message":{"role":"assistant","content":"done",
                       "tool_calls":[{"id":"call-1","type":"function",
                         "function":{"name":"lookup","arguments":"{\\"city\\":\\"Paris\\"}"}}]},
                       "finish_reason":"tool_calls"}],
                     "usage":{"prompt_tokens":4,"completion_tokens":3,"total_tokens":7}}
                    """, "application/json");
                });
                MistralChatClient client = client(server.uri())) {
            ChatClientRequest request = new ChatClientRequest(
                    List.of(Message.text(Role.USER, "hello")),
                    ChatOptions.builder()
                            .structuredOutput(StructuredOutputOptions.jsonSchema(
                                    "answer", Map.of("type", StateValue.string("object"))))
                            .build(),
                    List.of(tool()),
                    ToolMode.AUTO,
                    null);

            var response = client.completeAsync(request).toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertThat(response.responseId()).isEqualTo("resp-1");
            assertThat(response.model()).isEqualTo("test-model");
            assertThat(response.usage().totalTokens()).contains(java.math.BigInteger.valueOf(7));
            assertThat(response.messages().getFirst().contents())
                    .anySatisfy(content -> assertThat(content)
                            .isEqualTo(new FunctionCallContent(
                                    "call-1",
                                    "lookup",
                                    StateValue.object(Map.of("city", StateValue.string("Paris"))))));
        }
    }

    @Test
    void streaming_shouldAssembleFragmentedToolCallAndUsageOnlyTerminal() throws Exception {
        try (Loopback server = new Loopback(exchange -> respond(
                        exchange,
                        200,
                        "data: {\"id\":\"resp-2\",\"model\":\"test-model\",\"created\":2,"
                                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hel\"},"
                                + "\"finish_reason\":null}]}\n\n"
                                + "data: {\"id\":\"resp-2\",\"model\":\"test-model\",\"created\":2,"
                                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\","
                                + "\"tool_calls\":[{\"index\":0,\"id\":\"call-2\",\"function\":{"
                                + "\"name\":\"lookup\",\"arguments\":\"{\\\"city\\\":\\\"Pa\"}}]},"
                                + "\"finish_reason\":null}]}\n\n"
                                + "data: {\"id\":\"resp-2\",\"model\":\"test-model\",\"created\":2,"
                                + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                                + "\"function\":{\"arguments\":\"ris\\\"}\"}}]},"
                                + "\"finish_reason\":\"tool_calls\"}]}\n\n"
                                + "data: {\"id\":\"resp-2\",\"model\":\"test-model\",\"choices\":[],"
                                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":4,\"total_tokens\":9}}\n\n"
                                + "data: [DONE]\n\n",
                        "text/event-stream"));
                MistralChatClient client = client(server.uri())) {
            List<ChatResponseUpdate> updates = collect(client.completeStreaming(
                    new ChatClientRequest(List.of(Message.text(Role.USER, "hello")), ChatOptions.empty())));

            assertThat(updates.stream().map(ChatResponseUpdate::text).reduce("", String::concat))
                    .isEqualTo("hello");
            ChatResponseUpdate terminal = updates.getLast();
            assertThat(terminal.finishReason()).isEqualTo(com.microsoft.agents.core.FinishReason.TOOL_CALLS);
            assertThat(terminal.usage().totalTokens()).contains(java.math.BigInteger.valueOf(9));
            assertThat(terminal.contents().getFirst())
                    .isEqualTo(new FunctionCallContent(
                            "call-2", "lookup", StateValue.object(Map.of("city", StateValue.string("Paris")))));
        }
    }

    @Test
    void streaming_shouldConcatenateVerbatimArgumentsAcrossInterleavedTools() {
        MistralMessageMapper.StreamAssembler assembler = new MistralMessageMapper.StreamAssembler(json(), "request-1");

        assembler.accept(streamToolDelta(
                "{\"index\":1,\"id\":\"call-b\",\"function\":{\"name\":\"second\","
                        + "\"arguments\":\"{\\\"digits\\\":1\"}}",
                null));
        assembler.accept(streamToolDelta(
                "{\"index\":0,\"id\":\"call-a\",\"function\":{\"name\":\"first\","
                        + "\"arguments\":\"{\\\"equal\\\":\\\"same\"}}",
                null));
        assembler.accept(streamToolDelta("{\"index\":1,\"function\":{\"arguments\":\"1,\\\"zeros\\\":\\\"0\"}}", null));
        assembler.accept(
                streamToolDelta("{\"index\":0,\"function\":{\"arguments\":\"same\\\",\\\"utf8\\\":\\\"東\"}}", null));
        assembler.accept(streamToolDelta("{\"index\":1,\"function\":{\"arguments\":\"0\\\"}\"}}", null));
        assembler.accept(streamToolDelta("{\"index\":0,\"function\":{\"arguments\":\"京\\\"}\"}}", "tool_calls"));

        ChatResponseUpdate terminal = assembler.finish();

        assertThat(terminal.contents())
                .containsExactly(
                        new FunctionCallContent(
                                "call-a",
                                "first",
                                StateValue.object(Map.of(
                                        "equal", StateValue.string("samesame"), "utf8", StateValue.string("東京")))),
                        new FunctionCallContent(
                                "call-b",
                                "second",
                                StateValue.object(Map.of(
                                        "digits", StateValue.integer(11),
                                        "zeros", StateValue.string("00")))));
    }

    @Test
    void streaming_shouldRejectMalformedArgumentsAtTerminal() {
        MistralMessageMapper.StreamAssembler assembler = new MistralMessageMapper.StreamAssembler(json(), "request-1");
        assembler.accept(streamToolDelta(
                "{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"lookup\","
                        + "\"arguments\":\"{\\\"value\\\":\"}}",
                "tool_calls"));

        assertThatThrownBy(assembler::finish)
                .isInstanceOf(MistralProviderException.class)
                .hasMessageContaining("malformed_json");
    }

    @Test
    void vision_shouldSendImageUrlAsCurrentObjectWireShape() throws Exception {
        try (Loopback server = new Loopback(exchange -> {
                    String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    assertThat(request)
                            .contains(
                                    "\"type\":\"image_url\"",
                                    "\"image_url\":{\"url\":\"https://example.com/image.png\"}")
                            .doesNotContain("\"image_url\":\"https://example.com/image.png\"");
                    respond(
                            exchange,
                            200,
                            "{\"id\":\"vision-1\",\"model\":\"test-model\",\"created\":1,"
                                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                                    + "\"content\":\"seen\"},\"finish_reason\":\"stop\"}]}",
                            "application/json");
                });
                MistralChatClient client = client(server.uri())) {
            Message message = new Message(
                    Role.USER,
                    List.of(
                            new com.microsoft.agents.core.TextContent("describe"),
                            new com.microsoft.agents.core.UriContent(
                                    URI.create("https://example.com/image.png"), "image/png")));

            var response = client.completeAsync(new ChatClientRequest(List.of(message), ChatOptions.empty()))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertThat(response.text()).isEqualTo("seen");
        }
    }

    @Test
    void errorAndCancellation_shouldBeSanitizedAndExplicit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        try (Loopback server = new Loopback(exchange -> {
                    if (calls.getAndIncrement() == 0) {
                        exchange.getResponseHeaders().add("x-request-id", "req-safe");
                        respond(
                                exchange,
                                429,
                                "{\"error\":{\"code\":\"rate_limit\",\"message\":\"secret-body\"}}",
                                "application/json");
                        return;
                    }
                    started.countDown();
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    respond(exchange, 200, "{}", "application/json");
                });
                MistralChatClient client = client(server.uri())) {
            assertThatThrownBy(() -> client.completeAsync(new ChatClientRequest(
                                    List.of(Message.text(Role.USER, "hello")), ChatOptions.empty()))
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(MistralProviderException.class)
                    .rootCause()
                    .hasMessageContaining("req-safe")
                    .hasMessageNotContaining("secret-body");

            var handle = client.startCompletion(
                    new ChatClientRequest(List.of(Message.text(Role.USER, "wait")), ChatOptions.empty()));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(handle.cancel()).isTrue();
            assertThatThrownBy(() -> handle.resultAsync().toCompletableFuture().join())
                    .hasRootCauseInstanceOf(RunCancelledException.class);
        }
    }

    @Test
    void validationAndOptions_shouldRejectUnsafeInputAndRedactSecrets() {
        MistralChatClientOptions options = MistralChatClientOptions.builder()
                .model("test")
                .apiKey("super-secret")
                .build();
        assertThat(options.toString()).doesNotContain("super-secret").contains("[REDACTED]");
        assertThatThrownBy(() -> MistralChatClientOptions.builder()
                        .model("test")
                        .apiKey("key")
                        .endpoint("http://example.com/v1")
                        .allowedHosts(Set.of("example.com"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");

        MistralTransport neverCalled = new MistralTransport() {
            @Override
            public java.util.concurrent.CompletionStage<com.microsoft.agents.core.ChatResponse> completeAsync(
                    ChatClientRequest request,
                    MistralChatClientOptions ignored,
                    com.microsoft.agents.core.RunCancellation cancellation) {
                throw new AssertionError("transport called");
            }

            @Override
            public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                    ChatClientRequest request,
                    MistralChatClientOptions ignored,
                    com.microsoft.agents.core.RunCancellation cancellation) {
                throw new AssertionError("transport called");
            }
        };
        try (MistralChatClient client = MistralChatClient.builder()
                .options(options)
                .transport(neverCalled)
                .build()) {
            assertThatThrownBy(() -> client.completeAsync(new ChatClientRequest(
                                    List.of(new Message(
                                            Role.USER,
                                            List.of(new com.microsoft.agents.core.UriContent(
                                                    URI.create("http://example.com/image.png"), "image/png")))),
                                    ChatOptions.empty()))
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(ValidationException.class);
        }
    }

    @Test
    void malformedAndOversizedResponses_shouldFailWithinConfiguredBounds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Loopback server = new Loopback(exchange -> {
                    if (calls.getAndIncrement() == 0) {
                        respond(exchange, 200, "{\"id\":\"one\",\"id\":\"two\",\"choices\":[]}", "application/json");
                    } else {
                        respond(exchange, 200, "{\"padding\":\"" + "x".repeat(512) + "\"}", "application/json");
                    }
                });
                MistralChatClient client = MistralChatClient.builder()
                        .options(MistralChatClientOptions.builder()
                                .model("test-model")
                                .endpoint(server.uri().resolve("v1/"))
                                .authenticationMode(MistralAuthenticationMode.NONE)
                                .allowInsecureLoopback(true)
                                .maxResponseBytes(128)
                                .build())
                        .build()) {
            ChatClientRequest request =
                    new ChatClientRequest(List.of(Message.text(Role.USER, "hello")), ChatOptions.empty());

            assertThatThrownBy(() ->
                            client.completeAsync(request).toCompletableFuture().join())
                    .hasRootCauseInstanceOf(MistralProviderException.class);
            assertThatThrownBy(() ->
                            client.completeAsync(request).toCompletableFuture().join())
                    .hasRootCauseInstanceOf(MistralProviderException.class);
        }
    }

    private static MistralChatClient client(URI endpoint) {
        return MistralChatClient.builder()
                .options(MistralChatClientOptions.builder()
                        .model("test-model")
                        .endpoint(endpoint.resolve("v1/"))
                        .authenticationMode(MistralAuthenticationMode.NONE)
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
                StateValue.object(Map.of(
                        "type",
                        StateValue.string("object"),
                        "properties",
                        StateValue.object(
                                Map.of("city", StateValue.object(Map.of("type", StateValue.string("string"))))))),
                StateValue.object(Map.of("type", StateValue.string("object"))));
    }

    private static MistralJson json() {
        return new MistralJson(MistralChatClientOptions.builder()
                .model("test-model")
                .endpoint("http://127.0.0.1:1/v1/")
                .authenticationMode(MistralAuthenticationMode.NONE)
                .allowInsecureLoopback(true)
                .build());
    }

    private static String streamToolDelta(String toolDelta, String finishReason) {
        String finish = finishReason == null ? "null" : "\"" + finishReason + "\"";
        return "{\"id\":\"resp-1\",\"model\":\"test-model\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"tool_calls\":["
                + toolDelta
                + "]},\"finish_reason\":"
                + finish
                + "}]}";
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

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static final class Loopback implements AutoCloseable {
        private final HttpServer server;

        private Loopback(Handler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> handler.handle(exchange));
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
