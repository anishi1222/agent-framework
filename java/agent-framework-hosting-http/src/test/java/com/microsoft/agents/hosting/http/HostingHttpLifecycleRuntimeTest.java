// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HostingHttpLifecycleRuntimeTest {
    @Test
    void runtime_shouldCancelActiveRunThroughBoundRoute() throws Exception {
        CompletableFuture<Boolean> cancelled = new CompletableFuture<>();
        HostingRuntimeTestSupport.ScriptedChatClient transport = new HostingRuntimeTestSupport.ScriptedChatClient()
                .enqueueStreaming((request, cancellation) -> HostingRuntimeTestSupport.pendingPublisher(cancelled));
        HostingRegistry registry = new HostingRegistry();
        try (ChatAgent agent = HostingRuntimeTestSupport.chatAgent("cancel-agent", transport);
                HostingDispatcher dispatcher = new HostingDispatcher(registry, HostingLimits.defaults());
                HostingHttpServer server = HostingHttpServer.start(
                        dispatcher, HostingHttpServerOptions.builder().build());
                HttpClient client = HttpClient.newHttpClient()) {
            registry.registerAgent(agent);
            HttpResponse<java.io.InputStream> stream = client.send(
                    runRequest(server, "/v1/agents/cancel-agent/runs/stream", "text/event-stream"),
                    HttpResponse.BodyHandlers.ofInputStream());
            String runId = stream.headers().firstValue("x-agent-run-id").orElseThrow();

            HttpResponse<String> cancelledResponse = client.send(
                    HttpRequest.newBuilder(uri(server, "/v1/agents/cancel-agent/runs/" + runId))
                            .DELETE()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            stream.body().close();

            assertThat(cancelledResponse.statusCode()).isEqualTo(204);
            assertThat(cancelled.orTimeout(5, TimeUnit.SECONDS).join()).isTrue();
            assertThat(dispatcher.activeRunCount()).isZero();
        }
    }

    @Test
    void runtime_shouldMapRunTimeoutAndReleaseCapacity() throws Exception {
        CompletableFuture<Boolean> cancelled = new CompletableFuture<>();
        HostingRuntimeTestSupport.ScriptedChatClient transport = new HostingRuntimeTestSupport.ScriptedChatClient()
                .enqueueFinite((request, cancellation) -> {
                    CompletableFuture<ChatResponse> pending = new CompletableFuture<>();
                    cancellation.cancelledAsync().whenComplete((ignored, failure) -> {
                        cancelled.complete(true);
                        pending.completeExceptionally(new RunCancelledException());
                    });
                    return pending;
                });
        HostingLimits limits =
                HostingLimits.builder().runTimeout(Duration.ofMillis(150)).build();
        HostingRegistry registry = new HostingRegistry();
        try (ChatAgent agent = HostingRuntimeTestSupport.chatAgent("timeout-agent", transport);
                HostingDispatcher dispatcher = new HostingDispatcher(registry, limits);
                HostingHttpServer server = HostingHttpServer.start(
                        dispatcher,
                        HostingHttpServerOptions.builder().limits(limits).build());
                HttpClient client = HttpClient.newHttpClient()) {
            registry.registerAgent(agent);

            HttpResponse<String> response = client.send(
                    runRequest(server, "/v1/agents/timeout-agent/runs"), HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(504);
            assertThat(response.body()).contains("\"code\":\"run_timeout\"");
            assertThat(cancelled.orTimeout(5, TimeUnit.SECONDS).join()).isTrue();
            assertThat(dispatcher.activeRunCount()).isZero();
        }
    }

    @Test
    void runtime_shouldLimitConcurrentRequestsAndGracefullyCancelStreams() throws Exception {
        CompletableFuture<Boolean> cancelled = new CompletableFuture<>();
        HostingRuntimeTestSupport.ScriptedChatClient transport = new HostingRuntimeTestSupport.ScriptedChatClient()
                .enqueueStreaming((request, cancellation) -> HostingRuntimeTestSupport.pendingPublisher(cancelled));
        HostingLimits limits = HostingLimits.builder()
                .maxConcurrentRequests(1)
                .idleTimeout(Duration.ofSeconds(5))
                .runTimeout(Duration.ofSeconds(10))
                .build();
        HostingRegistry registry = new HostingRegistry();
        try (ChatAgent agent = HostingRuntimeTestSupport.chatAgent("capacity-agent", transport);
                HostingDispatcher dispatcher = new HostingDispatcher(registry, limits);
                HostingHttpServer server = HostingHttpServer.start(
                        dispatcher,
                        HostingHttpServerOptions.builder()
                                .limits(limits)
                                .gracefulShutdownTimeout(Duration.ofSeconds(2))
                                .build());
                HttpClient client = HttpClient.newHttpClient()) {
            registry.registerAgent(agent);
            HttpResponse<java.io.InputStream> stream = client.send(
                    runRequest(server, "/v1/agents/capacity-agent/runs/stream", "text/event-stream"),
                    HttpResponse.BodyHandlers.ofInputStream());
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8));
            assertThat(reader.readLine()).startsWith("id: ");

            HttpResponse<String> rejected = client.send(
                    HttpRequest.newBuilder(uri(server, "/v1/agents"))
                            .header("Accept", "application/json")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            server.closeAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
            stream.body().close();

            assertThat(rejected.statusCode()).isEqualTo(429);
            assertThat(rejected.body()).contains("\"code\":\"too_many_requests\"");
            assertThat(cancelled.orTimeout(5, TimeUnit.SECONDS).join()).isTrue();
            assertThat(server.isRunning()).isFalse();
            assertThat(dispatcher.activeRunCount()).isZero();
        }
    }

    private static HttpRequest runRequest(HostingHttpServer server, String path) {
        return runRequest(server, path, "application/json");
    }

    private static HttpRequest runRequest(HostingHttpServer server, String path, String accept) {
        return HttpRequest.newBuilder(uri(server, path))
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "version":"java-hosting-2026-08-01",
                          "messages":[
                            {"role":"user","contents":[{"kind":"text","text":"hello"}]}
                          ]
                        }
                        """))
                .build();
    }

    private static URI uri(HostingHttpServer server, String path) {
        URI endpoint = server.endpoint();
        return URI.create(endpoint.getScheme() + "://" + endpoint.getAuthority() + path);
    }
}
