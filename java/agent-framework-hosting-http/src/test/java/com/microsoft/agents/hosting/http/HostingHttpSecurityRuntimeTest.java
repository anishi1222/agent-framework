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
import com.microsoft.agents.hosting.HostingAuthentication;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingPrincipal;
import com.microsoft.agents.hosting.HostingRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HostingHttpSecurityRuntimeTest {
    private static final String VALID_BODY = """
            {
              "version":"java-hosting-2026-08-01",
              "messages":[
                {"role":"user","contents":[{"kind":"text","text":"hello"}]}
              ]
            }
            """;

    @Test
    void runtime_shouldTimeoutAuthenticatorAndReleaseRequestAdmission() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HostingLimits limits = HostingLimits.builder()
                .maxConcurrentRequests(1)
                .idleTimeout(Duration.ofMillis(100))
                .build();
        HostingRegistry registry = new HostingRegistry();
        registry.registerAgent(new ImmediateAgent());
        HostingHttpServerOptions options = HostingHttpServerOptions.builder()
                .limits(limits)
                .authenticator(request -> calls.getAndIncrement() == 0
                        ? new CompletableFuture<>()
                        : CompletableFuture.completedFuture(
                                HostingAuthentication.authenticated(new HostingPrincipal("owner", "tenant"))))
                .build();
        try (HostingDispatcher dispatcher = new HostingDispatcher(registry, limits);
                HostingHttpServer server = HostingHttpServer.start(dispatcher, options);
                HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> timedOut =
                    client.send(request(server, "GET", "/v1/agents", "").build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> admitted =
                    client.send(request(server, "GET", "/v1/agents", "").build(), HttpResponse.BodyHandlers.ofString());

            assertError(timedOut, 504, "run_timeout");
            assertThat(admitted.statusCode()).isEqualTo(200);
            assertThat(admitted.body()).contains("\"id\":\"immediate\"");
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void runtime_shouldMapAuthenticationMediaMethodAcceptAndStrictJsonErrors() throws Exception {
        // Arrange
        HostingRegistry registry = new HostingRegistry();
        registry.registerAgent(new ImmediateAgent());
        HostingHttpServerOptions options = HostingHttpServerOptions.builder()
                .authenticator(request -> {
                    String authorization = request.firstHeader("authorization");
                    if (authorization == null) {
                        return CompletableFuture.completedFuture(HostingAuthentication.unauthenticated());
                    }
                    return CompletableFuture.completedFuture(
                            HostingAuthentication.authenticated(new HostingPrincipal("owner", "tenant")));
                })
                .build();
        try (HostingDispatcher dispatcher = new HostingDispatcher(registry, options.limits());
                HostingHttpServer server = HostingHttpServer.start(dispatcher, options);
                HttpClient client = HttpClient.newHttpClient()) {

            // Act
            HttpResponse<String> unauthenticated = client.send(
                    request(server, "POST", "/v1/agents/immediate/runs", VALID_BODY)
                            .header("Content-Type", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> unsupportedMedia = client.send(
                    authorized(request(server, "POST", "/v1/agents/immediate/runs", VALID_BODY))
                            .header("Content-Type", "text/plain")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> method = client.send(
                    authorized(request(server, "PUT", "/v1/agents/immediate/runs", VALID_BODY))
                            .header("Content-Type", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> accept = client.send(
                    authorized(request(server, "POST", "/v1/agents/immediate/runs", VALID_BODY))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/xml")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> duplicate = client.send(
                    authorized(request(server, "POST", "/v1/agents/immediate/runs", """
                                            {
                                              "version":"java-hosting-2026-08-01",
                                              "input":"one",
                                              "input":"two"
                                            }
                                            """))
                            .header("Content-Type", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> origin = client.send(
                    authorized(request(server, "GET", "/v1/agents", ""))
                            .header("Origin", "https://evil.example")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> query = client.send(
                    authorized(request(server, "GET", "/v1/agents?redirect=https://evil.example", ""))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            // Assert
            assertError(unauthenticated, 401, "unauthenticated");
            assertError(unsupportedMedia, 415, "unsupported_media_type");
            assertError(method, 405, "method_not_allowed");
            assertError(accept, 406, "not_acceptable");
            assertError(duplicate, 400, "malformed_request");
            assertError(origin, 403, "forbidden");
            assertError(query, 400, "malformed_request");
            assertThat(unauthenticated.headers().firstValue("www-authenticate")).contains("Bearer");
            assertThat(rawHostStatus(server, "evil.example")).contains(" 403 ");
        }
    }

    @Test
    void runtime_shouldRejectOversizedBodiesBeforeDispatch() throws Exception {
        // Arrange
        HostingLimits limits = HostingLimits.builder()
                .maxRequestBytes(128)
                .maxWebSocketFrameBytes(128)
                .build();
        HostingRegistry registry = new HostingRegistry();
        registry.registerAgent(new ImmediateAgent());
        HostingHttpServerOptions options =
                HostingHttpServerOptions.builder().limits(limits).build();
        try (HostingDispatcher dispatcher = new HostingDispatcher(registry, limits);
                HostingHttpServer server = HostingHttpServer.start(dispatcher, options);
                HttpClient client = HttpClient.newHttpClient()) {
            String body = "{\"version\":\"java-hosting-2026-08-01\",\"input\":\"" + "x".repeat(256) + "\"}";

            // Act
            HttpResponse<String> response = client.send(
                    request(server, "POST", "/v1/agents/immediate/runs", body)
                            .header("Content-Type", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            // Assert
            assertError(response, 413, "payload_too_large");
        }
    }

    @Test
    void runtime_shouldRejectDuplicateHeadersDepthReplayAndEncodedPaths() throws Exception {
        HostingLimits limits = HostingLimits.builder().maxNestingDepth(6).build();
        HostingRegistry registry = new HostingRegistry();
        registry.registerAgent(new ImmediateAgent());
        try (HostingDispatcher dispatcher = new HostingDispatcher(registry, limits);
                HostingHttpServer server = HostingHttpServer.start(
                        dispatcher,
                        HostingHttpServerOptions.builder().limits(limits).build());
                HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> duplicateAuthorization = client.send(
                    request(server, "GET", "/v1/agents", "")
                            .header("Authorization", "Bearer one")
                            .header("Authorization", "Bearer two")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> duplicateContentType = client.send(
                    request(server, "POST", "/v1/agents/immediate/runs", VALID_BODY)
                            .header("Content-Type", "application/json")
                            .header("Content-Type", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> unsupportedCharset = client.send(
                    request(server, "POST", "/v1/agents/immediate/runs", VALID_BODY)
                            .header("Content-Type", "application/json; charset=iso-8859-1")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> deep = client.send(
                    request(server, "POST", "/v1/agents/immediate/runs", """
                                    {
                                      "version":"java-hosting-2026-08-01",
                                      "input":{"a":{"b":{"c":{"d":{"e":{"f":1}}}}}}
                                    }
                                    """)
                            .header("Content-Type", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> replay = client.send(
                    request(server, "POST", "/v1/agents/immediate/runs/stream", VALID_BODY)
                            .header("Content-Type", "application/json")
                            .header("Accept", "text/event-stream")
                            .header("Last-Event-ID", "7")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> encodedPath = client.send(
                    request(server, "GET", "/v1/agents%2fimmediate", "").build(), HttpResponse.BodyHandlers.ofString());

            assertError(duplicateAuthorization, 400, "malformed_request");
            assertError(duplicateContentType, 400, "malformed_request");
            assertError(unsupportedCharset, 415, "unsupported_media_type");
            assertError(deep, 400, "malformed_request");
            assertError(replay, 422, "unprocessable");
            assertError(encodedPath, 400, "malformed_request");
            assertThat(replay.headers().firstValue("content-security-policy"))
                    .contains("default-src 'none'; frame-ancestors 'none'");
            assertThat(replay.headers().firstValue("x-content-type-options")).contains("nosniff");
            assertThat(replay.headers().firstValue("cache-control")).contains("no-store");
        }
    }

    private static HttpRequest.Builder request(HostingHttpServer server, String method, String path, String body) {
        HttpRequest.BodyPublisher publisher =
                body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        return HttpRequest.newBuilder(uri(server, path)).method(method, publisher);
    }

    private static HttpRequest.Builder authorized(HttpRequest.Builder builder) {
        return builder.header("Authorization", "Bearer owner");
    }

    private static URI uri(HostingHttpServer server, String path) {
        URI endpoint = server.endpoint();
        return URI.create(endpoint.getScheme() + "://" + endpoint.getAuthority() + path);
    }

    private static void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).contains("\"code\":\"" + code + "\"");
        assertThat(response.body()).doesNotContain("Exception", "java.", "stackTrace", "super-secret");
    }

    private static String rawHostStatus(HostingHttpServer server, String host) throws Exception {
        try (Socket socket = new Socket(
                        server.endpoint().getHost(), server.endpoint().getPort());
                OutputStreamWriter writer =
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII);
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
            writer.write("GET /v1/agents HTTP/1.1\r\n");
            writer.write("Host: " + host + "\r\n");
            writer.write("Authorization: Bearer owner\r\n");
            writer.write("Connection: close\r\n\r\n");
            writer.flush();
            return reader.readLine();
        }
    }

    private static final class ImmediateAgent implements Agent<Void> {
        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata("immediate", "Immediate", "test");
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
                @Override
                public void request(long count) {
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {}
            });
        }
    }
}
