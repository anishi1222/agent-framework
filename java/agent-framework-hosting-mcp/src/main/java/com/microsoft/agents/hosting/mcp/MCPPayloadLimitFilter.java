// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class MCPPayloadLimitFilter implements Filter {
    private final int maximumBytes;

    MCPPayloadLimitFilter(int maximumBytes) {
        this.maximumBytes = maximumBytes;
    }

    @Override
    public void init(FilterConfig filterConfig) {}

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)
                || !"POST".equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        long declaredLength = httpRequest.getContentLengthLong();
        if (declaredLength > maximumBytes) {
            httpResponse.sendError(
                    HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    "MCP request exceeds the configured payload limit.");
            return;
        }
        byte[] body = httpRequest.getInputStream().readNBytes(maximumBytes + 1);
        if (body.length > maximumBytes) {
            httpResponse.sendError(
                    HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    "MCP request exceeds the configured payload limit.");
            return;
        }
        chain.doFilter(new BufferedRequest(httpRequest, body), response);
    }

    @Override
    public void destroy() {}

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new BufferedServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    private static final class BufferedServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream input;

        private BufferedServletInputStream(byte[] body) {
            input = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return input.read();
        }

        @Override
        public boolean isFinished() {
            return input.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            try {
                if (!isFinished()) {
                    listener.onDataAvailable();
                }
                if (isFinished()) {
                    listener.onAllDataRead();
                }
            } catch (IOException exception) {
                listener.onError(exception);
            }
        }
    }
}
