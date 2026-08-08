// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

final class MCPSessionLimitFilter implements Filter {
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final Semaphore sessionSlots;

    private final Set<String> sessions = ConcurrentHashMap.newKeySet();

    MCPSessionLimitFilter(int maximumSessions) {
        sessionSlots = new Semaphore(maximumSessions);
    }

    @Override
    public void init(FilterConfig filterConfig) {}

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }
        String sessionId = httpRequest.getHeader(SESSION_HEADER);
        if (sessionId != null && !sessionId.isBlank() && !sessions.contains(sessionId)) {
            httpResponse.sendError(HttpServletResponse.SC_NOT_FOUND, "MCP session is not active.");
            return;
        }
        if (sessionId == null && "POST".equalsIgnoreCase(httpRequest.getMethod())) {
            initialize(httpRequest, httpResponse, chain);
            return;
        }

        chain.doFilter(request, response);
        if ("DELETE".equalsIgnoreCase(httpRequest.getMethod())
                && sessionId != null
                && httpResponse.getStatus() >= 200
                && httpResponse.getStatus() < 300
                && sessions.remove(sessionId)) {
            sessionSlots.release();
        }
    }

    @Override
    public void destroy() {
        sessions.clear();
    }

    private void initialize(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!sessionSlots.tryAcquire()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "MCP active-session limit was reached.");
            return;
        }
        SessionCapturingResponse capturing = new SessionCapturingResponse(response);
        boolean retained = false;
        try {
            chain.doFilter(request, capturing);
            String newSession = capturing.sessionId;
            if (newSession != null && !newSession.isBlank()) {
                retained = sessions.add(newSession);
            }
        } finally {
            if (!retained) {
                sessionSlots.release();
            }
        }
    }

    private static final class SessionCapturingResponse extends HttpServletResponseWrapper {
        private String sessionId;

        private SessionCapturingResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setHeader(String name, String value) {
            capture(name, value);
            super.setHeader(name, value);
        }

        @Override
        public void addHeader(String name, String value) {
            capture(name, value);
            super.addHeader(name, value);
        }

        private void capture(String name, String value) {
            if (SESSION_HEADER.equalsIgnoreCase(name)) {
                sessionId = value;
            }
        }
    }
}
