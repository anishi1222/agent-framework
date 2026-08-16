// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.genai.Client;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancelledException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GeminiSdkTransportLoopbackTest {
    private static final String TEST_KEY = "test-api-key";

    @Test
    void realSdk_shouldPinEndpointAndMapFiniteAndStreamingResponses() throws Exception {
        try (Loopback server = new Loopback(exchange -> {
                    assertThat(exchange.getRequestURI().getPath()).startsWith("/v1beta/models/test-model:");
                    assertThat(exchange.getRequestHeaders().getFirst("x-goog-api-key"))
                            .isEqualTo(TEST_KEY);
                    if (exchange.getRequestURI().getPath().contains("streamGenerateContent")) {
                        respond(exchange, 200, streamBody("stream-", "ok"), "text/event-stream");
                    } else {
                        respond(exchange, 200, finiteBody("finite"), "application/json");
                    }
                });
                GeminiSdkTransport transport = GeminiSdkTransport.create(options(server.uri()))) {
            ChatClientRequest request = request("hello");
            var finite = transport
                    .completeAsync(request, options(server.uri()), new DefaultRunCancellation())
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            List<ChatResponseUpdate> updates = collect(
                            transport.completeStreaming(request, options(server.uri()), new DefaultRunCancellation()))
                    .get(5, TimeUnit.SECONDS);

            assertThat(finite.text()).isEqualTo("finite");
            assertThat(updates.stream().map(ChatResponseUpdate::text).reduce("", String::concat))
                    .isEqualTo("stream-ok");
            assertThat(updates.getLast().finishReason()).isEqualTo(com.microsoft.agents.core.FinishReason.STOP);
            assertThat(server.calls).hasValue(2);
        }
    }

    @Test
    void realSdk_shouldNeverFollowRedirectsOrLeakSecrets() throws Exception {
        try (Loopback target =
                        new Loopback(exchange -> respond(exchange, 200, finiteBody("escaped"), "application/json"));
                Loopback redirect = new Loopback(exchange -> {
                    exchange.getResponseHeaders()
                            .set("Location", target.uri().resolve("escaped").toString());
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
                GeminiSdkTransport transport = GeminiSdkTransport.create(options(redirect.uri()))) {
            assertThatThrownBy(() -> transport
                            .completeAsync(request("redirect"), options(redirect.uri()), new DefaultRunCancellation())
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(GeminiProviderException.class)
                    .hasMessageNotContaining(TEST_KEY);
            assertThat(target.calls).hasValue(0);
            assertThat(options(redirect.uri()).toString()).doesNotContain(TEST_KEY);
        }
    }

    @Test
    void realSdk_shouldEnforceResponseAndEventBoundsAndRejectMalformedBodies() throws Exception {
        AtomicInteger response = new AtomicInteger();
        try (Loopback server = new Loopback(exchange -> {
            if (response.getAndIncrement() == 0) {
                respond(exchange, 200, "x".repeat(1024), "application/json");
            } else {
                respond(exchange, 200, "{\"candidates\":[", "application/json");
            }
        })) {
            GeminiChatClientOptions bounded = options(server.uri(), 256, 256, 8);
            try (GeminiSdkTransport transport = GeminiSdkTransport.create(bounded)) {
                assertThatThrownBy(() -> transport
                                .completeAsync(request("oversized"), bounded, new DefaultRunCancellation())
                                .toCompletableFuture()
                                .join())
                        .hasRootCauseInstanceOf(GeminiProviderException.class)
                        .hasMessageNotContaining("x".repeat(64))
                        .hasMessageNotContaining(TEST_KEY);
                assertThatThrownBy(() -> transport
                                .completeAsync(request("malformed"), bounded, new DefaultRunCancellation())
                                .toCompletableFuture()
                                .join())
                        .hasRootCauseInstanceOf(GeminiProviderException.class)
                        .hasMessageNotContaining(TEST_KEY)
                        .hasMessageNotContaining("candidates");
            }
        }

        try (Loopback server = new Loopback(exchange ->
                respond(exchange, 200, "data: " + finiteBody("x".repeat(512)) + "\r\n\r\n", "text/event-stream"))) {
            GeminiChatClientOptions bounded = options(server.uri(), 2048, 128, 8);
            try (GeminiSdkTransport transport = GeminiSdkTransport.create(bounded)) {
                assertThatThrownBy(() -> collect(transport.completeStreaming(
                                        request("event"), bounded, new DefaultRunCancellation()))
                                .join())
                        .hasRootCauseInstanceOf(GeminiProviderException.class)
                        .hasMessageNotContaining(TEST_KEY)
                        .hasMessageNotContaining("x".repeat(64));
            }
        }
    }

    @Test
    void realSdk_shouldBoundBufferedUpdatesAndCancelInFlightStream() throws Exception {
        try (Loopback server = new Loopback(
                exchange -> respond(exchange, 200, streamBody("one", "two", "three"), "text/event-stream"))) {
            GeminiChatClientOptions bounded = options(server.uri(), 4096, 1024, 1);
            try (GeminiSdkTransport transport = GeminiSdkTransport.create(bounded)) {
                assertThatThrownBy(() -> failureWithoutDemand(transport.completeStreaming(
                                        request("overflow"), bounded, new DefaultRunCancellation()))
                                .get(5, TimeUnit.SECONDS))
                        .hasRootCauseInstanceOf(GeminiProviderException.class);
            }
        }

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Loopback server = new Loopback(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(": ready\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        })) {
            GeminiChatClientOptions options = options(server.uri());
            try (GeminiSdkTransport transport = GeminiSdkTransport.create(options)) {
                DefaultRunCancellation cancellation = new DefaultRunCancellation();
                CompletableFuture<List<ChatResponseUpdate>> result =
                        collect(transport.completeStreaming(request("cancel"), options, cancellation));
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                cancellation.cancel();
                assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                        .hasRootCauseInstanceOf(RunCancelledException.class);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void close_shouldRespectInjectedClientAndExecutorOwnership() {
        GeminiChatClientOptions options = options(URI.create("http://127.0.0.1:1/"));
        Client borrowedClient = mock(Client.class);
        ExecutorService borrowedExecutor = Executors.newSingleThreadExecutor();
        new GeminiSdkTransport(options, borrowedClient, false, borrowedExecutor, false, true).close();
        verify(borrowedClient, never()).close();
        assertThat(borrowedExecutor.isShutdown()).isFalse();
        borrowedExecutor.close();

        Client ownedClient = mock(Client.class);
        ExecutorService ownedExecutor = Executors.newSingleThreadExecutor();
        new GeminiSdkTransport(options, ownedClient, true, ownedExecutor, true, true).close();
        verify(ownedClient).close();
        assertThat(ownedExecutor.isShutdown()).isTrue();
    }

    private static GeminiChatClientOptions options(URI endpoint) {
        return options(endpoint, 16 * 1024 * 1024, 2 * 1024 * 1024, 256);
    }

    private static GeminiChatClientOptions options(
            URI endpoint, int maxResponseBytes, int maxEventBytes, int maxBufferedUpdates) {
        return GeminiChatClientOptions.builder()
                .model("test-model")
                .apiKey(TEST_KEY)
                .endpoint(endpoint)
                .allowInsecureLoopback(true)
                .timeout(Duration.ofSeconds(5))
                .maxResponseBytes(maxResponseBytes)
                .maxEventBytes(maxEventBytes)
                .maxBufferedUpdates(maxBufferedUpdates)
                .build();
    }

    private static ChatClientRequest request(String text) {
        return new ChatClientRequest(List.of(Message.text(Role.USER, text)), ChatOptions.empty());
    }

    private static String finiteBody(String text) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\""
                + text
                + "\"}]},\"finishReason\":\"STOP\",\"index\":0}],"
                + "\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":1,\"totalTokenCount\":2},"
                + "\"modelVersion\":\"test-model\",\"responseId\":\"response-1\"}";
    }

    private static String streamBody(String... chunks) {
        StringBuilder body = new StringBuilder();
        for (int index = 0; index < chunks.length; index++) {
            boolean terminal = index == chunks.length - 1;
            body.append("data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"")
                    .append(chunks[index])
                    .append("\"}]},\"index\":0");
            if (terminal) {
                body.append(",\"finishReason\":\"STOP\"");
            }
            body.append("}],\"modelVersion\":\"test-model\",\"responseId\":\"response-1\"}\n\n");
        }
        return body.toString();
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

    private static CompletableFuture<Throwable> failureWithoutDemand(Flow.Publisher<ChatResponseUpdate> publisher) {
        CompletableFuture<Throwable> result = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {}

            @Override
            public void onNext(ChatResponseUpdate item) {}

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.completeExceptionally(new AssertionError("Expected overflow."));
            }
        });
        return result;
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

        private final AtomicInteger calls = new AtomicInteger();

        private Loopback(Handler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                calls.incrementAndGet();
                handler.handle(exchange);
            });
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
        }

        private URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        }

        @Override
        public void close() {
            server.stop(0);
            if (server.getExecutor() instanceof ExecutorService service) {
                service.close();
            }
        }
    }
}
