// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JdkTelegramBotClientTest {
    @Test
    void sendMessageAsync_shouldSendExpectedJsonAndDecodeResult() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        try (LoopbackServer server = LoopbackServer.start(exchange -> {
                    path.set(exchange.getRequestURI().getPath());
                    body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    respond(exchange, 200, """
                    {"ok":true,"result":{"message_id":42}}
                    """);
                });
                JdkTelegramBotClient client = client(server.endpoint())) {
            TelegramSendMessageResult result = await(client.sendMessageAsync(
                    new TelegramSendMessageRequest(-100, "hello"), new DefaultRunCancellation()));

            assertThat(result.messageId()).isEqualTo(42);
            assertThat(path.get()).isEqualTo("/bot123456:abcdefghij/sendMessage");
            assertThat(body.get()).contains("\"chat_id\":-100").contains("\"text\":\"hello\"");
        }
    }

    @Test
    void sendMessageAsync_shouldEncodeWorstCaseBoundedTextAndReportSmallerConfiguredLimit() throws IOException {
        AtomicInteger requestBytes = new AtomicInteger();
        try (LoopbackServer server = LoopbackServer.start(exchange -> {
                    requestBytes.set(exchange.getRequestBody().readAllBytes().length);
                    respond(exchange, 200, """
                    {"ok":true,"result":{"message_id":42}}
                    """);
                });
                JdkTelegramBotClient client = client(server.endpoint())) {
            String escapedText = String.valueOf((char) 0).repeat(TelegramWebhookOptions.TELEGRAM_MAX_TEXT_LENGTH);

            TelegramSendMessageResult result = await(client.sendMessageAsync(
                    new TelegramSendMessageRequest(100, escapedText), new DefaultRunCancellation()));

            assertThat(result.messageId()).isEqualTo(42);
            assertThat(requestBytes).hasValueGreaterThan(16 * 1024);
        }

        TelegramBotClientOptions constrained = TelegramBotClientOptions.builder()
                .botToken("123456:abcdefghij")
                .maxRequestBytes(16)
                .build();
        try (JdkTelegramBotClient client = new JdkTelegramBotClient(constrained)) {
            assertBotFailure(
                    client.sendMessageAsync(new TelegramSendMessageRequest(100, "hello"), new DefaultRunCancellation()),
                    TelegramBotErrorCode.REQUEST_TOO_LARGE);
        }
    }

    @Test
    void sendMessageAsync_shouldRejectRedirectWithoutFollowingIt() throws IOException {
        AtomicInteger redirectedRequests = new AtomicInteger();
        try (LoopbackServer server = LoopbackServer.start(exchange -> {
                    if (exchange.getRequestURI().getPath().equals("/redirected")) {
                        redirectedRequests.incrementAndGet();
                        respond(exchange, 200, """
                        {"ok":true,"result":{"message_id":99}}
                        """);
                    } else {
                        exchange.getResponseHeaders().add("Location", "/redirected");
                        exchange.sendResponseHeaders(302, -1);
                        exchange.close();
                    }
                });
                JdkTelegramBotClient client = client(server.endpoint())) {
            assertBotFailure(
                    client.sendMessageAsync(new TelegramSendMessageRequest(100, "hello"), new DefaultRunCancellation()),
                    TelegramBotErrorCode.HTTP_ERROR);
            assertThat(redirectedRequests).hasValue(0);
        }
    }

    @Test
    void sendMessageAsync_shouldMapApiErrorsAndBoundResponses() throws IOException {
        try (LoopbackServer apiError = LoopbackServer.start(exchange -> respond(exchange, 200, """
                        {"ok":false,"error_code":400,"description":"bad request"}
                        """));
                JdkTelegramBotClient client = client(apiError.endpoint())) {
            TelegramBotException failure = botFailure(client.sendMessageAsync(
                    new TelegramSendMessageRequest(100, "hello"), new DefaultRunCancellation()));
            assertThat(failure.code()).isEqualTo(TelegramBotErrorCode.API_ERROR);
            assertThat(failure.apiErrorCode()).isEqualTo(400);
        }

        try (LoopbackServer oversized = LoopbackServer.start(exchange -> respond(exchange, 200, "x".repeat(2048)));
                JdkTelegramBotClient client = client(
                        oversized.endpoint(),
                        TelegramBotClientOptions.builder()
                                .botToken("123456:abcdefghij")
                                .endpoint(oversized.endpoint())
                                .allowInsecureLoopback(true)
                                .maxResponseBytes(128)
                                .requestTimeout(Duration.ofSeconds(2))
                                .build())) {
            assertBotFailure(
                    client.sendMessageAsync(new TelegramSendMessageRequest(100, "hello"), new DefaultRunCancellation()),
                    TelegramBotErrorCode.RESPONSE_TOO_LARGE);
        }
    }

    @Test
    void sendMessageAsync_shouldCancelInFlightRequest() throws IOException {
        try (LoopbackServer server = LoopbackServer.start(exchange -> {
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                });
                JdkTelegramBotClient client = client(server.endpoint())) {
            DefaultRunCancellation cancellation = new DefaultRunCancellation();
            CompletionStage<TelegramSendMessageResult> stage =
                    client.sendMessageAsync(new TelegramSendMessageRequest(100, "hello"), cancellation);

            cancellation.cancel();

            assertBotFailure(stage, TelegramBotErrorCode.CANCELLED);
        }
    }

    @Test
    void sendMessageAsync_shouldTimeoutInFlightRequest() throws IOException {
        try (LoopbackServer server = LoopbackServer.start(exchange -> {
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                });
                JdkTelegramBotClient client = client(
                        server.endpoint(),
                        TelegramBotClientOptions.builder()
                                .botToken("123456:abcdefghij")
                                .endpoint(server.endpoint())
                                .allowInsecureLoopback(true)
                                .requestTimeout(Duration.ofMillis(75))
                                .build())) {
            assertBotFailure(
                    client.sendMessageAsync(new TelegramSendMessageRequest(100, "hello"), new DefaultRunCancellation()),
                    TelegramBotErrorCode.TIMEOUT);
        }
    }

    @Test
    void constructor_shouldRejectRedirectFollowingHttpClient() {
        TelegramBotClientOptions options =
                TelegramBotClientOptions.builder().botToken("123456:abcdefghij").build();
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        assertThatThrownBy(() -> new JdkTelegramBotClient(options, client))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disable redirects");
    }

    @Test
    void options_shouldRequireHttpsExceptExplicitLoopbackHttp() {
        assertThatThrownBy(() -> TelegramBotClientOptions.builder()
                        .botToken("123456:abcdefghij")
                        .endpoint(URI.create("http://example.com/"))
                        .allowedHosts(java.util.Set.of("example.com"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    private static JdkTelegramBotClient client(URI endpoint) {
        return client(
                endpoint,
                TelegramBotClientOptions.builder()
                        .botToken("123456:abcdefghij")
                        .endpoint(endpoint)
                        .allowInsecureLoopback(true)
                        .requestTimeout(Duration.ofSeconds(2))
                        .build());
    }

    private static JdkTelegramBotClient client(URI endpoint, TelegramBotClientOptions options) {
        assertThat(options.endpoint()).isEqualTo(endpoint);
        return new JdkTelegramBotClient(options);
    }

    private static void assertBotFailure(CompletionStage<?> stage, TelegramBotErrorCode code) {
        assertThat(botFailure(stage).code()).isEqualTo(code);
    }

    private static TelegramBotException botFailure(CompletionStage<?> stage) {
        try {
            stage.toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            throw new AssertionError("Expected TelegramBotException.");
        } catch (java.util.concurrent.CompletionException exception) {
            assertThat(exception.getCause()).isInstanceOf(TelegramBotException.class);
            return (TelegramBotException) exception.getCause();
        }
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static final class LoopbackServer implements AutoCloseable {
        private final HttpServer server;

        private final java.util.concurrent.ExecutorService executor;

        private LoopbackServer(HttpServer server, java.util.concurrent.ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        static LoopbackServer start(Handler handler) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.createContext("/", exchange -> handler.handle(exchange));
            server.start();
            return new LoopbackServer(server, executor);
        }

        URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
