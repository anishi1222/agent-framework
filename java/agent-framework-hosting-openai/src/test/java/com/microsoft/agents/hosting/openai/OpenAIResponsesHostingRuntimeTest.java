// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.hosting.HostingAuthentication;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingJsonCodec;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingPrincipal;
import com.microsoft.agents.hosting.HostingRegistry;
import com.microsoft.agents.hosting.http.HostingHttpRequest;
import com.microsoft.agents.hosting.http.HostingHttpServerOptions;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OpenAIResponsesHostingRuntimeTest {
    @Test
    void loopbackServer_shouldServeFiniteResponseAndCompleteSseSequence() throws Exception {
        // Arrange
        EchoAgent agent = new EchoAgent();
        HostingLimits limits =
                HostingLimits.builder().idleTimeout(Duration.ofSeconds(2)).build();
        try (Runtime runtime = runtime(agent, limits);
                OpenAIResponsesHttpServer server = OpenAIResponsesHttpServer.start(runtime.handler);
                HttpClient client = HttpClient.newHttpClient()) {
            URI endpoint = resolve(server.endpoint(), OpenAIResponsesHostingRegistry.DEFAULT_PATH);

            // Act
            HttpResponse<String> finite = client.send(
                    request(endpoint, "{\"input\":\"hello\",\"model\":\"caller-model\"}", "application/json"),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<java.io.InputStream> stream = client.send(
                    request(endpoint, "{\"input\":\"hello\",\"stream\":true}", "text/event-stream"),
                    HttpResponse.BodyHandlers.ofInputStream());
            List<String> events = readEventNames(stream);

            // Assert
            assertThat(finite.statusCode()).isEqualTo(200);
            assertThat(finite.headers().firstValue("content-type").orElseThrow())
                    .startsWith("application/json");
            assertThat(finite.body())
                    .contains(
                            "\"object\":\"response\"",
                            "\"status\":\"completed\"",
                            "\"model\":\"caller-model\"",
                            "\"type\":\"output_text\"",
                            "reply:hello");
            assertThat(stream.statusCode()).isEqualTo(200);
            assertThat(stream.headers().firstValue("content-type").orElseThrow())
                    .startsWith("text/event-stream");
            assertThat(events)
                    .containsExactly(
                            "response.created",
                            "response.in_progress",
                            "response.output_item.added",
                            "response.content_part.added",
                            "response.output_text.delta",
                            "response.output_text.delta",
                            "response.output_text.done",
                            "response.content_part.done",
                            "response.output_item.done",
                            "response.completed");
            assertThat(runtime.dispatcher.activeRunCount()).isZero();
        }
    }

    @Test
    void handler_shouldMapMalformedOversizedUnsupportedAndExecutionErrors() throws Exception {
        // Arrange
        HostingLimits limits = HostingLimits.builder()
                .maxRequestBytes(256)
                .maxWebSocketFrameBytes(256)
                .build();
        try (Runtime runtime = runtime(new EchoAgent(), limits)) {
            HostingAuthentication identity = identity("alice", "tenant-a");

            // Act
            OpenAIResponsesHttpResponse malformed =
                    invoke(runtime.handler, "{\"input\":\"one\",\"input\":\"two\"}", "application/json", identity);
            OpenAIResponsesHttpResponse oversized =
                    invoke(runtime.handler, "{\"input\":\"" + "x".repeat(400) + "\"}", "application/json", identity);
            OpenAIResponsesHttpResponse unknown =
                    invoke(runtime.handler, "{\"input\":\"hello\",\"unsupported\":true}", "application/json", identity);
            OpenAIResponsesHttpResponse setting =
                    invoke(runtime.handler, "{\"input\":\"hello\",\"temperature\":0.5}", "application/json", identity);
            OpenAIResponsesHttpResponse execution =
                    invoke(runtime.handler, "{\"input\":\"fail\"}", "application/json", identity);

            // Assert
            assertThat(malformed.status()).isEqualTo(400);
            assertThat(body(malformed)).contains("\"code\":\"malformed_request\"");
            assertThat(oversized.status()).isEqualTo(413);
            assertThat(body(oversized)).contains("\"code\":\"payload_too_large\"");
            assertThat(unknown.status()).isEqualTo(400);
            assertThat(body(unknown)).contains("\"code\":\"unsupported_parameter\"", "\"param\":\"unsupported\"");
            assertThat(setting.status()).isEqualTo(400);
            assertThat(body(setting)).contains("\"code\":\"unsupported_parameter\"", "\"param\":\"temperature\"");
            assertThat(execution.status()).isEqualTo(500);
            assertThat(body(execution))
                    .contains("\"code\":\"internal_error\"", "\"type\":\"server_error\"")
                    .doesNotContain(EchoAgent.SECRET_FAILURE);
            assertThat(runtime.dispatcher.activeRunCount()).isZero();
        }
    }

    @Test
    void transportValidationFailures_shouldReturnErrorsBeforeRouteLookup() throws Exception {
        // Arrange
        try (Runtime runtime = runtime(new EchoAgent(), HostingLimits.defaults())) {
            HostingHttpRequest missingHost = new HostingHttpRequest(
                    "POST",
                    URI.create("http://localhost:80/not-registered"),
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 43210),
                    Map.of(
                            "accept", List.of("application/json"),
                            "content-type", List.of("application/json")),
                    "{\"input\":\"hello\"}".getBytes(StandardCharsets.UTF_8),
                    new DefaultRunCancellation());
            HostingHttpRequest ambiguousOrigin = new HostingHttpRequest(
                    "POST",
                    URI.create("http://localhost:80/v1/responses"),
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 43210),
                    Map.of(
                            "accept", List.of("application/json"),
                            "content-type", List.of("application/json"),
                            "host", List.of("localhost:80"),
                            "origin", List.of("https://one.example", "https://two.example")),
                    "{\"input\":\"hello\"}".getBytes(StandardCharsets.UTF_8),
                    new DefaultRunCancellation());

            // Act
            OpenAIResponsesHttpResponse missingHostResponse = runtime.handler
                    .handleAsync(missingHost)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            OpenAIResponsesHttpResponse ambiguousOriginResponse = runtime.handler
                    .handleAsync(ambiguousOrigin)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            // Assert
            assertThat(missingHostResponse.status()).isEqualTo(400);
            assertThat(ambiguousOriginResponse.status()).isEqualTo(400);
            assertThat(runtime.dispatcher.activeRunCount()).isZero();
        }
    }

    @Test
    void streamingFailure_shouldEmitSanitizedFailedTerminalEvent() throws Exception {
        // Arrange
        try (Runtime runtime = runtime(new FailingStreamingAgent(), HostingLimits.defaults())) {
            OpenAIResponsesHttpResponse response = invoke(
                    runtime.handler,
                    "{\"input\":\"hello\",\"stream\":true}",
                    "text/event-stream",
                    identity("alice", "tenant-a"));
            FrameCollector collector = new FrameCollector();

            // Act
            response.streamingRun().frames().subscribe(collector);
            collector.completion.get(5, TimeUnit.SECONDS);

            // Assert
            assertThat(collector.eventNames())
                    .containsExactly("response.created", "response.in_progress", "response.failed");
            assertThat(String.join("", collector.frames))
                    .contains("\"status\":\"failed\"", "\"code\":\"internal_error\"")
                    .doesNotContain(FailingStreamingAgent.SECRET_FAILURE);
            assertThat(runtime.dispatcher.activeRunCount()).isZero();
        }
    }

    @Test
    void refusalStream_shouldUseRefusalDeltaAndDoneEvents() throws Exception {
        // Arrange
        try (Runtime runtime = runtime(new RefusalStreamingAgent(), HostingLimits.defaults())) {
            OpenAIResponsesHttpResponse response = invoke(
                    runtime.handler,
                    "{\"input\":\"unsafe\",\"stream\":true}",
                    "text/event-stream",
                    identity("alice", "tenant-a"));
            FrameCollector collector = new FrameCollector();

            // Act
            response.streamingRun().frames().subscribe(collector);
            collector.completion.get(5, TimeUnit.SECONDS);

            // Assert
            assertThat(collector.eventNames())
                    .contains("response.refusal.delta", "response.refusal.done", "response.completed")
                    .doesNotContain("response.output_text.delta", "response.output_text.done");
            assertThat(String.join("", collector.frames))
                    .contains("\"type\":\"refusal\"", "\"refusal\":\"cannot comply\"");
        }
    }

    @Test
    void functionCallStream_shouldEmitCompatibleEventsAndApplyHostingRedaction() throws Exception {
        // Arrange
        try (Runtime runtime = runtime(new FunctionStreamingAgent(), HostingLimits.defaults())) {
            OpenAIResponsesHttpResponse response = invoke(
                    runtime.handler,
                    "{\"input\":\"call a tool\",\"stream\":true}",
                    "text/event-stream",
                    identity("alice", "tenant-a"));
            FrameCollector collector = new FrameCollector();

            // Act
            response.streamingRun().frames().subscribe(collector);
            collector.completion.get(5, TimeUnit.SECONDS);

            // Assert
            assertThat(collector.eventNames())
                    .containsExactly(
                            "response.created",
                            "response.in_progress",
                            "response.output_item.added",
                            "response.function_call_arguments.delta",
                            "response.function_call_arguments.done",
                            "response.output_item.done",
                            "response.completed");
            assertThat(String.join("", collector.frames))
                    .contains(
                            "\"name\":\"submit_secret\"",
                            "\\\"city\\\":\\\"Seattle\\\"",
                            "\\\"password\\\":\\\"[REDACTED]\\\"")
                    .doesNotContain("tool-value");
        }
    }

    @Test
    void explicitCancellation_shouldEmitCancelledAndReleaseConversation() throws Exception {
        // Arrange
        PendingStreamingAgent agent = new PendingStreamingAgent();
        HostingLimits limits = HostingLimits.builder()
                .idleTimeout(Duration.ofSeconds(2))
                .runTimeout(Duration.ofSeconds(5))
                .build();
        try (Runtime runtime = runtime(agent, limits)) {
            HostingAuthentication identity = identity("alice", "tenant-a");
            OpenAIResponsesHttpResponse response = invoke(runtime.handler, """
                    {
                      "input": "wait",
                      "stream": true,
                      "conversation": "conversation-cancel"
                    }
                    """, "text/event-stream", identity);
            FrameCollector collector = new FrameCollector();
            response.streamingRun().frames().subscribe(collector);
            collector.firstFrame.get(5, TimeUnit.SECONDS);

            // Act
            assertThat(response.streamingRun().cancel()).isTrue();
            collector.completion.get(5, TimeUnit.SECONDS);
            OpenAIResponsesHttpResponse subsequent = invoke(runtime.handler, """
                    {
                      "input": "after cancellation",
                      "conversation": "conversation-cancel"
                    }
                    """, "application/json", identity);

            // Assert
            assertThat(agent.cancelled.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(collector.eventNames()).endsWith("response.cancelled");
            assertThat(subsequent.status()).isEqualTo(200);
            assertThat(body(subsequent)).contains("after cancellation");
            assertThat(runtime.dispatcher.activeRunCount()).isZero();
        }
    }

    @Test
    void loopbackDisconnect_shouldCancelPendingAgentRun() throws Exception {
        // Arrange
        PendingStreamingAgent agent = new PendingStreamingAgent();
        HostingLimits limits = HostingLimits.builder()
                .idleTimeout(Duration.ofMillis(250))
                .runTimeout(Duration.ofSeconds(5))
                .build();
        try (Runtime runtime = runtime(agent, limits);
                OpenAIResponsesHttpServer server = OpenAIResponsesHttpServer.start(runtime.handler);
                HttpClient client = HttpClient.newHttpClient()) {
            URI endpoint = resolve(server.endpoint(), OpenAIResponsesHostingRegistry.DEFAULT_PATH);
            HttpResponse<java.io.InputStream> response = client.send(
                    request(endpoint, "{\"input\":\"wait\",\"stream\":true}", "text/event-stream"),
                    HttpResponse.BodyHandlers.ofInputStream());
            BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
            assertThat(reader.readLine()).isEqualTo("event: response.created");

            // Act
            response.body().close();

            // Assert
            assertThat(agent.cancelled.get(5, TimeUnit.SECONDS)).isTrue();
            awaitNoActiveRuns(runtime.dispatcher);
        }
    }

    @Test
    void conversationsAndPreviousResponses_shouldRemainPrincipalAndTenantIsolated() throws Exception {
        // Arrange
        RecordingAgent agent = new RecordingAgent();
        try (Runtime runtime = runtime(agent, HostingLimits.defaults())) {
            HostingAuthentication aliceTenantA = identity("alice", "tenant-a");
            HostingAuthentication bobTenantA = identity("bob", "tenant-a");
            HostingAuthentication aliceTenantB = identity("alice", "tenant-b");

            // Act
            OpenAIResponsesHttpResponse first = invoke(
                    runtime.handler,
                    "{\"input\":\"one\",\"conversation\":\"shared\"}",
                    "application/json",
                    aliceTenantA);
            String firstId = responseId(first, runtime.limits);
            OpenAIResponsesHttpResponse second = invoke(
                    runtime.handler,
                    "{\"input\":\"two\",\"conversation\":\"shared\"}",
                    "application/json",
                    aliceTenantA);
            invoke(runtime.handler, "{\"input\":\"bob\",\"conversation\":\"shared\"}", "application/json", bobTenantA);
            invoke(
                    runtime.handler,
                    "{\"input\":\"other tenant\",\"conversation\":\"shared\"}",
                    "application/json",
                    aliceTenantB);
            OpenAIResponsesHttpResponse forbiddenReference =
                    invoke(runtime.handler, """
                    {"input":"cross principal","previous_response_id":"%s"}
                    """.formatted(firstId), "application/json", bobTenantA);
            OpenAIResponsesHttpResponse branch =
                    invoke(runtime.handler, """
                    {"input":"branch","previous_response_id":"%s"}
                    """.formatted(firstId), "application/json", aliceTenantA);

            // Assert
            assertThat(first.status()).isEqualTo(200);
            assertThat(second.status()).isEqualTo(200);
            assertThat(forbiddenReference.status()).isEqualTo(404);
            assertThat(branch.status()).isEqualTo(200);
            assertThat(agent.invocations)
                    .containsExactly(
                            List.of("one"),
                            List.of("one", "reply:one", "two"),
                            List.of("bob"),
                            List.of("other tenant"),
                            List.of("one", "reply:one", "branch"));
            assertThat(runtime.dispatcher.activeRunCount()).isZero();
        }
    }

    @Test
    void instructions_shouldApplyOnlyToTheCurrentResponse() throws Exception {
        // Arrange
        RecordingAgent agent = new RecordingAgent();
        OpenAIResponsesHostingOptions options = OpenAIResponsesHostingOptions.builder()
                .runOptionsMapper(ignored -> RunOptions.empty())
                .build();
        try (Runtime runtime = runtime(agent, HostingLimits.defaults(), options)) {
            HostingAuthentication identity = identity("alice", "tenant-a");

            // Act
            invoke(runtime.handler, """
                    {
                      "input": "one",
                      "instructions": "temporary instruction",
                      "conversation": "instructions"
                    }
                    """, "application/json", identity);
            invoke(runtime.handler, """
                    {
                      "input": "two",
                      "conversation": "instructions"
                    }
                    """, "application/json", identity);

            // Assert
            assertThat(agent.invocations)
                    .containsExactly(List.of("temporary instruction", "one"), List.of("one", "reply:one", "two"));
        }
    }

    @Test
    void finitePersistenceFailure_shouldReleaseMutableConversation() throws Exception {
        // Arrange
        RecordingAgent agent = new RecordingAgent();
        OpenAIResponsesHostingOptions options = OpenAIResponsesHostingOptions.builder()
                .maxConversationEntries(1)
                .build();
        try (Runtime runtime = runtime(agent, HostingLimits.defaults(), options)) {
            HostingAuthentication identity = identity("alice", "tenant-a");

            // Act
            OpenAIResponsesHttpResponse first = invoke(
                    runtime.handler, "{\"input\":\"one\",\"conversation\":\"capacity\"}", "application/json", identity);
            OpenAIResponsesHttpResponse second = invoke(
                    runtime.handler, "{\"input\":\"two\",\"conversation\":\"capacity\"}", "application/json", identity);

            // Assert
            assertThat(first.status()).isEqualTo(429);
            assertThat(second.status()).isEqualTo(429);
            assertThat(agent.invocations).containsExactly(List.of("one"), List.of("two"));
        }
    }

    @Test
    void streamingPersistenceFailure_shouldReleaseMutableConversation() throws Exception {
        // Arrange
        OpenAIResponsesHostingOptions options = OpenAIResponsesHostingOptions.builder()
                .maxConversationEntries(1)
                .build();
        try (Runtime runtime = runtime(new EchoAgent(), HostingLimits.defaults(), options)) {
            HostingAuthentication identity = identity("alice", "tenant-a");

            // Act
            OpenAIResponsesHttpResponse first = invoke(runtime.handler, """
                    {
                      "input": "one",
                      "stream": true,
                      "conversation": "capacity"
                    }
                    """, "text/event-stream", identity);
            FrameCollector firstFrames = new FrameCollector();
            first.streamingRun().frames().subscribe(firstFrames);
            firstFrames.completion.get(5, TimeUnit.SECONDS);

            OpenAIResponsesHttpResponse second = invoke(runtime.handler, """
                    {
                      "input": "two",
                      "stream": true,
                      "conversation": "capacity"
                    }
                    """, "text/event-stream", identity);
            FrameCollector secondFrames = new FrameCollector();
            second.streamingRun().frames().subscribe(secondFrames);
            secondFrames.completion.get(5, TimeUnit.SECONDS);

            // Assert
            assertThat(firstFrames.eventNames()).endsWith("response.failed");
            assertThat(secondFrames.eventNames()).endsWith("response.failed");
            assertThat(runtime.dispatcher.activeRunCount()).isZero();
        }
    }

    private static Runtime runtime(Agent<?> agent, HostingLimits limits) {
        return runtime(agent, limits, OpenAIResponsesHostingOptions.defaults());
    }

    private static Runtime runtime(Agent<?> agent, HostingLimits limits, OpenAIResponsesHostingOptions options) {
        HostingRegistry generic = new HostingRegistry();
        OpenAIResponsesHostingRegistry routes = new OpenAIResponsesHostingRegistry(generic);
        routes.registerAgent(OpenAIResponsesHostingRegistry.DEFAULT_PATH, agent);
        HostingDispatcher dispatcher = new HostingDispatcher(generic, limits);
        HostingHttpServerOptions httpOptions =
                HostingHttpServerOptions.builder().limits(limits).build();
        OpenAIResponsesHttpHandler handler = new OpenAIResponsesHttpHandler(dispatcher, routes, httpOptions, options);
        return new Runtime(agent, dispatcher, handler, limits);
    }

    private static OpenAIResponsesHttpResponse invoke(
            OpenAIResponsesHttpHandler handler, String requestBody, String accept, HostingAuthentication identity)
            throws Exception {
        return handler.handleAuthenticatedAsync(hostingRequest(requestBody, accept), identity)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
    }

    private static HostingHttpRequest hostingRequest(String body, String accept) throws Exception {
        return new HostingHttpRequest(
                "POST",
                URI.create("http://localhost:80/v1/responses"),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 43210),
                Map.of(
                        "accept", List.of(accept),
                        "content-type", List.of("application/json"),
                        "host", List.of("localhost:80")),
                body.getBytes(StandardCharsets.UTF_8),
                new DefaultRunCancellation());
    }

    private static HostingAuthentication identity(String principalId, String isolationId) {
        return HostingAuthentication.authenticated(new HostingPrincipal(principalId, isolationId));
    }

    private static HttpRequest request(URI endpoint, String body, String accept) {
        return HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static URI resolve(URI origin, String path) {
        return URI.create(origin.getScheme() + "://" + origin.getAuthority() + path);
    }

    private static List<String> readEventNames(HttpResponse<java.io.InputStream> response) throws Exception {
        ArrayList<String> events = new ArrayList<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: ")) {
                    events.add(line.substring("event: ".length()));
                }
            }
        }
        return List.copyOf(events);
    }

    private static String responseId(OpenAIResponsesHttpResponse response, HostingLimits limits) {
        HostingJsonCodec json = new HostingJsonCodec(limits);
        StateValue.ObjectValue value = json.decodeObject(response.body());
        return ((StateValue.StringValue) value.values().get("id")).value();
    }

    private static String body(OpenAIResponsesHttpResponse response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static void awaitNoActiveRuns(HostingDispatcher dispatcher) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (dispatcher.activeRunCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(dispatcher.activeRunCount()).isZero();
    }

    private static AgentResponseUpdate update(long sequence, String text) {
        return AgentResponseUpdate.builder()
                .sequence(sequence)
                .role(Role.ASSISTANT)
                .contents(List.of(new TextContent(text)))
                .build();
    }

    private static final class Runtime implements AutoCloseable {
        private final Agent<?> agent;

        private final HostingDispatcher dispatcher;

        private final OpenAIResponsesHttpHandler handler;

        private final HostingLimits limits;

        private Runtime(
                Agent<?> agent,
                HostingDispatcher dispatcher,
                OpenAIResponsesHttpHandler handler,
                HostingLimits limits) {
            this.agent = agent;
            this.dispatcher = dispatcher;
            this.handler = handler;
            this.limits = limits;
        }

        @Override
        public void close() {
            handler.close();
            dispatcher.close();
            agent.close();
        }
    }

    private static class EchoAgent implements Agent<Void> {
        private static final String SECRET_FAILURE = "provider-secret-should-not-cross-boundary";

        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata("echo", "Echo", "OpenAI hosting test agent");
        }

        @Override
        public RunHandle<AgentResponse<Void>> startRun(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            RunHandleSource<AgentResponse<Void>> source = new RunHandleSource<>(cancellation);
            String input = messages.getLast().text();
            if ("fail".equals(input)) {
                source.tryFail(new IllegalStateException(SECRET_FAILURE));
            } else {
                source.tryComplete(AgentResponse.<Void>builder()
                        .messages(List.of(Message.text(Role.ASSISTANT, "reply:" + input)))
                        .build());
            }
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
                            subscriber.onNext(update(0, "hello "));
                        } else if (next == 1) {
                            subscriber.onNext(update(1, "world"));
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
    }

    private static final class FailingStreamingAgent extends EchoAgent {
        private static final String SECRET_FAILURE = "stream-provider-secret-should-not-cross-boundary";

        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata("stream-failure", "Stream failure", "Failing stream test agent");
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicBoolean done = new AtomicBoolean();

                @Override
                public void request(long count) {
                    if (count > 0 && done.compareAndSet(false, true)) {
                        subscriber.onError(new IllegalStateException(SECRET_FAILURE));
                    }
                }

                @Override
                public void cancel() {
                    done.set(true);
                }
            });
        }
    }

    private static final class RefusalStreamingAgent extends EchoAgent {
        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata("refusal", "Refusal", "Refusal stream test agent");
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
                                        "cannot comply", Map.of("openai.refusal", StateValue.bool(true)))))
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

    private static final class FunctionStreamingAgent extends EchoAgent {
        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata("function", "Function", "Function stream test agent");
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
                                .contents(List.of(new FunctionCallContent(
                                        "call-1",
                                        "submit_secret",
                                        StateValue.object(Map.of(
                                                "city",
                                                StateValue.string("Seattle"),
                                                "password",
                                                StateValue.string("tool-value"))),
                                        true,
                                        Map.of())))
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

    private static final class PendingStreamingAgent extends EchoAgent {
        private final CompletableFuture<Boolean> cancelled = new CompletableFuture<>();

        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata("pending", "Pending", "Pending stream test agent");
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            RunCancellations.register(cancellation, () -> cancelled.complete(true));
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {
                    // Hold until the caller cancels or disconnects.
                }

                @Override
                public void cancel() {
                    cancelled.complete(true);
                }
            });
        }
    }

    private static final class RecordingAgent extends EchoAgent {
        private final List<List<String>> invocations = new CopyOnWriteArrayList<>();

        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata("recording", "Recording", "Conversation isolation test agent");
        }

        @Override
        public RunHandle<AgentResponse<Void>> startRun(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            invocations.add(messages.stream().map(Message::text).toList());
            return super.startRun(messages, options, cancellation);
        }
    }

    private static final class FrameCollector implements Flow.Subscriber<byte[]> {
        private final List<String> frames = new CopyOnWriteArrayList<>();

        private final CompletableFuture<String> firstFrame = new CompletableFuture<>();

        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(byte[] item) {
            String frame = new String(item, StandardCharsets.UTF_8);
            frames.add(frame);
            firstFrame.complete(frame);
        }

        @Override
        public void onError(Throwable throwable) {
            completion.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            completion.complete(null);
        }

        private List<String> eventNames() {
            return frames.stream()
                    .map(frame -> frame.lines()
                            .filter(line -> line.startsWith("event: "))
                            .findFirst()
                            .orElseThrow()
                            .substring("event: ".length()))
                    .toList();
        }
    }
}
