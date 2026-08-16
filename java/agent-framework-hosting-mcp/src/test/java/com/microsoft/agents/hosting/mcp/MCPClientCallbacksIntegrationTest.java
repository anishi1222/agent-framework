// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.protocols.mcp.MCPClient;
import com.microsoft.agents.protocols.mcp.MCPClientOptions;
import com.microsoft.agents.protocols.mcp.MCPContent;
import com.microsoft.agents.protocols.mcp.MCPElicitationRequest;
import com.microsoft.agents.protocols.mcp.MCPElicitationResult;
import com.microsoft.agents.protocols.mcp.MCPRole;
import com.microsoft.agents.protocols.mcp.MCPRoot;
import com.microsoft.agents.protocols.mcp.MCPSamplingResult;
import com.microsoft.agents.protocols.mcp.MCPStreamableHTTPTransport;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class MCPClientCallbacksIntegrationTest {
    @Test
    void handlesRootsSamplingAndBothElicitationModesThroughRealSdk() throws Exception {
        // Arrange
        try (RawCallbackServer server = RawCallbackServer.start();
                MCPClient client = MCPClient.create(
                        MCPStreamableHTTPTransport.builder(server.endpoint())
                                .allowInsecureLoopback(true)
                                .allowedHosts(Set.of("127.0.0.1"))
                                .build(),
                        MCPClientOptions.builder()
                                .roots(List.of(new MCPRoot(URI.create("file:///workspace"), "workspace")))
                                .samplingHandler(request -> CompletableFuture.completedFuture(new MCPSamplingResult(
                                        MCPRole.ASSISTANT,
                                        new MCPContent.Text("sampled"),
                                        "callback-model",
                                        MCPSamplingResult.StopReason.END_TURN)))
                                .formElicitationHandler(request -> {
                                    assertThat(request).isInstanceOf(MCPElicitationRequest.Form.class);
                                    return CompletableFuture.completedFuture(new MCPElicitationResult(
                                            MCPElicitationResult.Action.ACCEPT,
                                            StateValue.object(Map.of("answer", StateValue.string("yes")))));
                                })
                                .urlElicitationHandler(request -> {
                                    assertThat(request).isInstanceOf(MCPElicitationRequest.Url.class);
                                    return CompletableFuture.completedFuture(
                                            new MCPElicitationResult(MCPElicitationResult.Action.DECLINE, null));
                                })
                                .build())) {
            // Act
            var result = client.callToolAsync("callbacks", StateValue.object(Map.of()))
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);

            // Assert
            StateValue.ObjectValue structured = (StateValue.ObjectValue) result.structuredContent();
            assertThat(structured.values())
                    .containsEntry("root", StateValue.string("file:///workspace"))
                    .containsEntry("sample", StateValue.string("sampled"))
                    .containsEntry("form", StateValue.string("yes"))
                    .containsEntry("url", StateValue.string("DECLINE"));
        }
    }

    private static final class RawCallbackServer implements AutoCloseable {
        private final Tomcat tomcat;

        private final McpAsyncServer server;

        private final Path baseDirectory;

        private final URI endpoint;

        private RawCallbackServer(Tomcat tomcat, McpAsyncServer server, Path baseDirectory, URI endpoint) {
            this.tomcat = tomcat;
            this.server = server;
            this.baseDirectory = baseDirectory;
            this.endpoint = endpoint;
        }

        private static RawCallbackServer start() throws Exception {
            Path base = Files.createTempDirectory("mcp-callback-test-");
            var security = DefaultServerTransportSecurityValidator.builder()
                    .allowedHost("127.0.0.1:*")
                    .build();
            var transport = HttpServletStreamableServerTransportProvider.builder()
                    .mcpEndpoint("/mcp")
                    .securityValidator(security)
                    .build();
            Map<String, Object> input = Map.of("type", "object", "additionalProperties", false);
            McpSchema.Tool tool = McpSchema.Tool.builder("callbacks", input)
                    .description("Exercises client callback capabilities.")
                    .build();
            McpAsyncServer sdkServer = McpServer.async(transport)
                    .serverInfo("callback-server", "1.0.0")
                    .requestTimeout(Duration.ofSeconds(5))
                    .toolCall(
                            tool,
                            (exchange, request) -> exchange.listRoots()
                                    .flatMap(roots -> exchange.createMessage(McpSchema.CreateMessageRequest.builder(
                                                            List.of(McpSchema.SamplingMessage.builder(
                                                                            McpSchema.Role.USER,
                                                                            McpSchema.TextContent.builder("sample")
                                                                                    .build())
                                                                    .build()),
                                                            32)
                                                    .build())
                                            .flatMap(sample -> exchange.createElicitation(
                                                            McpSchema.ElicitFormRequest.builder(
                                                                            "Provide an answer.",
                                                                            Map.of(
                                                                                    "type",
                                                                                    "object",
                                                                                    "properties",
                                                                                    Map.of(
                                                                                            "answer",
                                                                                            Map.of("type", "string")),
                                                                                    "required",
                                                                                    List.of("answer"),
                                                                                    "additionalProperties",
                                                                                    false))
                                                                    .build())
                                                    .flatMap(form -> exchange.createElicitation(
                                                                    McpSchema.ElicitUrlRequest.builder(
                                                                                    "Open the secure URL.",
                                                                                    "https://example.test/authorize",
                                                                                    "url-1")
                                                                            .build())
                                                            .map(url -> callbackResult(roots, sample, form, url))))))
                    .build();

            Tomcat tomcat = new Tomcat();
            tomcat.setBaseDir(base.toString());
            tomcat.setHostname("127.0.0.1");
            tomcat.setPort(0);
            Context context = tomcat.addContext("", base.toString());
            Wrapper wrapper = Tomcat.addServlet(context, "mcp", transport);
            wrapper.setAsyncSupported(true);
            context.addServletMappingDecoded("/mcp", "mcp");
            tomcat.start();
            int port = tomcat.getConnector().getLocalPort();
            return new RawCallbackServer(tomcat, sdkServer, base, URI.create("http://127.0.0.1:" + port + "/mcp"));
        }

        private static McpSchema.CallToolResult callbackResult(
                McpSchema.ListRootsResult roots,
                McpSchema.CreateMessageResult sample,
                McpSchema.ElicitResult form,
                McpSchema.ElicitResult url) {
            String sampledText = ((McpSchema.TextContent) sample.content()).text();
            Map<String, Object> structured = Map.of(
                    "root",
                    roots.roots().get(0).uri(),
                    "sample",
                    sampledText,
                    "form",
                    form.content().get("answer"),
                    "url",
                    url.action().name());
            return McpSchema.CallToolResult.builder(List.of(
                            McpSchema.TextContent.builder("callbacks complete").build()))
                    .structuredContent(structured)
                    .build();
        }

        private URI endpoint() {
            return endpoint;
        }

        @Override
        public void close() {
            server.closeGracefully().onErrorResume(ignored -> Mono.empty()).block();
            try {
                tomcat.stop();
            } catch (org.apache.catalina.LifecycleException ignored) {
                // Continue to destroy the test server.
            }
            try {
                tomcat.destroy();
            } catch (org.apache.catalina.LifecycleException ignored) {
                // Test server cleanup is best effort after stop.
            }
            try (var paths = Files.walk(baseDirectory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException ignored) {
                        // Test temporary files are best-effort cleanup.
                    }
                });
            } catch (java.io.IOException ignored) {
                // Test temporary files are best-effort cleanup.
            }
        }
    }
}
