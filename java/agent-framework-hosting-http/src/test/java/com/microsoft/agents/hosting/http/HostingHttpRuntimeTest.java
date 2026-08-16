// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HostingHttpRuntimeTest {
    @Test
    void embeddedRuntime_shouldServeDiscoveryFiniteAndGradualSse() throws Exception {
        // Arrange
        EchoAgent agent = new EchoAgent();
        HostingRegistry registry = new HostingRegistry();
        registry.registerAgent(agent);
        HostingLimits limits =
                HostingLimits.builder().idleTimeout(Duration.ofSeconds(2)).build();
        try (HostingDispatcher dispatcher = new HostingDispatcher(registry, limits);
                HostingHttpServer server = HostingHttpServer.start(
                        dispatcher,
                        HostingHttpServerOptions.builder().limits(limits).build());
                HttpClient client = HttpClient.newHttpClient()) {

            // Act
            HttpResponse<String> discovery = client.send(
                    HttpRequest.newBuilder(uri(server, "/v1/agents"))
                            .header("Accept", "application/json")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> finite =
                    client.send(jsonRequest(server, "/v1/agents/echo/runs"), HttpResponse.BodyHandlers.ofString());
            long startedAt = System.nanoTime();
            HttpResponse<java.io.InputStream> stream = client.send(
                    jsonRequest(server, "/v1/agents/echo/runs/stream", "text/event-stream"),
                    HttpResponse.BodyHandlers.ofInputStream());
            List<String> events = new ArrayList<>();
            List<Long> elapsedMillis = new ArrayList<>();
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event: ")) {
                        events.add(line.substring("event: ".length()));
                        elapsedMillis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                    }
                }
            }

            // Assert
            assertThat(discovery.statusCode()).isEqualTo(200);
            assertThat(discovery.body()).contains("\"id\":\"echo\"");
            assertThat(finite.statusCode()).isEqualTo(200);
            assertThat(finite.body()).contains("\"status\":\"completed\"", "echo: hello");
            assertThat(stream.statusCode()).isEqualTo(200);
            assertThat(stream.headers().firstValue("content-type").orElseThrow())
                    .startsWith("text/event-stream");
            assertThat(events).containsExactly("run-started", "agent-update", "agent-update", "terminal");
            assertThat(elapsedMillis.get(2) - elapsedMillis.get(1)).isGreaterThanOrEqualTo(40L);
            assertThat(dispatcher.activeRunCount()).isZero();
        }
    }

    @Test
    void embeddedRuntime_shouldExchangeTypedWebSocketFramesWithDemand() throws Exception {
        // Arrange
        EchoAgent agent = new EchoAgent();
        HostingRegistry registry = new HostingRegistry();
        registry.registerAgent(agent);
        try (HostingDispatcher dispatcher = new HostingDispatcher(registry, HostingLimits.defaults());
                HostingHttpServer server = HostingHttpServer.start(
                        dispatcher, HostingHttpServerOptions.builder().build());
                HttpClient client = HttpClient.newHttpClient()) {
            RecordingWebSocketListener listener = new RecordingWebSocketListener();
            WebSocket socket = client.newWebSocketBuilder()
                    .subprotocols(HostingWebSocketProtocol.SUBPROTOCOL)
                    .buildAsync(server.webSocketEndpoint(), listener)
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();

            // Act
            socket.sendText(startFrame(), true).join();
            String started = listener.messages.poll(5, TimeUnit.SECONDS);
            socket.sendText(demandFrame(3), true).join();
            String eventOne = listener.messages.poll(5, TimeUnit.SECONDS);
            String eventTwo = listener.messages.poll(5, TimeUnit.SECONDS);
            String terminal = listener.messages.poll(5, TimeUnit.SECONDS);
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();

            // Assert
            assertThat(started).contains("\"type\":\"started\"", "\"runId\":\"run-");
            assertThat(eventOne).contains("\"type\":\"event\"", "first");
            assertThat(eventTwo).contains("\"type\":\"event\"", "second");
            assertThat(terminal).contains("\"type\":\"terminal\"", "\"status\":\"completed\"");
            assertThat(listener.subprotocol).isEqualTo(HostingWebSocketProtocol.SUBPROTOCOL);
            assertThat(dispatcher.activeRunCount()).isZero();
        }
    }

    @Test
    void embeddedRuntime_shouldCancelSseAfterPeerDisconnectAndIdleDeadline() throws Exception {
        // Arrange
        PendingStreamingAgent agent = new PendingStreamingAgent();
        HostingRegistry registry = new HostingRegistry();
        registry.registerAgent(agent);
        HostingLimits limits = HostingLimits.builder()
                .idleTimeout(Duration.ofMillis(250))
                .runTimeout(Duration.ofSeconds(5))
                .build();
        try (HostingDispatcher dispatcher = new HostingDispatcher(registry, limits);
                HostingHttpServer server = HostingHttpServer.start(
                        dispatcher,
                        HostingHttpServerOptions.builder().limits(limits).build());
                HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<java.io.InputStream> stream = client.send(
                    jsonRequest(server, "/v1/agents/pending/runs/stream", "text/event-stream"),
                    HttpResponse.BodyHandlers.ofInputStream());
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8));

            // Act
            assertThat(reader.readLine()).startsWith("id: ");
            stream.body().close();

            // Assert
            assertThat(agent.cancelled.orTimeout(5, TimeUnit.SECONDS).join()).isTrue();
            awaitNoActiveRuns(dispatcher);
        }
    }

    private static void awaitNoActiveRuns(HostingDispatcher dispatcher) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (dispatcher.activeRunCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(dispatcher.activeRunCount()).isZero();
    }

    private static HttpRequest jsonRequest(HostingHttpServer server, String path) {
        return jsonRequest(server, path, "application/json");
    }

    private static HttpRequest jsonRequest(HostingHttpServer server, String path, String accept) {
        String body = """
                {
                  "version":"java-hosting-2026-08-01",
                  "messages":[
                    {"role":"user","contents":[{"kind":"text","text":"hello"}]}
                  ]
                }
                """;
        return HttpRequest.newBuilder(uri(server, path))
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static URI uri(HostingHttpServer server, String path) {
        URI endpoint = server.endpoint();
        return URI.create(endpoint.getScheme() + "://" + endpoint.getAuthority() + path);
    }

    private static String startFrame() {
        return """
                {
                  "version":"java-hosting-2026-08-01",
                  "type":"start",
                  "operationId":"operation-1",
                  "kind":"agent",
                  "routeId":"echo",
                  "request":{
                    "version":"java-hosting-2026-08-01",
                    "messages":[
                      {"role":"user","contents":[{"kind":"text","text":"hello"}]}
                    ]
                  }
                }
                """;
    }

    private static String demandFrame(long count) {
        return """
                {
                  "version":"java-hosting-2026-08-01",
                  "type":"demand",
                  "operationId":"operation-1",
                  "count":%d
                }
                """.formatted(count);
    }

    private static final class EchoAgent implements Agent<Void> {
        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata("echo", "Echo", "Echo test agent");
        }

        @Override
        public RunHandle<AgentResponse<Void>> startRun(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            RunHandleSource<AgentResponse<Void>> source = new RunHandleSource<>(cancellation);
            source.tryComplete(AgentResponse.<Void>builder()
                    .messages(List.of(Message.text(
                            Role.ASSISTANT, "echo: " + messages.getLast().text())))
                    .build());
            return source.handle();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicInteger index = new AtomicInteger();

                private final AtomicBoolean done = new AtomicBoolean();

                @Override
                public void request(long count) {
                    if (count <= 0 || done.get()) {
                        return;
                    }
                    long remaining = count;
                    while (remaining-- > 0 && !done.get()) {
                        int next = index.getAndIncrement();
                        if (next == 0) {
                            subscriber.onNext(update(0, "first"));
                        } else if (next == 1) {
                            try {
                                Thread.sleep(75);
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                            }
                            subscriber.onNext(update(1, "second"));
                            if (done.compareAndSet(false, true)) {
                                subscriber.onComplete();
                            }
                        }
                    }
                }

                @Override
                public void cancel() {
                    done.set(true);
                }
            });
        }

        private static AgentResponseUpdate update(long sequence, String text) {
            return AgentResponseUpdate.builder()
                    .sequence(sequence)
                    .contents(List.of(new TextContent(text)))
                    .role(Role.ASSISTANT)
                    .build();
        }
    }

    private static final class PendingStreamingAgent implements Agent<Void> {
        private final CompletableFuture<Boolean> cancelled = new CompletableFuture<>();

        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata("pending", "Pending", "Pending test agent");
        }

        @Override
        public RunHandle<AgentResponse<Void>> startRun(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            return new RunHandleSource<AgentResponse<Void>>(cancellation).handle();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {
                    // Hold until the peer disconnects or the stream becomes idle.
                }

                @Override
                public void cancel() {
                    cancelled.complete(true);
                }
            });
        }
    }

    private static final class RecordingWebSocketListener implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        private final StringBuilder partial = new StringBuilder();

        private volatile String subprotocol;

        @Override
        public void onOpen(WebSocket webSocket) {
            subprotocol = webSocket.getSubprotocol();
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
            return WebSocket.Listener.super.onPing(webSocket, message);
        }
    }
}
