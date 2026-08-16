// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.devui;

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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DevUIServerRuntimeTest {
    private static final String RUN_BODY = """
            {
              "version":"java-hosting-2026-08-01",
              "messages":[
                {"role":"user","contents":[{"kind":"text","text":"hello"}]}
              ]
            }
            """;

    @Test
    void runtime_shouldServeBoundedEmbeddedAssetsAndSecurityHeaders() throws Exception {
        // Arrange
        HostingRegistry registry = new HostingRegistry();
        registry.registerAgent(new EchoAgent());
        try (HostingDispatcher dispatcher = new HostingDispatcher(registry, HostingLimits.defaults());
                DevUIServer server = DevUIServer.start(
                        dispatcher, DevUIServerOptions.builder().build());
                HttpClient client = HttpClient.newHttpClient()) {

            // Act
            HttpResponse<String> index = get(client, server.endpoint());
            HttpResponse<String> styles = get(client, resolve(server, "/devui/app.css"));
            HttpResponse<String> script = get(client, resolve(server, "/devui/app.js"));
            HttpResponse<String> configuration = get(client, resolve(server, DevUIServer.CONFIG_PATH));

            // Assert
            assertThat(index.statusCode()).isEqualTo(200);
            assertThat(index.body()).contains("Agent Framework Dev UI", "./app.css", "./app.js");
            assertThat(index.body()).doesNotContain("http://", "https://", "cdn.");
            assertThat(styles.statusCode()).isEqualTo(200);
            assertThat(styles.headers().firstValue("content-type").orElseThrow())
                    .startsWith("text/css");
            assertThat(styles.body()).contains("--background", ".route-card");
            assertThat(script.statusCode()).isEqualTo(200);
            assertThat(script.headers().firstValue("content-type").orElseThrow())
                    .startsWith("text/javascript");
            assertThat(script.body()).contains("fetch(", "readSse");
            assertThat(script.body()).doesNotContain("https://", "http://", "cdn.");
            assertThat(configuration.statusCode()).isEqualTo(200);
            assertThat(configuration.body())
                    .contains("\"apiBasePath\":\"/v1\"", "\"sameOrigin\":true", "\"streamingTransport\":\"sse\"");
            assertThat(configuration.body()).doesNotContain("http://", "https://");
            assertSecurityHeaders(index);
            assertSecurityHeaders(styles);
            assertSecurityHeaders(script);
            assertSecurityHeaders(configuration);
            assertThat(index.headers().firstValue("access-control-allow-origin"))
                    .isEmpty();
        }
    }

    @Test
    void runtime_shouldRouteGenericJsonAndSseApisOnTheUiOrigin() throws Exception {
        // Arrange
        HostingLimits limits =
                HostingLimits.builder().idleTimeout(Duration.ofSeconds(2)).build();
        HostingRegistry registry = new HostingRegistry();
        registry.registerAgent(new EchoAgent());
        try (HostingDispatcher dispatcher = new HostingDispatcher(registry, limits);
                DevUIServer server = DevUIServer.start(
                        dispatcher, DevUIServerOptions.builder().limits(limits).build());
                HttpClient client = HttpClient.newHttpClient()) {

            // Act
            HttpResponse<String> root = get(client, server.apiEndpoint());
            HttpResponse<String> routes = get(client, resolve(server, "/v1/agents"));
            HttpResponse<String> finite = client.send(
                    runRequest(server, "/v1/agents/echo/runs", "application/json"),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> stream = client.send(
                    runRequest(server, "/v1/agents/echo/runs/stream", "text/event-stream"),
                    HttpResponse.BodyHandlers.ofString());

            // Assert
            assertThat(server.endpoint().getScheme())
                    .isEqualTo(server.apiEndpoint().getScheme());
            assertThat(server.endpoint().getAuthority())
                    .isEqualTo(server.apiEndpoint().getAuthority());
            assertThat(server.apiEndpoint().getPath()).isEqualTo("/v1");
            assertThat(root.statusCode()).isEqualTo(200);
            assertThat(root.body()).contains("\"basePath\":\"/v1\"");
            assertThat(routes.statusCode()).isEqualTo(200);
            assertThat(routes.body()).contains("\"id\":\"echo\"", "\"type\":\"route-list\"");
            assertThat(finite.statusCode()).isEqualTo(200);
            assertThat(finite.body()).contains("\"status\":\"completed\"", "echo: hello");
            assertThat(stream.statusCode()).isEqualTo(200);
            assertThat(stream.headers().firstValue("content-type").orElseThrow())
                    .startsWith("text/event-stream");
            assertThat(stream.body())
                    .contains("event: run-started", "event: agent-update", "event: terminal", "stream: hello");
            assertThat(dispatcher.activeRunCount()).isZero();
        }
    }

    private static HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri).header("Accept", "*/*").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest runRequest(DevUIServer server, String path, String accept) {
        return HttpRequest.newBuilder(resolve(server, path))
                .header("Accept", accept)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(RUN_BODY))
                .build();
    }

    private static URI resolve(DevUIServer server, String path) {
        URI endpoint = server.endpoint();
        return URI.create(endpoint.getScheme() + "://" + endpoint.getAuthority() + path);
    }

    private static void assertSecurityHeaders(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        assertThat(response.headers().firstValue("content-security-policy").orElseThrow())
                .contains("script-src 'self'", "style-src 'self'", "connect-src 'self'", "frame-ancestors 'none'");
        assertThat(response.headers().firstValue("cross-origin-opener-policy")).contains("same-origin");
        assertThat(response.headers().firstValue("cross-origin-resource-policy"))
                .contains("same-origin");
        assertThat(response.headers().firstValue("permissions-policy").orElseThrow())
                .contains("camera=()", "microphone=()");
        assertThat(response.headers().firstValue("referrer-policy")).contains("no-referrer");
        assertThat(response.headers().firstValue("x-content-type-options")).contains("nosniff");
        assertThat(response.headers().firstValue("x-frame-options")).contains("DENY");
    }

    private static final class EchoAgent implements Agent<Void> {
        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata("echo", "Echo", "Echo developer UI test agent");
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
                private final AtomicBoolean done = new AtomicBoolean();

                @Override
                public void request(long count) {
                    if (count > 0 && done.compareAndSet(false, true)) {
                        subscriber.onNext(AgentResponseUpdate.builder()
                                .sequence(0)
                                .role(Role.ASSISTANT)
                                .contents(List.of(new TextContent(
                                        "stream: " + messages.getLast().text())))
                                .build());
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    done.set(true);
                }
            });
        }
    }
}
