// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingRegistry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HostingWebSocketProductionRuntimeTest {
    @Test
    void runtime_shouldCancelActiveRunAfterAbruptPeerDisconnect() throws Exception {
        CompletableFuture<Boolean> cancelled = new CompletableFuture<>();
        HostingRuntimeTestSupport.ScriptedChatClient transport = new HostingRuntimeTestSupport.ScriptedChatClient()
                .enqueueStreaming((request, cancellation) -> HostingRuntimeTestSupport.pendingPublisher(cancelled));
        HostingRegistry registry = new HostingRegistry();
        HttpClient client = HttpClient.newHttpClient();
        try (ChatAgent agent = HostingRuntimeTestSupport.chatAgent("disconnect-agent", transport);
                HostingDispatcher dispatcher = new HostingDispatcher(registry, HostingLimits.defaults());
                HostingHttpServer server = HostingHttpServer.start(
                        dispatcher, HostingHttpServerOptions.builder().build())) {
            registry.registerAgent(agent);
            RecordingListener listener = new RecordingListener(true);
            WebSocket socket = open(client, server, listener);
            socket.sendText(startFrame("disconnect-operation", "disconnect-agent"), true)
                    .join();
            assertThat(listener.nextMessage()).contains("\"type\":\"started\"");

            socket.abort();

            assertThat(cancelled.orTimeout(5, TimeUnit.SECONDS).join()).isTrue();
            awaitNoActiveRuns(dispatcher);
        } finally {
            client.shutdownNow();
        }
    }

    @Test
    void runtime_shouldRejectConcurrentStartBeforeCreatingAnotherRun() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        CompletableFuture<Boolean> cancelled = new CompletableFuture<>();
        HostingRuntimeTestSupport.ScriptedChatClient transport = new HostingRuntimeTestSupport.ScriptedChatClient()
                .enqueueStreaming((request, cancellation) -> {
                    starts.incrementAndGet();
                    return HostingRuntimeTestSupport.pendingPublisher(cancelled);
                })
                .enqueueStreaming((request, cancellation) -> {
                    starts.incrementAndGet();
                    return HostingRuntimeTestSupport.pendingPublisher(new CompletableFuture<>());
                });
        HostingRegistry registry = new HostingRegistry();
        HttpClient client = HttpClient.newHttpClient();
        try (ChatAgent agent = HostingRuntimeTestSupport.chatAgent("single-operation", transport);
                HostingDispatcher dispatcher = new HostingDispatcher(registry, HostingLimits.defaults());
                HostingHttpServer server = HostingHttpServer.start(
                        dispatcher, HostingHttpServerOptions.builder().build())) {
            registry.registerAgent(agent);
            RecordingListener listener = new RecordingListener(true);
            WebSocket socket = open(client, server, listener);
            socket.sendText(startFrame("operation-one", "single-operation"), true)
                    .join();
            assertThat(listener.nextMessage()).contains("\"type\":\"started\"");

            socket.sendText(startFrame("operation-two", "single-operation"), true)
                    .join();
            assertThat(listener.nextMessage()).contains("\"type\":\"error\"", "\"code\":\"conflict\"");
            assertThat(starts).hasValue(1);

            socket.sendText(controlFrame("cancel", "operation-one"), true).join();
            assertThat(listener.nextMessage()).contains("\"type\":\"terminal\"", "\"status\":\"cancelled\"");
            assertThat(cancelled.orTimeout(5, TimeUnit.SECONDS).join()).isTrue();
            socket.sendText(closeFrame(), true).join();
            assertThat(listener.closeCode.orTimeout(5, TimeUnit.SECONDS).join()).isEqualTo(1000);
            socket.abort();
        } finally {
            client.shutdownNow();
        }
    }

    @Test
    void runtime_shouldAcceptFragmentedStartHonorDemandAndCloseNormally() throws Exception {
        HostingRuntimeTestSupport.ScriptedChatClient transport = new HostingRuntimeTestSupport.ScriptedChatClient()
                .enqueueStreaming((request, cancellation) -> HostingRuntimeTestSupport.scriptedPublisher(
                        List.of(
                                HostingRuntimeTestSupport.update(0, "first", null),
                                HostingRuntimeTestSupport.update(1, "second", FinishReason.STOP)),
                        0,
                        null));
        HostingRegistry registry = new HostingRegistry();
        HttpClient client = HttpClient.newHttpClient();
        try (ChatAgent agent = HostingRuntimeTestSupport.chatAgent("socket-agent", transport);
                HostingDispatcher dispatcher = new HostingDispatcher(registry, HostingLimits.defaults());
                HostingHttpServer server = HostingHttpServer.start(
                        dispatcher, HostingHttpServerOptions.builder().build())) {
            registry.registerAgent(agent);
            RecordingListener listener = new RecordingListener(true);
            WebSocket socket = open(client, server, listener);
            String start = startFrame("operation-1", "socket-agent");
            int split = start.length() / 2;

            socket.sendText(start.substring(0, split), false).join();
            socket.sendText(start.substring(split), true).join();
            assertThat(listener.nextMessage()).contains("\"type\":\"started\"", "\"operationId\":\"operation-1\"");
            assertThat(listener.messages.poll(150, TimeUnit.MILLISECONDS)).isNull();
            socket.sendText(demandFrame("operation-1", 2), true).join();

            assertThat(listener.nextMessage()).contains("\"type\":\"event\"", "first");
            assertThat(listener.nextMessage()).contains("\"type\":\"event\"", "second");
            assertThat(listener.nextMessage()).contains("\"type\":\"terminal\"", "\"status\":\"completed\"");
            socket.sendText(closeFrame(), true).join();
            assertThat(listener.closeCode.orTimeout(5, TimeUnit.SECONDS).join()).isEqualTo(1000);
            socket.abort();
            assertThat(dispatcher.activeRunCount()).isZero();
        } finally {
            client.shutdownNow();
        }
    }

    @Test
    void runtime_shouldCancelActiveOperationAndReportBoundedEventOverflow() throws Exception {
        CompletableFuture<Boolean> cancelled = new CompletableFuture<>();
        HostingRuntimeTestSupport.ScriptedChatClient pendingTransport =
                new HostingRuntimeTestSupport.ScriptedChatClient()
                        .enqueueStreaming(
                                (request, cancellation) -> HostingRuntimeTestSupport.pendingPublisher(cancelled));
        HostingRegistry pendingRegistry = new HostingRegistry();
        HttpClient pendingClient = HttpClient.newHttpClient();
        try (ChatAgent agent = HostingRuntimeTestSupport.chatAgent("pending-socket", pendingTransport);
                HostingDispatcher dispatcher = new HostingDispatcher(pendingRegistry, HostingLimits.defaults());
                HostingHttpServer server = HostingHttpServer.start(
                        dispatcher, HostingHttpServerOptions.builder().build())) {
            pendingRegistry.registerAgent(agent);
            RecordingListener listener = new RecordingListener(true);
            WebSocket socket = open(pendingClient, server, listener);
            socket.sendText(startFrame("cancel-operation", "pending-socket"), true)
                    .join();
            assertThat(listener.nextMessage()).contains("\"type\":\"started\"");
            socket.sendText(demandFrame("cancel-operation", 1), true).join();
            socket.sendText(controlFrame("cancel", "cancel-operation"), true).join();

            assertThat(listener.nextMessage()).contains("\"type\":\"terminal\"", "\"status\":\"cancelled\"");
            assertThat(cancelled.orTimeout(5, TimeUnit.SECONDS).join()).isTrue();
            socket.sendText(closeFrame(), true).join();
            assertThat(listener.closeCode.orTimeout(5, TimeUnit.SECONDS).join()).isEqualTo(1000);
            socket.abort();
        } finally {
            pendingClient.shutdownNow();
        }

        HostingLimits overflowLimits =
                HostingLimits.builder().maxEventsPerRun(1).build();
        HostingRuntimeTestSupport.ScriptedChatClient overflowTransport =
                new HostingRuntimeTestSupport.ScriptedChatClient()
                        .enqueueStreaming((request, cancellation) -> HostingRuntimeTestSupport.scriptedPublisher(
                                List.of(
                                        HostingRuntimeTestSupport.update(0, "one", null),
                                        HostingRuntimeTestSupport.update(1, "two", FinishReason.STOP)),
                                0,
                                null));
        HostingRegistry overflowRegistry = new HostingRegistry();
        HttpClient overflowClient = HttpClient.newHttpClient();
        try (ChatAgent agent = HostingRuntimeTestSupport.chatAgent("overflow-socket", overflowTransport);
                HostingDispatcher dispatcher = new HostingDispatcher(overflowRegistry, overflowLimits);
                HostingHttpServer server = HostingHttpServer.start(
                        dispatcher,
                        HostingHttpServerOptions.builder()
                                .limits(overflowLimits)
                                .build())) {
            overflowRegistry.registerAgent(agent);
            RecordingListener listener = new RecordingListener(true);
            WebSocket socket = open(overflowClient, server, listener);
            socket.sendText(startFrame("overflow-operation", "overflow-socket"), true)
                    .join();
            assertThat(listener.nextMessage()).contains("\"type\":\"started\"");
            socket.sendText(demandFrame("overflow-operation", 1), true).join();

            assertThat(listener.nextMessage()).contains("\"type\":\"event\"", "one");
            socket.sendText(demandFrame("overflow-operation", 1), true).join();
            assertThat(listener.nextMessage()).contains("\"type\":\"terminal\"", "\"status\":\"overflow\"");
            socket.sendText(closeFrame(), true).join();
            assertThat(listener.closeCode.orTimeout(5, TimeUnit.SECONDS).join()).isEqualTo(1000);
            socket.abort();
        } finally {
            overflowClient.shutdownNow();
        }
    }

    @Test
    void runtime_shouldEnforceDemandFrameAndCompleteMessageBoundsAndCloseCodes() throws Exception {
        HostingLimits limits = HostingLimits.builder()
                .maxRequestBytes(1024)
                .maxWebSocketFrameBytes(256)
                .build();
        HostingRegistry registry = new HostingRegistry();
        HttpClient client = HttpClient.newHttpClient();
        try (HostingDispatcher dispatcher = new HostingDispatcher(registry, limits);
                HostingHttpServer server = HostingHttpServer.start(
                        dispatcher,
                        HostingHttpServerOptions.builder().limits(limits).build())) {
            RecordingListener malformed = new RecordingListener(true);
            WebSocket malformedSocket = open(client, server, malformed);
            malformedSocket.sendText("{", true).join();
            assertThat(malformed.closeCode.orTimeout(5, TimeUnit.SECONDS).join())
                    .isEqualTo(1007);
            malformedSocket.abort();

            RecordingListener oversized = new RecordingListener(true);
            WebSocket oversizedSocket = open(client, server, oversized);
            oversizedSocket.sendText("x".repeat(1024), true).join();
            assertThat(oversized.closeCode.orTimeout(5, TimeUnit.SECONDS).join())
                    .isEqualTo(1009);
            oversizedSocket.abort();

            RecordingListener binary = new RecordingListener(true);
            WebSocket binarySocket = open(client, server, binary);
            binarySocket.sendBinary(ByteBuffer.wrap(new byte[] {1, 2, 3}), true).join();
            assertThat(binary.closeCode.orTimeout(5, TimeUnit.SECONDS).join()).isEqualTo(1003);
            binarySocket.abort();

            assertThatThrownBy(() -> client.newWebSocketBuilder()
                            .subprotocols("wrong.protocol")
                            .buildAsync(server.webSocketEndpoint(), new RecordingListener(true))
                            .join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(WebSocketHandshakeException.class);
        } finally {
            client.shutdownNow();
        }
    }

    @Test
    void runtime_shouldKeepPongingPeerAliveAndIdleCloseSilentPeer() throws Exception {
        HostingLimits limits =
                HostingLimits.builder().idleTimeout(Duration.ofMillis(300)).build();
        HostingRegistry registry = new HostingRegistry();
        HttpClient client = HttpClient.newHttpClient();
        try (HostingDispatcher dispatcher = new HostingDispatcher(registry, limits);
                HostingHttpServer server = HostingHttpServer.start(
                        dispatcher,
                        HostingHttpServerOptions.builder().limits(limits).build())) {
            RecordingListener responsive = new RecordingListener(true);
            WebSocket responsiveSocket = open(client, server, responsive);
            Thread.sleep(850);
            assertThat(responsiveSocket.isInputClosed()).isFalse();
            responsiveSocket.sendText(closeFrame(), true).join();
            assertThat(responsive.closeCode.orTimeout(5, TimeUnit.SECONDS).join())
                    .isEqualTo(1000);
            responsiveSocket.abort();

            assertThat(rawSilentPeerCloseCode(server)).isEqualTo(1001);
        } finally {
            client.shutdownNow();
        }
    }

    private static int rawSilentPeerCloseCode(HostingHttpServer server) throws Exception {
        try (Socket socket = new Socket(
                server.webSocketEndpoint().getHost(), server.webSocketEndpoint().getPort())) {
            socket.setSoTimeout(5_000);
            String key = Base64.getEncoder().encodeToString(new byte[16]);
            String request = "GET /v1/ws HTTP/1.1\r\n"
                    + "Host: "
                    + server.webSocketEndpoint().getAuthority()
                    + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Key: "
                    + key
                    + "\r\n"
                    + "Sec-WebSocket-Protocol: "
                    + HostingWebSocketProtocol.SUBPROTOCOL
                    + "\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            InputStream input = socket.getInputStream();
            String headers = readHttpHeaders(input);
            assertThat(headers).startsWith("HTTP/1.1 101");
            while (true) {
                int first = input.read();
                int second = input.read();
                if (first < 0 || second < 0) {
                    throw new AssertionError("WebSocket closed without a close frame.");
                }
                int length = second & 0x7f;
                if (length == 126) {
                    length = (input.read() << 8) | input.read();
                } else if (length == 127) {
                    long extended = 0;
                    for (int index = 0; index < 8; index++) {
                        extended = (extended << 8) | input.read();
                    }
                    if (extended > Integer.MAX_VALUE) {
                        throw new AssertionError("Unexpected WebSocket frame length.");
                    }
                    length = (int) extended;
                }
                byte[] payload = input.readNBytes(length);
                if ((first & 0x0f) == 0x8) {
                    return payload.length < 2 ? 1005 : ((payload[0] & 0xff) << 8) | (payload[1] & 0xff);
                }
            }
        }
    }

    private static String readHttpHeaders(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int next = input.read();
            if (next < 0) {
                throw new AssertionError("WebSocket handshake ended early.");
            }
            output.write(next);
            int expected =
                    switch (matched) {
                        case 0, 2 -> '\r';
                        case 1, 3 -> '\n';
                        default -> throw new IllegalStateException();
                    };
            matched = next == expected ? matched + 1 : next == '\r' ? 1 : 0;
        }
        return output.toString(StandardCharsets.US_ASCII);
    }

    private static WebSocket open(HttpClient client, HostingHttpServer server, RecordingListener listener) {
        return client.newWebSocketBuilder()
                .subprotocols(HostingWebSocketProtocol.SUBPROTOCOL)
                .buildAsync(server.webSocketEndpoint(), listener)
                .orTimeout(5, TimeUnit.SECONDS)
                .join();
    }

    private static void awaitNoActiveRuns(HostingDispatcher dispatcher) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (dispatcher.activeRunCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(dispatcher.activeRunCount()).isZero();
    }

    private static String startFrame(String operationId, String routeId) {
        return """
                {
                  "version":"java-hosting-2026-08-01",
                  "type":"start",
                  "operationId":"%s",
                  "kind":"agent",
                  "routeId":"%s",
                  "request":{
                    "version":"java-hosting-2026-08-01",
                    "messages":[
                      {"role":"user","contents":[{"kind":"text","text":"hello"}]}
                    ]
                  }
                }
                """.formatted(operationId, routeId);
    }

    private static String demandFrame(String operationId, long count) {
        return """
                {
                  "version":"java-hosting-2026-08-01",
                  "type":"demand",
                  "operationId":"%s",
                  "count":%d
                }
                """.formatted(operationId, count);
    }

    private static String controlFrame(String type, String operationId) {
        return """
                {
                  "version":"java-hosting-2026-08-01",
                  "type":"%s",
                  "operationId":"%s"
                }
                """.formatted(type, operationId);
    }

    private static String closeFrame() {
        return """
                {
                  "version":"java-hosting-2026-08-01",
                  "type":"close"
                }
                """;
    }

    private static final class RecordingListener implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();

        private final StringBuilder partial = new StringBuilder();

        private final boolean respondToPing;

        private RecordingListener(boolean respondToPing) {
            this.respondToPing = respondToPing;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return respondToPing
                    ? WebSocket.Listener.super.onPing(webSocket, message)
                    : CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode.complete(statusCode);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closeCode.completeExceptionally(error);
        }

        private String nextMessage() throws InterruptedException {
            String message = messages.poll(5, TimeUnit.SECONDS);
            assertThat(message).isNotNull();
            return message;
        }
    }
}
