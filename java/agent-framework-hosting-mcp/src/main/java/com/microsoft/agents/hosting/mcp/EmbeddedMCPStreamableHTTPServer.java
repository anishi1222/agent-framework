// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import com.microsoft.agents.protocols.mcp.MCPException;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;

final class EmbeddedMCPStreamableHTTPServer implements MCPStreamableHTTPServer {
    private final Tomcat tomcat;

    private final MCPServerRuntime runtime;

    private final Path baseDirectory;

    private final URI endpoint;

    private final AtomicReference<CompletableFuture<Void>> closeFuture = new AtomicReference<>();

    private EmbeddedMCPStreamableHTTPServer(Tomcat tomcat, MCPServerRuntime runtime, Path baseDirectory, URI endpoint) {
        this.tomcat = tomcat;
        this.runtime = runtime;
        this.baseDirectory = baseDirectory;
        this.endpoint = endpoint;
    }

    static MCPStreamableHTTPServer start(MCPServer definition, MCPStreamableHTTPServerOptions options) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(options, "options");
        if (!definition.limits().equals(options.limits())) {
            throw new com.microsoft.agents.core.ValidationException("HTTP host limits must match MCP server limits.");
        }
        Path baseDirectory;
        try {
            baseDirectory = Files.createTempDirectory("agent-framework-mcp-");
        } catch (IOException exception) {
            throw new MCPException("Unable to create the temporary MCP HTTP host directory.", exception);
        }

        DefaultServerTransportSecurityValidator securityValidator = DefaultServerTransportSecurityValidator.builder()
                .allowedHosts(options.allowedHosts().stream().sorted().toList())
                .allowedOrigins(options.allowedOrigins().stream().sorted().toList())
                .build();
        HttpServletStreamableServerTransportProvider.Builder transportBuilder =
                HttpServletStreamableServerTransportProvider.builder()
                        .mcpEndpoint(options.endpoint())
                        .securityValidator(securityValidator);
        if (options.keepAliveInterval() != null) {
            transportBuilder.keepAliveInterval(options.keepAliveInterval());
        }
        HttpServletStreamableServerTransportProvider provider = transportBuilder.build();

        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(baseDirectory.toString());
        tomcat.setHostname(options.bindAddress().getHostAddress());
        tomcat.setPort(options.port());
        Connector connector = tomcat.getConnector();
        connector.setProperty("address", options.bindAddress().getHostAddress());
        connector.setProperty("maxPostSize", Integer.toString(options.limits().maxPayloadBytes()));
        connector.setProperty(
                "maxSwallowSize", Integer.toString(options.limits().maxPayloadBytes()));
        connector.setProperty("maxHttpRequestHeaderSize", "16384");
        connector.setProperty("connectionTimeout", "10000");

        Context context = tomcat.addContext("", baseDirectory.toString());
        Wrapper servlet = Tomcat.addServlet(context, "mcp", provider);
        servlet.setAsyncSupported(true);
        context.addServletMappingDecoded(options.endpoint(), "mcp");
        addPayloadFilter(context, options);
        addSessionLimitFilter(context, options);

        MCPServerRuntime runtime = null;
        try {
            runtime = MCPServerRuntime.startHTTP(definition, provider);
            tomcat.start();
            int localPort = connector.getLocalPort();
            URI endpoint = new URI(
                    "http", null, options.bindAddress().getHostAddress(), localPort, options.endpoint(), null, null);
            return new EmbeddedMCPStreamableHTTPServer(tomcat, runtime, baseDirectory, endpoint);
        } catch (Exception failure) {
            if (runtime != null) {
                runtime.close();
            }
            stopTomcat(tomcat);
            deleteDirectory(baseDirectory);
            throw new MCPException("Unable to start the embedded MCP Streamable HTTP server.", failure);
        }
    }

    @Override
    public URI endpoint() {
        return endpoint;
    }

    @Override
    public boolean isRunning() {
        return closeFuture.get() == null && runtime.isRunning();
    }

    @Override
    public CompletionStage<Void> closeAsync() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (!closeFuture.compareAndSet(null, result)) {
            return Objects.requireNonNull(closeFuture.get()).minimalCompletionStage();
        }
        Thread.startVirtualThread(() -> {
            RuntimeException closeFailure = null;
            try {
                runtime.close();
            } catch (RuntimeException failure) {
                closeFailure = failure;
            } finally {
                stopTomcat(tomcat);
                deleteDirectory(baseDirectory);
            }
            if (closeFailure == null) {
                result.complete(null);
            } else {
                result.completeExceptionally(closeFailure);
            }
        });
        return result.minimalCompletionStage();
    }

    @Override
    public void close() {
        try {
            closeAsync().toCompletableFuture().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MCPException("MCP HTTP server close was interrupted.", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new MCPException("MCP HTTP server close failed.", exception.getCause());
        }
    }

    private static void addPayloadFilter(Context context, MCPStreamableHTTPServerOptions options) {
        FilterDef filter = new FilterDef();
        filter.setFilterName("mcpPayloadLimit");
        filter.setFilter(new MCPPayloadLimitFilter(options.limits().maxPayloadBytes()));
        filter.setAsyncSupported("true");
        context.addFilterDef(filter);

        FilterMap mapping = new FilterMap();
        mapping.setFilterName("mcpPayloadLimit");
        mapping.addURLPattern(options.endpoint());
        mapping.setDispatcher("REQUEST");
        context.addFilterMapBefore(mapping);
    }

    private static void addSessionLimitFilter(Context context, MCPStreamableHTTPServerOptions options) {
        FilterDef filter = new FilterDef();
        filter.setFilterName("mcpSessionLimit");
        filter.setFilter(new MCPSessionLimitFilter(options.maxSessions()));
        filter.setAsyncSupported("true");
        context.addFilterDef(filter);

        FilterMap mapping = new FilterMap();
        mapping.setFilterName("mcpSessionLimit");
        mapping.addURLPattern(options.endpoint());
        mapping.setDispatcher("REQUEST");
        context.addFilterMapBefore(mapping);
    }

    private static void stopTomcat(Tomcat tomcat) {
        try {
            tomcat.stop();
        } catch (org.apache.catalina.LifecycleException ignored) {
            // Continue to destroy and release all remaining resources.
        }
        try {
            tomcat.destroy();
        } catch (org.apache.catalina.LifecycleException ignored) {
            // Best effort after stop.
        }
    }

    private static void deleteDirectory(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temporary host files are best-effort cleanup after process resources close.
                }
            });
        } catch (IOException ignored) {
            // Temporary host files are best-effort cleanup after process resources close.
        }
    }
}
