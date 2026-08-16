// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.protocols.mcp.MCPClient;
import com.microsoft.agents.protocols.mcp.MCPException;
import com.microsoft.agents.protocols.mcp.MCPStreamableHTTPTransport;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MCPServerValidationTest {
    @Test
    void rejectsToolNamesThatCollideAfterSafeNormalization() {
        // Arrange
        FunctionTool first = tool("delete/file");
        FunctionTool second = tool("delete file");

        // Act / Assert
        assertThatThrownBy(() -> MCPServer.builder("duplicates", "1.0.0")
                        .tools(java.util.List.of(first, second))
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("normalize to the same exposed name");
    }

    @Test
    void remoteBindingRequiresTrustedTlsProxyDeclarationAndOrigins() throws Exception {
        // Arrange
        InetAddress remote = InetAddress.getByName("192.0.2.10");

        // Act / Assert
        assertThatThrownBy(() -> MCPStreamableHTTPServerOptions.builder()
                        .bindAddress(remote)
                        .allowedHosts(Set.of("mcp.example.com:*"))
                        .allowedOrigins(Set.of("https://app.example.com"))
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("behindTrustedTLSProxy");

        MCPStreamableHTTPServerOptions valid = MCPStreamableHTTPServerOptions.builder()
                .bindAddress(remote)
                .behindTrustedTLSProxy(true)
                .allowedHosts(Set.of("mcp.example.com:*"))
                .allowedOrigins(Set.of("https://app.example.com"))
                .build();
        assertThat(valid.behindTrustedTLSProxy()).isTrue();
    }

    @Test
    void streamableHttpBoundsRetainedSessionsAndReleasesOnDelete() throws Exception {
        // Arrange
        MCPServer definition = MCPServer.builder("session-limit", "1.0.0").build();
        MCPStreamableHTTPServerOptions options =
                MCPStreamableHTTPServerOptions.builder().maxSessions(1).build();
        try (MCPStreamableHTTPServer host = definition.startStreamableHTTP(options)) {
            MCPClient first = client(host);
            MCPClient rejected = client(host);
            first.initializeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);

            // Act / Assert
            try {
                assertThatThrownBy(() ->
                                rejected.initializeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS))
                        .cause()
                        .isInstanceOf(MCPException.class);
            } finally {
                rejected.close();
            }
            first.close();
            try (MCPClient replacement = client(host)) {
                assertThat(replacement
                                .initializeAsync()
                                .toCompletableFuture()
                                .get(10, TimeUnit.SECONDS)
                                .serverName())
                        .isEqualTo("session-limit");
            }
        }
    }

    private static FunctionTool tool(String name) {
        return FunctionTool.create(
                new ToolMetadata(
                        name,
                        "Test tool.",
                        Set.of(ToolCapability.FUNCTION),
                        ToolApprovalMode.NEVER_REQUIRE,
                        StateValue.object(Map.of()),
                        StateValue.object(Map.of())),
                (context, arguments) -> CompletableFuture.completedFuture(StateValue.object(Map.of())));
    }

    private static MCPClient client(MCPStreamableHTTPServer host) {
        return MCPClient.create(MCPStreamableHTTPTransport.builder(host.endpoint())
                .allowInsecureLoopback(true)
                .allowedHosts(Set.of("127.0.0.1"))
                .build());
    }
}
