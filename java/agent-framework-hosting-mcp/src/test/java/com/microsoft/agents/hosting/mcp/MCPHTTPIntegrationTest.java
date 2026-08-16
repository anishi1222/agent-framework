// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentContinuation;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.ApprovalRequiredException;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.UriContent;
import com.microsoft.agents.protocols.mcp.MCPClient;
import com.microsoft.agents.protocols.mcp.MCPClientEvent;
import com.microsoft.agents.protocols.mcp.MCPClientOptions;
import com.microsoft.agents.protocols.mcp.MCPContent;
import com.microsoft.agents.protocols.mcp.MCPException;
import com.microsoft.agents.protocols.mcp.MCPInitialization;
import com.microsoft.agents.protocols.mcp.MCPLimits;
import com.microsoft.agents.protocols.mcp.MCPPromptArgument;
import com.microsoft.agents.protocols.mcp.MCPPromptMessage;
import com.microsoft.agents.protocols.mcp.MCPPromptResult;
import com.microsoft.agents.protocols.mcp.MCPProtocolException;
import com.microsoft.agents.protocols.mcp.MCPReadResourceResult;
import com.microsoft.agents.protocols.mcp.MCPResourceContents;
import com.microsoft.agents.protocols.mcp.MCPResourceDescriptor;
import com.microsoft.agents.protocols.mcp.MCPRole;
import com.microsoft.agents.protocols.mcp.MCPStreamableHTTPTransport;
import com.microsoft.agents.protocols.mcp.MCPToolCallOptions;
import com.microsoft.agents.protocols.mcp.MCPToolResult;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.InvocationId;
import com.microsoft.agents.tools.ToolApprovalId;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolApprovalRequest;
import com.microsoft.agents.tools.ToolApprovalState;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MCPHTTPIntegrationTest {
    @Test
    void roundTripsToolsPromptsResourcesRichContentAndProgress() throws Exception {
        // Arrange
        MCPServer definition = MCPServer.builder("java-mcp-test", "1.0.0")
                .instructions("Use focused tools and inspect structured output.")
                .tool(echoTool(ToolApprovalMode.NEVER_REQUIRE))
                .agent(MCPAgentTool.builder(new RichAgent()).name("rich_agent").build())
                .prompt(richPrompt())
                .resource(textResource())
                .build();
        try (MCPStreamableHTTPServer host = definition.startStreamableHTTP(
                        MCPStreamableHTTPServerOptions.builder().build());
                MCPClient client = httpClient(host.endpoint())) {
            RecordingEventSubscriber events = new RecordingEventSubscriber(2);
            client.events().subscribe(events);

            // Act
            MCPInitialization initialization =
                    client.initializeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            var tools = client.listToolsAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            MCPToolResult echo = client.callToolAsync(
                            "echo",
                            StateValue.object(Map.of("text", StateValue.string("hello"))),
                            MCPToolCallOptions.builder(Duration.ofSeconds(5))
                                    .progressToken(StateValue.string("progress-1"))
                                    .build())
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            MCPToolResult rich = client.callToolAsync(
                            "rich_agent", StateValue.object(Map.of("task", StateValue.string("show content"))))
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            var prompts = client.listPromptsAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            MCPPromptResult prompt = client.getPromptAsync("rich_prompt", Map.of("topic", "MCP"))
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            var resources = client.listResourcesAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            MCPReadResourceResult resource = client.readResourceAsync(URI.create("test://docs/readme"))
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);

            // Assert
            assertThat(initialization.capabilities().tools()).isTrue();
            assertThat(initialization.capabilities().prompts()).isTrue();
            assertThat(initialization.capabilities().resources()).isTrue();
            assertThat(tools).extracting(tool -> tool.name()).containsExactly("echo", "rich_agent");
            assertThat(echo.error()).isFalse();
            assertThat(((StateValue.ObjectValue) echo.structuredContent())
                            .values()
                            .get("echoed"))
                    .isEqualTo(StateValue.string("hello"));
            assertThat(events.latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(events.events)
                    .filteredOn(MCPClientEvent.Progress.class::isInstance)
                    .hasSize(2);
            assertThat(rich.content())
                    .anyMatch(MCPContent.Text.class::isInstance)
                    .anyMatch(MCPContent.Image.class::isInstance)
                    .anyMatch(MCPContent.Audio.class::isInstance)
                    .anyMatch(MCPContent.ResourceLink.class::isInstance);
            assertThat(prompts).extracting(value -> value.name()).containsExactly("rich_prompt");
            assertThat(prompt.messages())
                    .extracting(MCPPromptMessage::content)
                    .anyMatch(MCPContent.Text.class::isInstance)
                    .anyMatch(MCPContent.Image.class::isInstance)
                    .anyMatch(MCPContent.Audio.class::isInstance)
                    .anyMatch(MCPContent.EmbeddedResource.class::isInstance)
                    .anyMatch(MCPContent.ResourceLink.class::isInstance);
            assertThat(resources).extracting(value -> value.uri()).containsExactly(URI.create("test://docs/readme"));
            assertThat(resource.contents().get(0)).isInstanceOf(MCPResourceContents.Text.class);
        }
    }

    @Test
    void reportsProtocolAndToolErrorsWithoutSuccessShapedFallbacks() throws Exception {
        // Arrange
        MCPServer definition = MCPServer.builder("errors", "1.0.0")
                .tool(echoTool(ToolApprovalMode.ALWAYS_REQUIRE))
                .agent(MCPAgentTool.builder(new InputRequiredAgent())
                        .name("input_agent")
                        .build())
                .build();
        try (MCPStreamableHTTPServer host = definition.startStreamableHTTP(
                        MCPStreamableHTTPServerOptions.builder().build());
                MCPClient client = httpClient(host.endpoint())) {
            // Act
            MCPToolResult approval = client.callToolAsync(
                            "echo", StateValue.object(Map.of("text", StateValue.string("hello"))))
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            MCPToolResult inputRequired = client.callToolAsync(
                            "input_agent", StateValue.object(Map.of("task", StateValue.string("continue"))))
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);

            // Assert
            assertThat(approval.error()).isTrue();
            assertThat(approval.text()).contains("requires explicit approval");
            assertThat(inputRequired.error()).isTrue();
            assertThat(inputRequired.text()).contains("requires approval or additional input");
            assertThatThrownBy(() -> client.callToolAsync("missing", StateValue.object(Map.of()))
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS))
                    .cause()
                    .isInstanceOf(MCPProtocolException.class);
        }
    }

    @Test
    void enforcesClientCancellationTimeoutAndServerConcurrencyBounds() throws Exception {
        // Arrange
        MCPLimits limits = new MCPLimits(1_048_576, 32, 10_000, 100, 1, 16);
        Semaphore entered = new Semaphore(0);
        Semaphore completed = new Semaphore(0);
        FunctionTool blocking = blockingTool(entered, completed);
        MCPServer definition = MCPServer.builder("bounds", "1.0.0")
                .limits(limits)
                .callTimeout(Duration.ofSeconds(2))
                .tool(blocking)
                .build();
        MCPStreamableHTTPServerOptions serverOptions =
                MCPStreamableHTTPServerOptions.builder().limits(limits).build();
        try (MCPStreamableHTTPServer host = definition.startStreamableHTTP(serverOptions);
                MCPClient firstClient = httpClient(host.endpoint());
                MCPClient secondClient = httpClient(host.endpoint())) {
            StateValue.ObjectValue arguments = StateValue.object(Map.of());
            DefaultRunCancellation cancellation = new DefaultRunCancellation();
            var cancelled = firstClient.callToolAsync(
                    "blocking",
                    arguments,
                    MCPToolCallOptions.builder(Duration.ofSeconds(5))
                            .cancellation(cancellation)
                            .build());
            assertThat(entered.tryAcquire(5, TimeUnit.SECONDS)).isTrue();

            // Act / Assert
            MCPToolResult limited = secondClient
                    .callToolAsync("blocking", arguments)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertThat(limited.error()).isTrue();
            assertThat(limited.text()).contains("concurrent call limit");

            cancellation.cancel();
            assertThatThrownBy(() -> cancelled.toCompletableFuture().get(5, TimeUnit.SECONDS))
                    .cause()
                    .isInstanceOf(com.microsoft.agents.core.RunCancelledException.class);

            assertThat(completed.tryAcquire(5, TimeUnit.SECONDS)).isTrue();
            var timedOut = secondClient.callToolAsync(
                    "blocking",
                    arguments,
                    MCPToolCallOptions.builder(Duration.ofMillis(75)).build());
            assertThat(entered.tryAcquire(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> timedOut.toCompletableFuture().get(5, TimeUnit.SECONDS))
                    .cause()
                    .isInstanceOf(MCPException.class)
                    .hasMessageContaining("timed out");
        }
    }

    @Test
    void rejectsOversizedPayloadsAndInvalidHostHeaders() throws Exception {
        // Arrange
        MCPLimits limits = new MCPLimits(1024, 16, 100, 10, 4, 8);
        MCPServer definition =
                MCPServer.builder("security", "1.0.0").limits(limits).build();
        MCPStreamableHTTPServerOptions options =
                MCPStreamableHTTPServerOptions.builder().limits(limits).build();
        try (MCPStreamableHTTPServer host = definition.startStreamableHTTP(options)) {
            HttpRequest request = HttpRequest.newBuilder(host.endpoint())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("x".repeat(1025)))
                    .build();

            // Act
            HttpResponse<String> response =
                    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            String statusLine;
            try (Socket socket =
                    new Socket(host.endpoint().getHost(), host.endpoint().getPort())) {
                socket.setSoTimeout(5000);
                String raw = "POST "
                        + host.endpoint().getPath()
                        + " HTTP/1.1\r\nHost: evil.example\r\nContent-Length: 0\r\n"
                        + "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n\r\n";
                socket.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                statusLine = new java.io.BufferedReader(
                                new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                        .readLine();
            }

            // Assert
            assertThat(response.statusCode()).isEqualTo(413);
            assertThat(statusLine).contains("421");
        }
    }

    private static MCPClient httpClient(URI endpoint) {
        return MCPClient.create(
                MCPStreamableHTTPTransport.builder(endpoint)
                        .allowInsecureLoopback(true)
                        .allowedHosts(Set.of(endpoint.getHost()))
                        .build(),
                MCPClientOptions.builder().requestTimeout(Duration.ofSeconds(3)).build());
    }

    private static FunctionTool echoTool(ToolApprovalMode approvalMode) {
        StateValue.ObjectValue input = StateValue.object(Map.of(
                "type",
                StateValue.string("object"),
                "properties",
                StateValue.object(Map.of("text", StateValue.object(Map.of("type", StateValue.string("string"))))),
                "required",
                StateValue.array(List.of(StateValue.string("text"))),
                "additionalProperties",
                StateValue.bool(false)));
        StateValue.ObjectValue output = StateValue.object(Map.of(
                "type",
                StateValue.string("object"),
                "properties",
                StateValue.object(Map.of("echoed", StateValue.object(Map.of("type", StateValue.string("string"))))),
                "required",
                StateValue.array(List.of(StateValue.string("echoed"))),
                "additionalProperties",
                StateValue.bool(false)));
        return FunctionTool.create(
                new ToolMetadata(
                        "echo", "Echoes one text value.", Set.of(ToolCapability.FUNCTION), approvalMode, input, output),
                (context, arguments) -> CompletableFuture.completedFuture(
                        StateValue.object(Map.of("echoed", arguments.values().get("text")))));
    }

    private static FunctionTool blockingTool(Semaphore entered, Semaphore completed) {
        return FunctionTool.create(
                new ToolMetadata(
                        "blocking",
                        "Waits until the server deadline.",
                        Set.of(ToolCapability.FUNCTION),
                        ToolApprovalMode.NEVER_REQUIRE,
                        StateValue.object(Map.of(
                                "type", StateValue.string("object"), "additionalProperties", StateValue.bool(false))),
                        StateValue.object(Map.of())),
                (context, arguments) -> {
                    CompletableFuture<StateValue> result = new CompletableFuture<>();
                    entered.release();
                    context.cancellation()
                            .cancelledAsync()
                            .whenComplete((ignored, failure) -> result.completeExceptionally(
                                    new com.microsoft.agents.core.RunCancelledException()));
                    result.whenComplete((ignored, failure) -> completed.release());
                    return result;
                });
    }

    private static MCPServerPrompt richPrompt() {
        List<MCPPromptMessage> messages = List.of(
                new MCPPromptMessage(MCPRole.USER, new MCPContent.Text("Prompt text")),
                new MCPPromptMessage(
                        MCPRole.ASSISTANT,
                        new MCPContent.Image("image".getBytes(StandardCharsets.UTF_8), "image/png", Map.of())),
                new MCPPromptMessage(
                        MCPRole.ASSISTANT,
                        new MCPContent.Audio("audio".getBytes(StandardCharsets.UTF_8), "audio/wav", Map.of())),
                new MCPPromptMessage(
                        MCPRole.ASSISTANT,
                        new MCPContent.EmbeddedResource(
                                new MCPResourceContents.Text(
                                        URI.create("test://embedded/text"), "text/plain", "embedded", Map.of()),
                                Map.of())),
                new MCPPromptMessage(
                        MCPRole.ASSISTANT,
                        new MCPContent.ResourceLink(
                                URI.create("https://example.test/doc"),
                                "doc",
                                "Document",
                                "A linked document.",
                                "text/plain",
                                10L,
                                Map.of())));
        return new MCPServerPrompt(
                "rich_prompt",
                "Returns every supported prompt content kind.",
                List.of(new MCPPromptArgument("topic", "Topic to discuss.", true)),
                arguments -> CompletableFuture.completedFuture(new MCPPromptResult("Rich prompt", messages, Map.of())));
    }

    private static MCPServerResource textResource() {
        MCPResourceDescriptor descriptor = new MCPResourceDescriptor(
                URI.create("test://docs/readme"),
                "readme",
                "README",
                "Test documentation.",
                "text/plain",
                null,
                Map.of());
        return new MCPServerResource(
                descriptor,
                uri -> CompletableFuture.completedFuture(new MCPReadResourceResult(
                        List.of(new MCPResourceContents.Text(uri, "text/plain", "resource text", Map.of())),
                        Map.of())));
    }

    private static class RichAgent implements Agent<StateValue> {
        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata("rich-agent", "Rich Agent", "Returns rich content.");
        }

        @Override
        public RunHandle<AgentResponse<StateValue>> startRun(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            RunHandleSource<AgentResponse<StateValue>> source = new RunHandleSource<>(cancellation);
            Message response = new Message(
                    Role.ASSISTANT,
                    List.of(
                            new TextContent("agent text"),
                            new DataContent("image".getBytes(StandardCharsets.UTF_8), "image/png"),
                            new DataContent("audio".getBytes(StandardCharsets.UTF_8), "audio/wav"),
                            new UriContent(URI.create("https://example.test/agent"), "text/plain")));
            source.tryComplete(AgentResponse.<StateValue>builder()
                    .messages(List.of(response))
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
                public void cancel() {
                    cancellation.cancel();
                }
            });
        }
    }

    private static final class InputRequiredAgent extends RichAgent {
        @Override
        public RunHandle<AgentResponse<StateValue>> startRun(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            ToolApprovalRequest request = new ToolApprovalRequest(
                    new ToolApprovalId("approval-1"),
                    "run-approval",
                    new InvocationId("invocation-approval"),
                    "call-approval",
                    "sensitive",
                    "schema",
                    "arguments",
                    "request",
                    StateValue.object(Map.of()),
                    ToolApprovalState.PENDING);
            ApprovalRequiredException failure = new ApprovalRequiredException(
                    new AgentContinuation("continuation-1", null, "run-approval", List.of(request), false, false),
                    List.of());
            RunHandleSource<AgentResponse<StateValue>> source = new RunHandleSource<>(cancellation);
            source.tryFail(failure);
            return source.handle();
        }
    }

    private static final class RecordingEventSubscriber implements Flow.Subscriber<MCPClientEvent> {
        private final List<MCPClientEvent> events = new ArrayList<>();

        private final CountDownLatch latch;

        private RecordingEventSubscriber(int expected) {
            latch = new CountDownLatch(expected);
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(MCPClientEvent item) {
            events.add(item);
            latch.countDown();
        }

        @Override
        public void onError(Throwable throwable) {}

        @Override
        public void onComplete() {}
    }
}
