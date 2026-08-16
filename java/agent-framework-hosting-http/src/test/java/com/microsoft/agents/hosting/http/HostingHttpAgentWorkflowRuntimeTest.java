// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingRegistry;
import com.microsoft.agents.hosting.HostingWorkflowCodecs;
import com.microsoft.agents.workflows.Workflow;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HostingHttpAgentWorkflowRuntimeTest {
    @Test
    void runtime_shouldReserveSseControlFramesOutsideSingleEventBuffer() throws Exception {
        HostingRuntimeTestSupport.ScriptedChatClient transport = new HostingRuntimeTestSupport.ScriptedChatClient()
                .enqueueStreaming((request, cancellation) -> HostingRuntimeTestSupport.scriptedPublisher(
                        List.of(HostingRuntimeTestSupport.update(0, "only", FinishReason.STOP)), 0, null));
        HostingLimits limits = HostingLimits.builder().maxSseBufferedEvents(1).build();
        HostingRegistry registry = new HostingRegistry();
        try (ChatAgent agent = HostingRuntimeTestSupport.chatAgent("single-buffer", transport);
                HostingDispatcher dispatcher = new HostingDispatcher(registry, limits);
                HostingHttpServer server = HostingHttpServer.start(
                        dispatcher,
                        HostingHttpServerOptions.builder().limits(limits).build());
                HttpClient client = HttpClient.newHttpClient()) {
            registry.registerAgent(agent);

            HttpResponse<String> response = client.send(
                    json(server, "/v1/agents/single-buffer/runs/stream", agentBody(), "text/event-stream"),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains(
                            "event: run-started", "event: agent-update", "event: terminal", "\"status\":\"completed\"")
                    .doesNotContain("\"status\":\"overflow\"");
            assertThat(dispatcher.activeRunCount()).isZero();
        }
    }

    @Test
    void runtime_shouldServeAllAgentAndWorkflowRouteFamilies() throws Exception {
        HostingRuntimeTestSupport.ScriptedChatClient transport = new HostingRuntimeTestSupport.ScriptedChatClient()
                .enqueue(HostingRuntimeTestSupport.response("finite-agent"))
                .enqueueStreaming((request, cancellation) -> HostingRuntimeTestSupport.scriptedPublisher(
                        List.of(
                                HostingRuntimeTestSupport.update(0, "first", null),
                                HostingRuntimeTestSupport.update(1, "second", FinishReason.STOP)),
                        70,
                        null));
        HostingLimits limits =
                HostingLimits.builder().idleTimeout(Duration.ofSeconds(2)).build();
        HostingRegistry registry = new HostingRegistry();
        try (ChatAgent agent = HostingRuntimeTestSupport.chatAgent("production-agent", transport);
                Workflow<String, String> workflow = HostingRuntimeTestSupport.workflow("production-workflow");
                HostingDispatcher dispatcher = new HostingDispatcher(registry, limits);
                HostingHttpServer server = start(registry, agent, workflow, dispatcher, limits);
                HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> api = client.send(
                    HttpRequest.newBuilder(uri(server, "/v1"))
                            .header("Accept", "application/json")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> agents = get(client, server, "/v1/agents");
            HttpResponse<String> agentDescriptor = get(client, server, "/v1/agents/production-agent");
            HttpResponse<String> workflows = get(client, server, "/v1/workflows");
            HttpResponse<String> workflowDescriptor = get(client, server, "/v1/workflows/production-workflow");
            HttpResponse<String> agentResult = client.send(
                    json(server, "/v1/agents/production-agent/runs", agentBody(), "application/json"),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> workflowResult = client.send(
                    json(server, "/v1/workflows/production-workflow/runs", workflowBody(), "application/json"),
                    HttpResponse.BodyHandlers.ofString());
            SseCapture agentStream = stream(
                    client, json(server, "/v1/agents/production-agent/runs/stream", agentBody(), "text/event-stream"));
            SseCapture workflowStream = stream(
                    client,
                    json(server, "/v1/workflows/production-workflow/runs/stream", workflowBody(), "text/event-stream"));

            assertThat(api.statusCode()).isEqualTo(200);
            assertThat(api.body())
                    .contains(
                            "\"basePath\":\"/v1\"",
                            "\"webSocketPath\":\"/v1/ws\"",
                            "\"lastEventIdReplay\":false",
                            "\"crossProcessResume\":false");
            assertThat(agents.body()).contains("\"id\":\"production-agent\"");
            assertThat(agentDescriptor.body()).contains("\"kind\":\"agent\"", "\"streamingSupported\":true");
            assertThat(workflows.body()).contains("\"id\":\"production-workflow\"");
            assertThat(workflowDescriptor.body()).contains("\"kind\":\"workflow\"", "\"resumeSupported\":false");
            assertThat(agentResult.statusCode()).isEqualTo(200);
            assertThat(agentResult.body()).contains("\"status\":\"completed\"", "finite-agent");
            assertThat(workflowResult.statusCode()).isEqualTo(200);
            assertThat(workflowResult.body()).contains("\"status\":\"completed\"", "hello-workflow");
            assertThat(agentStream.status()).isEqualTo(200);
            assertThat(agentStream.events()).containsExactly("run-started", "agent-update", "agent-update", "terminal");
            assertThat(agentStream.eventTimesMillis().get(2)
                            - agentStream.eventTimesMillis().get(1))
                    .isGreaterThanOrEqualTo(40L);
            assertThat(workflowStream.status()).isEqualTo(200);
            assertThat(workflowStream.events())
                    .startsWith("run-started")
                    .contains("workflow-event")
                    .endsWith("terminal");
            assertThat(dispatcher.activeRunCount()).isZero();
        }
    }

    @Test
    void runtime_shouldPropagateSseDisconnectCancellationThroughChatAgent() throws Exception {
        CompletableFuture<Boolean> cancelled = new CompletableFuture<>();
        HostingRuntimeTestSupport.ScriptedChatClient transport = new HostingRuntimeTestSupport.ScriptedChatClient()
                .enqueueStreaming((request, cancellation) -> HostingRuntimeTestSupport.pendingPublisher(cancelled));
        HostingLimits limits = HostingLimits.builder()
                .idleTimeout(Duration.ofMillis(200))
                .runTimeout(Duration.ofSeconds(5))
                .build();
        HostingRegistry registry = new HostingRegistry();
        try (ChatAgent agent = HostingRuntimeTestSupport.chatAgent("pending-agent", transport);
                HostingDispatcher dispatcher = new HostingDispatcher(registry, limits);
                HostingHttpServer server = HostingHttpServer.start(
                        dispatcher,
                        HostingHttpServerOptions.builder().limits(limits).build());
                HttpClient client = HttpClient.newHttpClient()) {
            registry.registerAgent(agent);
            HttpResponse<java.io.InputStream> response = client.send(
                    json(server, "/v1/agents/pending-agent/runs/stream", agentBody(), "text/event-stream"),
                    HttpResponse.BodyHandlers.ofInputStream());
            BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));

            assertThat(reader.readLine()).startsWith("id: ");
            response.body().close();

            assertThat(cancelled.orTimeout(5, TimeUnit.SECONDS).join()).isTrue();
            awaitNoActiveRuns(dispatcher);
        }
    }

    private static HostingHttpServer start(
            HostingRegistry registry,
            ChatAgent agent,
            Workflow<String, String> workflow,
            HostingDispatcher dispatcher,
            HostingLimits limits) {
        registry.registerAgent(agent);
        registry.registerWorkflow(workflow, HostingWorkflowCodecs.text());
        return HostingHttpServer.start(
                dispatcher, HostingHttpServerOptions.builder().limits(limits).build());
    }

    private static HttpResponse<String> get(HttpClient client, HostingHttpServer server, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri(server, path))
                        .header("Accept", "application/json")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static SseCapture stream(HttpClient client, HttpRequest request) throws Exception {
        long started = System.nanoTime();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        ArrayList<String> events = new ArrayList<>();
        ArrayList<Long> times = new ArrayList<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: ")) {
                    events.add(line.substring("event: ".length()));
                    times.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                }
            }
        }
        return new SseCapture(response.statusCode(), List.copyOf(events), List.copyOf(times));
    }

    private static HttpRequest json(HostingHttpServer server, String path, String body, String accept) {
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

    private static String agentBody() {
        return """
                {
                  "version":"java-hosting-2026-08-01",
                  "messages":[
                    {"role":"user","contents":[{"kind":"text","text":"hello"}]}
                  ]
                }
                """;
    }

    private static String workflowBody() {
        return """
                {
                  "version":"java-hosting-2026-08-01",
                  "input":"hello"
                }
                """;
    }

    private static void awaitNoActiveRuns(HostingDispatcher dispatcher) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (dispatcher.activeRunCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(dispatcher.activeRunCount()).isZero();
    }

    private record SseCapture(int status, List<String> events, List<Long> eventTimesMillis) {}
}
