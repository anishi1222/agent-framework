// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MCPClientOptionsTest {
    @Test
    void samplingLimitIsCumulativePerClientLifetimeAndExceededErrorIsActionable() {
        // Arrange
        MCPClientOptions options =
                MCPClientOptions.builder().maxSamplingRequests(1).build();
        MCPStreamableHTTPTransport transport = MCPStreamableHTTPTransport.builder(URI.create("http://127.0.0.1:9/mcp"))
                .allowInsecureLoopback(true)
                .allowedHosts(Set.of("127.0.0.1"))
                .build();
        McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest.builder(
                        List.of(McpSchema.SamplingMessage.builder(
                                        McpSchema.Role.USER,
                                        McpSchema.TextContent.builder("sample").build())
                                .build()),
                        32)
                .build();

        // Act / Assert
        try (MCPClient firstClient = MCPClient.create(transport, options);
                MCPClient secondClient = MCPClient.create(transport, options)) {
            assertThat(firstClient.toSamplingRequest(request).maxTokens()).isEqualTo(32);
            assertThat(secondClient.toSamplingRequest(request).maxTokens()).isEqualTo(32);
            assertThatThrownBy(() -> firstClient.toSamplingRequest(request))
                    .isInstanceOf(MCPException.class)
                    .hasMessage("MCP sampling request limit exceeded: configured maxSamplingRequests=1 applies to the "
                            + "entire MCPClient lifetime. Create a new MCPClient to start a new sampling "
                            + "budget, or increase maxSamplingRequests only after reviewing the server's "
                            + "trust and cost limits.");
        }
    }
}
