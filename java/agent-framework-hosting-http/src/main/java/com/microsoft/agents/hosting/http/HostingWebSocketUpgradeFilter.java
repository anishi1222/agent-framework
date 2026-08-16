// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.hosting.HostingError;
import com.microsoft.agents.hosting.HostingErrorCode;
import com.microsoft.agents.hosting.HostingException;
import com.microsoft.agents.hosting.HostingRequestContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class HostingWebSocketUpgradeFilter implements Filter {
    private final HostingHttpHandler handler;

    HostingWebSocketUpgradeFilter(HostingHttpHandler handler) {
        this.handler = java.util.Objects.requireNonNull(handler, "handler");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest http)
                || !(response instanceof HttpServletResponse servletResponse)
                || !HostingHttpHandler.WEBSOCKET_PATH.equals(http.getRequestURI())
                || !"websocket".equalsIgnoreCase(http.getHeader("Upgrade"))) {
            chain.doFilter(request, response);
            return;
        }
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        try {
            if (!"GET".equals(http.getMethod())) {
                throw new HostingException(HostingErrorCode.METHOD_NOT_ALLOWED, "WebSocket handshake requires GET.");
            }
            String subprotocol = http.getHeader("Sec-WebSocket-Protocol");
            if (!HostingWebSocketProtocol.SUBPROTOCOL.equals(subprotocol)) {
                throw new HostingException(
                        HostingErrorCode.UPGRADE_REQUIRED, "Exact Java hosting WebSocket subprotocol is required.");
            }
            HostingHttpRequest hostingRequest = request(http, cancellation);
            long timeoutMillis =
                    Math.max(1L, handler.options().limits().idleTimeout().toMillis());
            HostingRequestContext context = handler.authenticateWebSocketAsync(hostingRequest)
                    .toCompletableFuture()
                    .get(Math.min(Long.MAX_VALUE - 1_000L, timeoutMillis) + 1_000L, TimeUnit.MILLISECONDS);
            chain.doFilter(new PrincipalRequest(http, new HostingPrincipalCarrier(context)), response);
        } catch (InterruptedException exception) {
            cancellation.cancel();
            Thread.currentThread().interrupt();
            writeError(
                    servletResponse,
                    HostingError.of(HostingErrorCode.CLIENT_CANCELLED, "WebSocket authentication was interrupted."));
        } catch (java.util.concurrent.TimeoutException exception) {
            cancellation.cancel();
            writeError(
                    servletResponse,
                    HostingError.of(HostingErrorCode.RUN_TIMEOUT, "WebSocket authentication timed out."));
        } catch (java.util.concurrent.ExecutionException exception) {
            writeError(servletResponse, error(exception.getCause()));
        } catch (HostingException exception) {
            writeError(servletResponse, exception.error());
        }
    }

    static HostingHttpRequest request(HttpServletRequest request, DefaultRunCancellation cancellation) {
        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                headers.put(name, Collections.list(request.getHeaders(name)));
            }
        }
        URI uri = URI.create(
                request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString()));
        return new HostingHttpRequest(
                request.getMethod(),
                uri,
                new InetSocketAddress(request.getRemoteAddr(), request.getRemotePort()),
                headers,
                new byte[0],
                cancellation);
    }

    private void writeError(HttpServletResponse response, HostingError error) throws IOException {
        byte[] body = handler.encodeError(error);
        response.setStatus(error.code().httpStatus());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        if (error.code() == HostingErrorCode.UNAUTHENTICATED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private static HostingError error(Throwable failure) {
        Throwable cause = RunHandles.unwrap(failure);
        return cause instanceof HostingException hosting
                ? hosting.error()
                : HostingError.of(HostingErrorCode.UNAUTHENTICATED, "WebSocket authentication failed.");
    }

    record HostingPrincipalCarrier(HostingRequestContext context) implements Principal {
        HostingPrincipalCarrier {
            java.util.Objects.requireNonNull(context, "context");
        }

        @Override
        public String getName() {
            return context.principalId();
        }
    }

    private static final class PrincipalRequest extends HttpServletRequestWrapper {
        private final Principal principal;

        private PrincipalRequest(HttpServletRequest request, Principal principal) {
            super(request);
            this.principal = principal;
        }

        @Override
        public Principal getUserPrincipal() {
            return principal;
        }
    }
}
