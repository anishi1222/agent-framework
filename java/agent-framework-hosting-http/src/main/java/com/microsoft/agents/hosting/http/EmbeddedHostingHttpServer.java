// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingError;
import com.microsoft.agents.hosting.HostingErrorCode;
import com.microsoft.agents.hosting.HostingException;
import jakarta.servlet.ServletException;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerContainer;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.websocket.server.WsSci;

final class EmbeddedHostingHttpServer implements HostingHttpServer {
    private final Tomcat tomcat;

    private final Connector connector;

    private final HostingServlet servlet;

    private final HostingHttpHandler handler;

    private final HostingWebSocketProtocol webSocketProtocol;

    private final Path baseDirectory;

    private final URI endpoint;

    private final URI webSocketEndpoint;

    private final AtomicReference<CompletableFuture<Void>> closeFuture = new AtomicReference<>();

    private EmbeddedHostingHttpServer(
            Tomcat tomcat,
            Connector connector,
            HostingServlet servlet,
            HostingHttpHandler handler,
            HostingWebSocketProtocol webSocketProtocol,
            Path baseDirectory,
            URI endpoint,
            URI webSocketEndpoint) {
        this.tomcat = tomcat;
        this.connector = connector;
        this.servlet = servlet;
        this.handler = handler;
        this.webSocketProtocol = webSocketProtocol;
        this.baseDirectory = baseDirectory;
        this.endpoint = endpoint;
        this.webSocketEndpoint = webSocketEndpoint;
    }

    static HostingHttpServer start(HostingDispatcher dispatcher, HostingHttpServerOptions options) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(options, "options");
        Path baseDirectory;
        try {
            baseDirectory = Files.createTempDirectory("agent-framework-hosting-");
        } catch (IOException exception) {
            throw new HostingException(
                    HostingErrorCode.INTERNAL_ERROR, "Unable to create temporary hosting directory.", exception);
        }

        HostingHttpHandler handler = new HostingHttpHandler(dispatcher, options);
        HostingServlet servlet = new HostingServlet(handler);
        HostingWebSocketProtocol protocol = new HostingWebSocketProtocol(dispatcher, handler.codec(), options);
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(baseDirectory.toString());
        tomcat.setHostname(options.bindAddress().getHostAddress());
        tomcat.setPort(options.port());
        configureErrorReporting(tomcat, handler);
        Connector connector = tomcat.getConnector();
        configureConnector(connector, options);
        Context context = tomcat.addContext("", baseDirectory.toString());
        Wrapper wrapper = Tomcat.addServlet(context, "agentFrameworkHosting", servlet);
        wrapper.setAsyncSupported(true);
        context.addServletMappingDecoded("/v1/*", "agentFrameworkHosting");
        context.addServletMappingDecoded("/v1", "agentFrameworkHosting");
        addWebSocketSecurityFilter(context, handler);
        addWebSocket(context, protocol, options);

        try {
            tomcat.start();
            int localPort = connector.getLocalPort();
            URI origin = advertisedOrigin(options, localPort);
            URI endpoint = append(origin, HostingHttpHandler.BASE_PATH);
            String webSocketScheme = "https".equalsIgnoreCase(origin.getScheme()) ? "wss" : "ws";
            URI webSocketOrigin = new URI(webSocketScheme, null, origin.getHost(), origin.getPort(), null, null, null);
            URI webSocketEndpoint = append(webSocketOrigin, HostingHttpHandler.WEBSOCKET_PATH);
            return new EmbeddedHostingHttpServer(
                    tomcat, connector, servlet, handler, protocol, baseDirectory, endpoint, webSocketEndpoint);
        } catch (Exception failure) {
            try {
                protocol.close();
            } finally {
                handler.close();
                stopTomcat(tomcat);
                deleteDirectory(baseDirectory);
            }
            throw new HostingException(
                    HostingErrorCode.INTERNAL_ERROR, "Unable to start embedded hosting server.", failure);
        }
    }

    @Override
    public URI endpoint() {
        return endpoint;
    }

    @Override
    public URI webSocketEndpoint() {
        return webSocketEndpoint;
    }

    @Override
    public boolean isRunning() {
        return closeFuture.get() == null && tomcat.getServer().getState().isAvailable();
    }

    @Override
    public CompletionStage<Void> closeAsync() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (!closeFuture.compareAndSet(null, result)) {
            return Objects.requireNonNull(closeFuture.get()).minimalCompletionStage();
        }
        Thread.startVirtualThread(() -> {
            RuntimeException failure = null;
            try {
                connector.pause();
                servlet.shutdown();
                webSocketProtocol.close();
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            } finally {
                handler.close();
                stopTomcat(tomcat);
                deleteDirectory(baseDirectory);
            }
            if (failure == null) {
                result.complete(null);
            } else {
                result.completeExceptionally(failure);
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
            throw new HostingException(
                    HostingErrorCode.CLIENT_CANCELLED, "Hosting server close was interrupted.", exception);
        } catch (ExecutionException exception) {
            throw new HostingException(
                    HostingErrorCode.INTERNAL_ERROR, "Hosting server close failed.", exception.getCause());
        }
    }

    private static void configureConnector(Connector connector, HostingHttpServerOptions options) {
        connector.setProperty("address", options.bindAddress().getHostAddress());
        connector.setProperty("maxPostSize", Long.toString(options.limits().maxRequestBytes()));
        connector.setProperty("maxSwallowSize", Long.toString(options.limits().maxRequestBytes()));
        connector.setProperty("maxHttpRequestHeaderSize", Integer.toString(options.maxHttpHeaderBytes()));
        connector.setProperty("connectionTimeout", "10000");
        connector.setProperty("compression", "off");
        connector.setProperty("allowTrace", "false");
        connector.setProperty("encodedSolidusHandling", "reject");
        connector.setProperty("maxParameterCount", "0");
    }

    private static void configureErrorReporting(Tomcat tomcat, HostingHttpHandler handler) {
        if (tomcat.getHost() instanceof StandardHost host) {
            host.setErrorReportValveClass(null);
            host.getPipeline().addValve(new HostingErrorReportValve(handler));
        }
    }

    private static void addWebSocketSecurityFilter(Context context, HostingHttpHandler handler) {
        FilterDef filter = new FilterDef();
        filter.setFilterName("agentFrameworkHostingWebSocketSecurity");
        filter.setFilter(new HostingWebSocketUpgradeFilter(handler));
        filter.setAsyncSupported("true");
        context.addFilterDef(filter);

        FilterMap mapping = new FilterMap();
        mapping.setFilterName("agentFrameworkHostingWebSocketSecurity");
        mapping.addURLPattern(HostingHttpHandler.WEBSOCKET_PATH);
        mapping.setDispatcher("REQUEST");
        context.addFilterMapBefore(mapping);
    }

    private static void addWebSocket(
            Context context, HostingWebSocketProtocol protocol, HostingHttpServerOptions options) {
        context.addServletContainerInitializer(new WsSci(), Set.of());
        context.addServletContainerInitializer(
                (classes, servletContext) -> {
                    Object value = servletContext.getAttribute(ServerContainer.class.getName());
                    if (!(value instanceof ServerContainer container)) {
                        throw new ServletException("Tomcat WebSocket server container is unavailable.");
                    }
                    try {
                        container.addEndpoint(EmbeddedHostingWebSocketEndpoint.config(
                                protocol, options.limits().maxWebSocketFrameBytes()));
                    } catch (DeploymentException exception) {
                        throw new ServletException("Unable to register Java hosting WebSocket endpoint.", exception);
                    }
                },
                Set.of());
    }

    private static URI advertisedOrigin(HostingHttpServerOptions options, int localPort) throws URISyntaxException {
        if (options.advertisedEndpoint() != null) {
            URI advertised = options.advertisedEndpoint();
            return new URI(advertised.getScheme(), null, advertised.getHost(), advertised.getPort(), null, null, null);
        }
        return new URI("http", null, options.bindAddress().getHostAddress(), localPort, null, null, null);
    }

    private static URI append(URI origin, String path) throws URISyntaxException {
        return new URI(origin.getScheme(), null, origin.getHost(), origin.getPort(), path, null, null);
    }

    private static void stopTomcat(Tomcat tomcat) {
        try {
            tomcat.stop();
        } catch (LifecycleException ignored) {
            // Continue to destroy remaining resources.
        }
        try {
            tomcat.destroy();
        } catch (LifecycleException ignored) {
            // Best effort after stop.
        }
    }

    private static void deleteDirectory(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temporary host files are best-effort cleanup.
                }
            });
        } catch (IOException ignored) {
            // Temporary host files are best-effort cleanup.
        }
    }

    private static final class HostingErrorReportValve extends ErrorReportValve {
        private final HostingHttpHandler handler;

        private HostingErrorReportValve(HostingHttpHandler handler) {
            this.handler = Objects.requireNonNull(handler, "handler");
            setShowReport(false);
            setShowServerInfo(false);
        }

        @Override
        protected void report(Request request, Response response, Throwable throwable) {
            if (response.isCommitted() || response.getStatus() < 400 || response.getContentWritten() > 0) {
                return;
            }
            HostingErrorCode code =
                    switch (response.getStatus()) {
                        case 404 -> HostingErrorCode.NOT_FOUND;
                        case 405 -> HostingErrorCode.METHOD_NOT_ALLOWED;
                        case 413 -> HostingErrorCode.PAYLOAD_TOO_LARGE;
                        default ->
                            response.getStatus() >= 500
                                    ? HostingErrorCode.INTERNAL_ERROR
                                    : HostingErrorCode.MALFORMED_REQUEST;
                    };
            byte[] body =
                    handler.encodeError(HostingError.of(code, "HTTP request was rejected before hosting dispatch."));
            try {
                response.resetBuffer(true);
                response.setContentType("application/json");
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.setHeader("Cache-Control", "no-store");
                response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
                response.setHeader("Referrer-Policy", "no-referrer");
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setHeader("X-Frame-Options", "DENY");
                response.setContentLength(body.length);
                response.getOutputStream().write(body);
            } catch (IOException | IllegalStateException ignored) {
                // The connection may already have closed while Tomcat rejected the request.
            }
        }
    }
}
