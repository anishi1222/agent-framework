// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MCPTransportSecurityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void streamableHttpRequiresTlsExceptExplicitLoopbackAndRedactsHeaders() {
        // Arrange
        URI loopback = URI.create("http://127.0.0.1:8123/mcp");

        // Act
        MCPStreamableHTTPTransport transport = MCPStreamableHTTPTransport.builder(loopback)
                .allowInsecureLoopback(true)
                .allowedHosts(Set.of("127.0.0.1"))
                .header("Authorization", "Bearer top-secret")
                .build();

        // Assert
        assertThat(transport.endpoint()).isEqualTo(loopback);
        assertThat(transport.toString()).contains("Authorization").doesNotContain("top-secret");
        assertThatThrownBy(() -> MCPStreamableHTTPTransport.builder(URI.create("http://example.com/mcp"))
                        .allowedHosts(Set.of("example.com"))
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> MCPStreamableHTTPTransport.builder(URI.create("https://example.com/mcp"))
                        .allowedHosts(Set.of("other.example"))
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("allowedHosts");
    }

    @Test
    void stdioEnforcesEnvironmentAndWorkingDirectoryPolicies() throws Exception {
        // Arrange
        Path childDirectory = temporaryDirectory.resolve("child");
        assertThat(childDirectory.toFile().mkdir()).isTrue();

        // Act
        MCPStdioTransport transport = MCPStdioTransport.builder("/usr/bin/env")
                .argument("true")
                .environment(Map.of("SAFE_VALUE", "secret-value"))
                .inheritedEnvironmentAllowlist(Set.of("PATH"))
                .workingDirectory(childDirectory)
                .allowedWorkingDirectories(Set.of(temporaryDirectory))
                .shutdownTimeout(Duration.ofSeconds(1))
                .build();

        // Assert
        assertThat(transport.arguments()).containsExactly("true");
        assertThat(transport.inheritedEnvironmentAllowlist()).containsExactly("PATH");
        assertThat(transport.toString()).contains("SAFE_VALUE").doesNotContain("secret-value");
        assertThatThrownBy(() -> MCPStdioTransport.builder("/usr/bin/env")
                        .workingDirectory(childDirectory)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("allowedWorkingDirectories");
        assertThatThrownBy(() -> MCPStdioTransport.builder("/usr/bin/env")
                        .environment(Map.of("BAD-NAME", "value"))
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("environment name");

        Path allowed = temporaryDirectory.resolve("allowed");
        Path outside = temporaryDirectory.resolve("outside");
        java.nio.file.Files.createDirectories(allowed);
        java.nio.file.Files.createDirectories(outside);
        Path link = allowed.resolve("escape");
        java.nio.file.Files.createSymbolicLink(link, outside);
        assertThatThrownBy(() -> MCPStdioTransport.builder("/usr/bin/env")
                        .workingDirectory(link)
                        .allowedWorkingDirectories(Set.of(allowed))
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("outside allowedWorkingDirectories");
    }

    @Test
    void clientRejectsHostileNestedArgumentsBeforeConnecting() {
        // Arrange
        MCPLimits limits = new MCPLimits(1024, 2, 16, 4, 2, 2);
        MCPClient client = MCPClient.create(
                MCPStreamableHTTPTransport.builder(URI.create("http://127.0.0.1:9/mcp"))
                        .allowInsecureLoopback(true)
                        .allowedHosts(Set.of("127.0.0.1"))
                        .build(),
                MCPClientOptions.builder().limits(limits).build());
        StateValue.ObjectValue hostile = StateValue.object(Map.of(
                "first",
                StateValue.object(
                        Map.of("second", StateValue.object(Map.of("third", StateValue.string("too deep")))))));

        // Act / Assert
        try (client) {
            assertThatThrownBy(() -> client.callToolAsync("test", hostile))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("nesting depth");
        }
    }

    @Test
    void preCancelledCallIsNeverDispatched() throws Exception {
        // Arrange
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch requestSeen = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            requests.incrementAndGet();
            requestSeen.countDown();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        cancellation.cancel();

        // Act / Assert
        try (MCPClient client = MCPClient.create(MCPStreamableHTTPTransport.builder(endpoint)
                .allowInsecureLoopback(true)
                .allowedHosts(Set.of("127.0.0.1"))
                .build())) {
            var handle = client.startToolCall(
                    "side_effect",
                    StateValue.object(Map.of()),
                    MCPToolCallOptions.builder(Duration.ofSeconds(1))
                            .cancellation(cancellation)
                            .build());
            assertThatThrownBy(() -> handle.resultAsync().toCompletableFuture().get(2, TimeUnit.SECONDS))
                    .cause()
                    .isInstanceOf(com.microsoft.agents.core.RunCancelledException.class);
            assertThat(requestSeen.await(500, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(requests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamableHttpRejectsResponseBeforeUnboundedAggregation() throws Exception {
        // Arrange
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            byte[] response = "x".repeat(257).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        MCPLimits limits = new MCPLimits(256, 8, 32, 4, 2, 2);

        // Act / Assert
        try (MCPClient client = MCPClient.create(
                MCPStreamableHTTPTransport.builder(endpoint)
                        .allowInsecureLoopback(true)
                        .allowedHosts(Set.of("127.0.0.1"))
                        .build(),
                MCPClientOptions.builder().limits(limits).build())) {
            assertThatThrownBy(
                            () -> client.initializeAsync().toCompletableFuture().get(5, TimeUnit.SECONDS))
                    .cause()
                    .isInstanceOf(MCPException.class);
        } finally {
            server.stop(0);
        }
    }
}
