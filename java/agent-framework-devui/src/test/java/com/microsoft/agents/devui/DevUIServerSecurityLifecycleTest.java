// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.devui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DevUIServerSecurityLifecycleTest {
    @Test
    void runtime_shouldRejectMalformedTraversalAndUnknownAssetPaths() throws Exception {
        // Arrange
        HostingRegistry registry = new HostingRegistry();
        registry.registerAgent(new ImmediateAgent());
        try (HostingDispatcher dispatcher = new HostingDispatcher(registry, HostingLimits.defaults());
                DevUIServer server = DevUIServer.start(
                        dispatcher, DevUIServerOptions.builder().build());
                HttpClient client = HttpClient.newHttpClient()) {

            // Act
            HttpResponse<String> encodedTraversal = get(client, resolve(server, "/devui/%2e%2e/secret"));
            HttpResponse<String> duplicateSlash = get(client, resolve(server, "/devui//app.js"));
            HttpResponse<String> query = get(client, resolve(server, "/devui/app.js?cache=1"));
            HttpResponse<String> unknown = get(client, resolve(server, "/devui/secrets.txt"));
            HttpResponse<String> method = client.send(
                    HttpRequest.newBuilder(resolve(server, "/devui/app.js"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            // Assert
            assertError(encodedTraversal, 400, "malformed_request");
            assertError(duplicateSlash, 400, "malformed_request");
            assertError(query, 400, "malformed_request");
            assertError(unknown, 404, "not_found");
            assertError(method, 405, "method_not_allowed");
            assertThat(method.headers().firstValue("allow")).contains("GET");
            assertThat(encodedTraversal.body()).doesNotContain("Exception", "java.", "stackTrace");
        }
    }

    @Test
    void close_shouldCancelActiveSseAndRefuseNewConnections() throws Exception {
        // Arrange
        PendingAgent agent = new PendingAgent();
        HostingLimits limits = HostingLimits.builder()
                .idleTimeout(Duration.ofSeconds(5))
                .runTimeout(Duration.ofSeconds(10))
                .build();
        HostingRegistry registry = new HostingRegistry();
        registry.registerAgent(agent);
        try (HostingDispatcher dispatcher = new HostingDispatcher(registry, limits);
                HttpClient client = HttpClient.newHttpClient()) {
            DevUIServer server = DevUIServer.start(
                    dispatcher, DevUIServerOptions.builder().limits(limits).build());
            URI endpoint = server.endpoint();
            HttpResponse<java.io.InputStream> stream = client.send(
                    HttpRequest.newBuilder(resolve(server, "/v1/agents/pending/runs/stream"))
                            .header("Accept", "text/event-stream")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("""
                                    {
                                      "version":"java-hosting-2026-08-01",
                                      "messages":[
                                        {"role":"user","contents":[{"kind":"text","text":"wait"}]}
                                      ]
                                    }
                                    """))
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8));
            assertThat(reader.readLine()).startsWith("id: ");

            // Act
            server.closeAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
            stream.body().close();
            server.close();

            // Assert
            assertThat(agent.cancelled.orTimeout(5, TimeUnit.SECONDS).join()).isTrue();
            assertThat(server.isRunning()).isFalse();
            assertThat(dispatcher.activeRunCount()).isZero();
            assertThatThrownBy(() -> client.send(
                            HttpRequest.newBuilder(endpoint).GET().build(), HttpResponse.BodyHandlers.ofString()))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void requestLimits_shouldRejectBeforeReadingAndCloseStalledBodies() throws Exception {
        // Arrange
        HostingLimits limits = HostingLimits.builder()
                .maxConcurrentRequests(1)
                .idleTimeout(Duration.ofSeconds(2))
                .build();
        HostingRegistry registry = new HostingRegistry();
        registry.registerAgent(new ImmediateAgent());
        try (HostingDispatcher dispatcher = new HostingDispatcher(registry, limits);
                DevUIServer server = DevUIServer.start(
                        dispatcher, DevUIServerOptions.builder().limits(limits).build());
                HttpClient client = HttpClient.newHttpClient();
                Socket stalled = new Socket(
                        server.endpoint().getHost(), server.endpoint().getPort())) {
            stalled.setSoTimeout(4000);
            String request = "POST /v1/agents/immediate/runs HTTP/1.1\r\n"
                    + "Host: "
                    + server.endpoint().getAuthority()
                    + "\r\nContent-Type: application/json\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{";
            stalled.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            stalled.getOutputStream().flush();

            // Act
            HttpResponse<String> rejected = null;
            for (int attempt = 0; attempt < 20; attempt++) {
                rejected = get(client, resolve(server, "/devui/"));
                if (rejected.statusCode() == 429) {
                    break;
                }
                Thread.sleep(25);
            }

            // Assert
            assertError(rejected, 429, "too_many_requests");
            assertThat(stalled.getInputStream().read()).isEqualTo(-1);
        }
    }

    private static HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI resolve(DevUIServer server, String path) {
        URI endpoint = server.endpoint();
        return URI.create(endpoint.getScheme() + "://" + endpoint.getAuthority() + path);
    }

    private static void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).contains("\"code\":\"" + code + "\"");
        assertThat(response.headers().firstValue("content-security-policy"))
                .contains("default-src 'none'; frame-ancestors 'none'");
        assertThat(response.headers().firstValue("x-content-type-options")).contains("nosniff");
    }

    private static class ImmediateAgent implements Agent<Void> {
        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata("immediate", "Immediate", "Immediate developer UI test agent");
        }

        @Override
        public RunHandle<AgentResponse<Void>> startRun(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            RunHandleSource<AgentResponse<Void>> source = new RunHandleSource<>(cancellation);
            source.tryComplete(AgentResponse.<Void>builder()
                    .messages(List.of(Message.text(Role.ASSISTANT, "ok")))
                    .build());
            return source.handle();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicBoolean complete = new AtomicBoolean();

                @Override
                public void request(long count) {
                    if (count > 0 && complete.compareAndSet(false, true)) {
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    complete.set(true);
                }
            });
        }
    }

    private static final class PendingAgent extends ImmediateAgent {
        private final CompletableFuture<Boolean> cancelled = new CompletableFuture<>();

        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata("pending", "Pending", "Pending developer UI test agent");
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicBoolean stopped = new AtomicBoolean();

                @Override
                public void request(long count) {
                    cancellation.cancelledAsync().whenComplete((ignored, failure) -> {
                        if (stopped.compareAndSet(false, true)) {
                            cancelled.complete(true);
                            subscriber.onComplete();
                        }
                    });
                }

                @Override
                public void cancel() {
                    if (stopped.compareAndSet(false, true)) {
                        cancelled.complete(true);
                    }
                }
            });
        }
    }
}
