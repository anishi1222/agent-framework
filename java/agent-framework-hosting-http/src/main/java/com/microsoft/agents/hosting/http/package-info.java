// Copyright (c) Microsoft. All rights reserved.

/**
 * Provides the stable nonstandard Java-hosting v1 HTTP, SSE, and typed WebSocket transport.
 *
 * <p>The embedded server binds to loopback by default. Non-loopback operation requires an explicit
 * trusted TLS proxy contract, application authenticator, HTTPS advertised origin, and Host and
 * Origin allowlists. SSE has no heartbeat or Last-Event-ID replay; disconnect and idle timeout
 * cancel the process-local run. The exact WebSocket subprotocol is
 * {@code agent-framework-hosting.v1}.
 */
package com.microsoft.agents.hosting.http;
