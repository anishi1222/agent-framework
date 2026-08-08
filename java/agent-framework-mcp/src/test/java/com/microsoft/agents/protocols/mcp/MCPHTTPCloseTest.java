// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MCPHTTPCloseTest {
    @Test
    void acceptsSuccessfulNoContentWhenClosingARealHttpSession() throws Exception {
        assertCloseStatusAccepted(204);
    }

    @Test
    void acceptsNotFoundWhenClosingARealHttpSession() throws Exception {
        assertCloseStatusAccepted(404);
    }

    @Test
    void acceptsMethodNotAllowedWhenClosingARealHttpSession() throws Exception {
        assertCloseStatusAccepted(405);
    }

    @Test
    void rejectsUnexpectedServerFailureWhenClosingARealHttpSession() throws Exception {
        // Arrange
        try (CloseStatusServer server = CloseStatusServer.start(500)) {
            MCPClient client = createInitializedClient(server);

            // Act / Assert
            try {
                assertThatThrownBy(
                                () -> client.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS))
                        .hasRootCauseInstanceOf(IOException.class)
                        .rootCause()
                        .hasMessage("MCP session DELETE failed with HTTP status 500.");
                assertThat(server.deleteRequests).hasValue(1);
            } finally {
                try {
                    client.close();
                } catch (RuntimeException ignored) {
                    // The already-observed close failure is stable across idempotent close calls.
                }
            }
        }
    }

    private static void assertCloseStatusAccepted(int statusCode) throws Exception {
        // Arrange
        try (CloseStatusServer server = CloseStatusServer.start(statusCode);
                MCPClient client = createInitializedClient(server)) {
            // Act
            client.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);

            // Assert
            assertThat(server.deleteRequests).hasValue(1);
        }
    }

    private static MCPClient createInitializedClient(CloseStatusServer server) throws Exception {
        MCPClient client = MCPClient.create(MCPStreamableHTTPTransport.builder(server.endpoint())
                .allowInsecureLoopback(true)
                .allowedHosts(Set.of("127.0.0.1"))
                .build());
        client.initializeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
        return client;
    }

    private static final class CloseStatusServer implements AutoCloseable {
        private static final String SESSION_HEADER = "Mcp-Session-Id";

        private final HttpServer server;

        private final McpJsonMapper mapper;

        private final int deleteStatus;

        private final AtomicInteger deleteRequests = new AtomicInteger();

        private CloseStatusServer(HttpServer server, McpJsonMapper mapper, int deleteStatus) {
            this.server = server;
            this.mapper = mapper;
            this.deleteStatus = deleteStatus;
        }

        private static CloseStatusServer start(int deleteStatus) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            CloseStatusServer fixture = new CloseStatusServer(server, McpJsonDefaults.getMapper(), deleteStatus);
            server.createContext("/mcp", fixture::handle);
            server.start();
            return fixture;
        }

        private URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                if ("DELETE".equals(exchange.getRequestMethod())) {
                    deleteRequests.incrementAndGet();
                    exchange.sendResponseHeaders(deleteStatus, -1);
                    return;
                }
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
                Object result = McpSchema.METHOD_INITIALIZE.equals(request.method()) ? initialize() : Map.of();
                if (McpSchema.METHOD_INITIALIZE.equals(request.method())) {
                    exchange.getResponseHeaders().set(SESSION_HEADER, "close-test-session");
                }
                byte[] response = mapper.writeValueAsString(McpSchema.JSONRPCResponse.result(request.id(), result))
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        }

        private static McpSchema.InitializeResult initialize() {
            McpSchema.ServerCapabilities capabilities =
                    new McpSchema.ServerCapabilities(null, null, null, null, null, null);
            return McpSchema.InitializeResult.builder(
                            ProtocolVersions.MCP_2025_11_25,
                            capabilities,
                            McpSchema.Implementation.builder("close-status-test", "1.0.0")
                                    .build())
                    .build();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
