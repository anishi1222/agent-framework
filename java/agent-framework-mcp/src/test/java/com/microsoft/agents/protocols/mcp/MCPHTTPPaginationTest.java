// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.InvocationId;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolInvocationContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class MCPHTTPPaginationTest {
    @Test
    void followsPaginationNormalizesDuplicateNamesAndPreservesCallCorrelation() throws Exception {
        // Arrange
        MCPClientOptions options = MCPClientOptions.builder()
                .remoteToolApprovalMode(ToolApprovalMode.NEVER_REQUIRE)
                .requestTimeout(Duration.ofSeconds(5))
                .build();
        try (RawPaginationServer server = RawPaginationServer.start();
                MCPClient client = MCPClient.create(
                        MCPStreamableHTTPTransport.builder(server.endpoint())
                                .allowInsecureLoopback(true)
                                .allowedHosts(Set.of("127.0.0.1"))
                                .build(),
                        options)) {
            // Act
            MCPInitialization initialization =
                    client.initializeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            List<MCPToolDescriptor> descriptors =
                    client.listToolsAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            var tools = client.asFunctionToolsAsync("svc").toCompletableFuture().get(10, TimeUnit.SECONDS);
            StateValue result = tools.get(0)
                    .invokeAsync(
                            new ToolInvocationContext(
                                    "run-1",
                                    "call-123",
                                    new InvocationId("invocation-123"),
                                    new DefaultRunCancellation(),
                                    Runnable::run,
                                    Map.of()),
                            StateValue.object(Map.of("value", StateValue.string("hello"))))
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);

            // Assert
            assertThat(initialization.serverName()).isEqualTo("pagination-test");
            assertThat(descriptors).extracting(MCPToolDescriptor::name).containsExactly("a b", "a/b", "a_b_2", "final");
            assertThat(tools)
                    .extracting(tool -> tool.name())
                    .containsExactly("svc_a_b", "svc_a_b_2", "svc_a_b_2_2", "svc_final");
            assertThat(((StateValue.ObjectValue) result).values().get("seenCallId"))
                    .isEqualTo(StateValue.string("call-123"));

            client.callToolAsync("enable_repeat", StateValue.object(Map.of()))
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            assertThatThrownBy(
                            () -> client.listToolsAsync().toCompletableFuture().get(10, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(MCPException.class)
                    .rootCause()
                    .hasMessageContaining("repeated pagination cursor");
        }
    }

    private static final class RawPaginationServer implements AutoCloseable {
        private static final TypeRef<McpSchema.PaginatedRequest> PAGE_TYPE = new TypeRef<>() {};

        private static final TypeRef<McpSchema.CallToolRequest> CALL_TYPE = new TypeRef<>() {};

        private final HttpServer server;

        private final McpJsonMapper mapper;

        private final java.util.concurrent.ExecutorService executor;

        private final AtomicBoolean repeatCursor = new AtomicBoolean();

        private RawPaginationServer(
                HttpServer server, McpJsonMapper mapper, java.util.concurrent.ExecutorService executor) {
            this.server = server;
            this.mapper = mapper;
            this.executor = executor;
        }

        private static RawPaginationServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            RawPaginationServer fixture = new RawPaginationServer(server, McpJsonDefaults.getMapper(), executor);
            server.createContext("/mcp", fixture::handle);
            server.setExecutor(executor);
            server.start();
            return fixture;
        }

        private URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                String json = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(mapper, json);
                if (!(message instanceof McpSchema.JSONRPCRequest request)) {
                    exchange.sendResponseHeaders(202, -1);
                    return;
                }
                Object result =
                        switch (request.method()) {
                            case McpSchema.METHOD_INITIALIZE -> initialize();
                            case McpSchema.METHOD_PING -> Map.of();
                            case McpSchema.METHOD_TOOLS_LIST -> listTools(request.params());
                            case McpSchema.METHOD_TOOLS_CALL -> callTool(request.params());
                            default -> Map.of();
                        };
                byte[] response = mapper.writeValueAsString(McpSchema.JSONRPCResponse.result(request.id(), result))
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (RuntimeException failure) {
                byte[] response = failure.getMessage().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, response.length);
                exchange.getResponseBody().write(response);
            }
        }

        private McpSchema.InitializeResult initialize() {
            McpSchema.ServerCapabilities capabilities = new McpSchema.ServerCapabilities(
                    null,
                    null,
                    null,
                    null,
                    null,
                    McpSchema.ServerCapabilities.ToolCapabilities.builder()
                            .listChanged(false)
                            .build());
            return McpSchema.InitializeResult.builder(
                            ProtocolVersions.MCP_2025_11_25,
                            capabilities,
                            McpSchema.Implementation.builder("pagination-test", "1.0.0")
                                    .build())
                    .build();
        }

        private McpSchema.ListToolsResult listTools(Object parameters) {
            McpSchema.PaginatedRequest request = mapper.convertValue(parameters, PAGE_TYPE);
            Map<String, Object> input = Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of("value", Map.of("type", "string")),
                    "required",
                    List.of("value"),
                    "additionalProperties",
                    false);
            Map<String, Object> output = Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of("seenCallId", Map.of("type", "string")),
                    "required",
                    List.of("seenCallId"),
                    "additionalProperties",
                    false);
            if (request.cursor() == null) {
                return McpSchema.ListToolsResult.builder(List.of(
                                tool("a b", input, output), tool("a/b", input, output), tool("a_b_2", input, output)))
                        .nextCursor(repeatCursor.get() ? "repeat" : "next")
                        .build();
            }
            return McpSchema.ListToolsResult.builder(List.of(tool("final", input, output)))
                    .nextCursor(repeatCursor.get() ? "repeat" : null)
                    .build();
        }

        private static McpSchema.Tool tool(String name, Map<String, Object> input, Map<String, Object> output) {
            return McpSchema.Tool.builder(name, input)
                    .description("Invokes " + name + ".")
                    .outputSchema(output)
                    .build();
        }

        private McpSchema.CallToolResult callTool(Object parameters) {
            McpSchema.CallToolRequest request = mapper.convertValue(parameters, CALL_TYPE);
            if ("enable_repeat".equals(request.name())) {
                repeatCursor.set(true);
            }
            Object callId = request.meta() == null
                    ? "missing"
                    : request.meta().getOrDefault("com.microsoft.agents/callId", "missing");
            Map<String, Object> structured = Map.of("seenCallId", callId);
            return McpSchema.CallToolResult.builder(List.of(
                            McpSchema.TextContent.builder(callId.toString()).build()))
                    .structuredContent(structured)
                    .isError(false)
                    .build();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.close();
        }
    }
}
