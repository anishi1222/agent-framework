// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.hosting.HostingAuthentication;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingJsonCodec;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingPrincipal;
import com.microsoft.agents.hosting.HostingRegistry;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HostingHttpResumeRuntimeTest {
    @Test
    void finiteDeliveryFailure_shouldReleaseContinuationCapacityAcrossRepeatedDisconnects() {
        int attempts = 100;
        AtomicInteger invocations = new AtomicInteger();
        HostingRuntimeTestSupport.ScriptedChatClient transport = new HostingRuntimeTestSupport.ScriptedChatClient();
        for (int attempt = 0; attempt < attempts; attempt++) {
            transport.enqueue(approvalResponse("undelivered-" + attempt));
        }
        HostingLimits limits =
                HostingLimits.builder().maxProcessLocalContinuations(1).build();
        HostingRegistry registry = new HostingRegistry();
        try (ChatAgent agent = new ChatAgent(
                        transport,
                        new AgentMetadata(
                                "approval-agent", "Approval agent", "Repeated undelivered continuation test agent"),
                        ChatOptions.empty(),
                        List.of(approvalTool(invocations)));
                HostingDispatcher dispatcher = new HostingDispatcher(registry, limits);
                HostingHttpHandler handler = new HostingHttpHandler(
                        dispatcher,
                        HostingHttpServerOptions.builder().limits(limits).build())) {
            registry.registerAgent(agent);

            for (int attempt = 0; attempt < attempts; attempt++) {
                HostingHttpResponse response = handler.handleAsync(localRunRequest())
                        .toCompletableFuture()
                        .orTimeout(5, TimeUnit.SECONDS)
                        .join();

                assertThat(new String(response.body(), StandardCharsets.UTF_8))
                        .contains("\"status\":\"approval-required\"");
                assertThat(dispatcher.continuationCount()).isEqualTo(1);
                response.discardUndeliveredOutcome();
                response.discardUndeliveredOutcome();
                assertThat(dispatcher.continuationCount()).isZero();
            }

            assertThat(invocations).hasValue(0);
            assertThat(dispatcher.activeRunCount()).isZero();
        }
    }

    @Test
    void runtime_shouldDiscardContinuationWhenFiniteOutcomeCannotBeEncoded() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        HostingRuntimeTestSupport.ScriptedChatClient transport =
                new HostingRuntimeTestSupport.ScriptedChatClient().enqueue(approvalResponse("oversized-approval"));
        HostingLimits limits = HostingLimits.builder().maxResponseBytes(256).build();
        HostingRegistry registry = new HostingRegistry();
        try (ChatAgent agent = new ChatAgent(
                        transport,
                        new AgentMetadata("approval-agent", "Approval agent", "Undelivered continuation test agent"),
                        ChatOptions.empty(),
                        List.of(approvalTool(invocations)));
                HostingDispatcher dispatcher = new HostingDispatcher(registry, limits);
                HostingHttpServer server = HostingHttpServer.start(
                        dispatcher,
                        HostingHttpServerOptions.builder().limits(limits).build());
                HttpClient client = HttpClient.newHttpClient()) {
            registry.registerAgent(agent);

            HttpResponse<String> response =
                    client.send(authorizedRun(server, "owner", "tenant-a"), HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(429);
            assertThat(response.body()).contains("\"code\":\"overflow\"");
            assertThat(dispatcher.continuationCount()).isZero();
            assertThat(dispatcher.activeRunCount()).isZero();
        }
    }

    @Test
    void runtime_shouldBindOneTimeFiniteAndSseResumeToPrincipalAndIsolation() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = approvalTool(invocations);
        HostingRuntimeTestSupport.ScriptedChatClient transport = new HostingRuntimeTestSupport.ScriptedChatClient()
                .enqueue(approvalResponse("approval-1"))
                .enqueue(doneResponse("done-1"))
                .enqueue(approvalResponse("approval-2"))
                .enqueue(doneResponse("done-2"));
        HostingRegistry registry = new HostingRegistry();
        HostingLimits limits = HostingLimits.defaults();
        HostingHttpServerOptions options = HostingHttpServerOptions.builder()
                .limits(limits)
                .authenticator(request -> {
                    String authorization = request.firstHeader("authorization");
                    if (authorization == null || !authorization.startsWith("Bearer ")) {
                        return CompletableFuture.completedFuture(HostingAuthentication.unauthenticated());
                    }
                    String principal = authorization.substring("Bearer ".length());
                    String isolation = request.firstHeader("x-isolation");
                    return CompletableFuture.completedFuture(HostingAuthentication.authenticated(
                            new HostingPrincipal(principal, isolation == null ? "default" : isolation)));
                })
                .trustedHeaderNames(Set.of("x-isolation"))
                .build();
        try (ChatAgent agent = new ChatAgent(
                        transport,
                        new AgentMetadata("approval-agent", "Approval agent", "HTTP resume test agent"),
                        ChatOptions.empty(),
                        List.of(tool));
                HostingDispatcher dispatcher = new HostingDispatcher(registry, limits);
                HostingHttpServer server = HostingHttpServer.start(dispatcher, options);
                HttpClient client = HttpClient.newHttpClient()) {
            registry.registerAgent(agent);
            HostingJsonCodec codec = new HostingJsonCodec(limits);

            HttpResponse<String> first =
                    client.send(authorizedRun(server, "owner", "tenant-a"), HttpResponse.BodyHandlers.ofString());
            Continuation firstContinuation = continuation(codec, first.body());
            HttpResponse<String> wrongPrincipal = client.send(
                    resume(server, firstContinuation, "other", "tenant-a", false),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> wrongIsolation = client.send(
                    resume(server, firstContinuation, "owner", "tenant-b", false),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> completed = client.send(
                    resume(server, firstContinuation, "owner", "tenant-a", false),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> replay = client.send(
                    resume(server, firstContinuation, "owner", "tenant-a", false),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(wrongPrincipal.statusCode()).as(wrongPrincipal.body()).isEqualTo(403);
            assertThat(wrongIsolation.statusCode()).as(wrongIsolation.body()).isEqualTo(403);
            assertThat(completed.statusCode()).isEqualTo(200);
            assertThat(completed.body()).contains("\"status\":\"completed\"", "done-1");

            HttpResponse<String> second =
                    client.send(authorizedRun(server, "owner", "tenant-a"), HttpResponse.BodyHandlers.ofString());
            assertThat(second.statusCode()).isEqualTo(200);
            assertThat(second.body()).contains("\"continuation\"");
            Continuation secondContinuation = continuation(codec, second.body());
            HttpResponse<String> streamed = client.send(
                    resume(server, secondContinuation, "owner", "tenant-a", true),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(first.statusCode()).isEqualTo(200);
            assertThat(first.body())
                    .contains("\"status\":\"approval-required\"", "\"oneTime\":true", "\"processLocal\":true");
            assertThat(wrongPrincipal.statusCode()).isEqualTo(403);
            assertThat(wrongPrincipal.body()).contains("\"code\":\"forbidden\"");
            assertThat(wrongIsolation.statusCode()).isEqualTo(403);
            assertThat(wrongIsolation.body()).contains("\"code\":\"forbidden\"");
            assertThat(completed.statusCode()).isEqualTo(200);
            assertThat(completed.body()).contains("\"status\":\"completed\"", "done-1");
            assertThat(replay.statusCode()).isEqualTo(409);
            assertThat(replay.body()).contains("\"code\":\"continuation_replayed\"");
            assertThat(streamed.statusCode()).isEqualTo(200);
            assertThat(streamed.headers().firstValue("content-type").orElseThrow())
                    .startsWith("text/event-stream");
            assertThat(streamed.body())
                    .contains("event: run-started", "event: terminal", "\"status\":\"completed\"", "done-2");
            assertThat(invocations).hasValue(2);
            assertThat(dispatcher.continuationCount()).isZero();
            assertThat(dispatcher.activeRunCount()).isZero();
        }
    }

    @Test
    void runtime_shouldResumeTypedWebSocketFrameWithoutWeakeningIdentityBinding() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        HostingRuntimeTestSupport.ScriptedChatClient transport = new HostingRuntimeTestSupport.ScriptedChatClient()
                .enqueue(approvalResponse("socket-approval"))
                .enqueue(doneResponse("socket-done"));
        HostingRegistry registry = new HostingRegistry();
        HostingLimits limits = HostingLimits.defaults();
        HostingHttpServerOptions options = HostingHttpServerOptions.builder()
                .limits(limits)
                .authenticator(request -> {
                    String authorization = request.firstHeader("authorization");
                    if (authorization == null || !authorization.startsWith("Bearer ")) {
                        return CompletableFuture.completedFuture(HostingAuthentication.unauthenticated());
                    }
                    return CompletableFuture.completedFuture(HostingAuthentication.authenticated(new HostingPrincipal(
                            authorization.substring("Bearer ".length()), request.firstHeader("x-isolation"))));
                })
                .trustedHeaderNames(Set.of("x-isolation"))
                .build();
        HttpClient client = HttpClient.newHttpClient();
        try (ChatAgent agent = new ChatAgent(
                        transport,
                        new AgentMetadata("approval-agent", "Approval agent", "WebSocket resume test agent"),
                        ChatOptions.empty(),
                        List.of(approvalTool(invocations)));
                HostingDispatcher dispatcher = new HostingDispatcher(registry, limits);
                HostingHttpServer server = HostingHttpServer.start(dispatcher, options)) {
            registry.registerAgent(agent);
            HostingJsonCodec codec = new HostingJsonCodec(limits);
            HttpResponse<String> suspended =
                    client.send(authorizedRun(server, "owner", "tenant-a"), HttpResponse.BodyHandlers.ofString());
            Continuation continuation = continuation(codec, suspended.body());

            ResumeListener deniedListener = new ResumeListener();
            WebSocket denied = openSocket(client, server, deniedListener, "other", "tenant-a");
            denied.sendText(resumeFrame(continuation), true).join();
            assertThat(deniedListener.nextMessage()).contains("\"type\":\"started\"");
            assertThat(deniedListener.nextMessage())
                    .contains("\"type\":\"terminal\"", "\"status\":\"failed\"", "\"code\":\"forbidden\"");
            denied.sendText(closeFrame(), true).join();
            assertThat(deniedListener.closeCode.orTimeout(5, TimeUnit.SECONDS).join())
                    .isEqualTo(1000);
            denied.abort();

            ResumeListener ownerListener = new ResumeListener();
            WebSocket owner = openSocket(client, server, ownerListener, "owner", "tenant-a");
            owner.sendText(resumeFrame(continuation), true).join();
            assertThat(ownerListener.nextMessage())
                    .contains("\"type\":\"started\"", "\"operationId\":\"resume-operation\"");
            assertThat(ownerListener.nextMessage())
                    .contains("\"type\":\"terminal\"", "\"status\":\"completed\"", "socket-done");
            owner.sendText(closeFrame(), true).join();
            assertThat(ownerListener.closeCode.orTimeout(5, TimeUnit.SECONDS).join())
                    .isEqualTo(1000);
            owner.abort();

            assertThat(invocations).hasValue(1);
            assertThat(dispatcher.continuationCount()).isZero();
        } finally {
            client.shutdownNow();
        }
    }

    private static HttpRequest authorizedRun(HostingHttpServer server, String principal, String isolation) {
        return HttpRequest.newBuilder(uri(server, "/v1/agents/approval-agent/runs"))
                .header("Authorization", "Bearer " + principal)
                .header("X-Isolation", isolation)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(runBody()))
                .build();
    }

    private static HostingHttpRequest localRunRequest() {
        return new HostingHttpRequest(
                "POST",
                URI.create("/v1/agents/approval-agent/runs"),
                new InetSocketAddress("127.0.0.1", 12345),
                Map.of(
                        "host",
                        List.of("localhost:8080"),
                        "content-type",
                        List.of("application/json"),
                        "accept",
                        List.of("application/json")),
                runBody().getBytes(StandardCharsets.UTF_8),
                new DefaultRunCancellation());
    }

    private static HttpRequest resume(
            HostingHttpServer server,
            Continuation continuation,
            String principal,
            String isolation,
            boolean streaming) {
        String suffix = streaming ? "/resume/stream" : "/resume";
        String body = """
                {
                  "version":"java-hosting-2026-08-01",
                  "token":"%s",
                  "type":"approval",
                  "decisions":[
                    {"approvalId":"%s","approved":true}
                  ]
                }
                """.formatted(continuation.token(), continuation.approvalId());
        return HttpRequest.newBuilder(uri(server, "/v1/agents/approval-agent/runs/" + continuation.runId() + suffix))
                .header("Authorization", "Bearer " + principal)
                .header("X-Isolation", isolation)
                .header("Content-Type", "application/json")
                .header("Accept", streaming ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static WebSocket openSocket(
            HttpClient client, HostingHttpServer server, ResumeListener listener, String principal, String isolation) {
        return client.newWebSocketBuilder()
                .header("Authorization", "Bearer " + principal)
                .header("X-Isolation", isolation)
                .subprotocols(HostingWebSocketProtocol.SUBPROTOCOL)
                .buildAsync(server.webSocketEndpoint(), listener)
                .orTimeout(5, TimeUnit.SECONDS)
                .join();
    }

    private static String resumeFrame(Continuation continuation) {
        return """
                {
                  "version":"java-hosting-2026-08-01",
                  "type":"resume",
                  "operationId":"resume-operation",
                  "kind":"agent",
                  "routeId":"approval-agent",
                  "runId":"%s",
                  "request":{
                    "version":"java-hosting-2026-08-01",
                    "token":"%s",
                    "type":"approval",
                    "decisions":[
                      {"approvalId":"%s","approved":true}
                    ]
                  }
                }
                """.formatted(continuation.runId(), continuation.token(), continuation.approvalId());
    }

    private static String closeFrame() {
        return """
                {
                  "version":"java-hosting-2026-08-01",
                  "type":"close"
                }
                """;
    }

    private static Continuation continuation(HostingJsonCodec codec, String json) {
        StateValue.ObjectValue root = codec.decodeObject(json.getBytes(StandardCharsets.UTF_8));
        String runId = string(root, "runId");
        StateValue.ObjectValue continuation = object(root.values().get("continuation"));
        StateValue.ArrayValue approvals =
                (StateValue.ArrayValue) continuation.values().get("approvalRequests");
        StateValue.ObjectValue approval = object(approvals.values().getFirst());
        return new Continuation(runId, string(continuation, "token"), string(approval, "approvalId"));
    }

    private static StateValue.ObjectValue object(StateValue value) {
        return (StateValue.ObjectValue) value;
    }

    private static String string(StateValue.ObjectValue value, String name) {
        return ((StateValue.StringValue) value.values().get(name)).value();
    }

    private static URI uri(HostingHttpServer server, String path) {
        URI endpoint = server.endpoint();
        return URI.create(endpoint.getScheme() + "://" + endpoint.getAuthority() + path);
    }

    private static String runBody() {
        return """
                {
                  "version":"java-hosting-2026-08-01",
                  "messages":[
                    {"role":"user","contents":[{"kind":"text","text":"write"}]}
                  ]
                }
                """;
    }

    private static FunctionTool approvalTool(AtomicInteger invocations) {
        ToolMetadata metadata = new ToolMetadata(
                "write",
                "Write a value",
                Set.of(ToolCapability.FUNCTION),
                ToolApprovalMode.ALWAYS_REQUIRE,
                StateValue.object(Map.of("type", StateValue.string("object"))),
                StateValue.object(Map.of()));
        return FunctionTool.create(metadata, (context, arguments) -> {
            invocations.incrementAndGet();
            return CompletableFuture.completedFuture(StateValue.string("written"));
        });
    }

    private static ChatResponse approvalResponse(String responseId) {
        FunctionCallContent call = new FunctionCallContent(
                "call-" + responseId, "write", StateValue.object(Map.of("value", StateValue.string("x"))));
        return response(new Message(Role.ASSISTANT, List.of(call)), responseId, FinishReason.TOOL_CALLS);
    }

    private static ChatResponse doneResponse(String responseId) {
        return response(Message.text(Role.ASSISTANT, responseId), responseId, FinishReason.STOP);
    }

    private static ChatResponse response(Message message, String responseId, FinishReason finishReason) {
        return new ChatResponse(
                List.of(message),
                responseId,
                "conversation",
                "test-model",
                Instant.parse("2026-08-09T00:00:00Z"),
                finishReason,
                null,
                null,
                Map.of(),
                List.of());
    }

    private record Continuation(String runId, String token, String approvalId) {}

    private static final class ResumeListener implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();

        private final StringBuilder partial = new StringBuilder();

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
            return WebSocket.Listener.super.onPing(webSocket, message);
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
