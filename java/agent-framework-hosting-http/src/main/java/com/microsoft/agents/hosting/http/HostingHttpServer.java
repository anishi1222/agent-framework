// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import com.microsoft.agents.hosting.HostingDispatcher;
import java.net.URI;
import java.util.concurrent.CompletionStage;

/** Represents a running embedded Java-hosting HTTP/SSE/WebSocket server. */
public interface HostingHttpServer extends AutoCloseable {
    /**
     * Starts an embedded server.
     *
     * @param dispatcher hosting dispatcher
     * @param options transport options
     * @return running server
     */
    static HostingHttpServer start(HostingDispatcher dispatcher, HostingHttpServerOptions options) {
        return EmbeddedHostingHttpServer.start(dispatcher, options);
    }

    /**
     * Returns the advertised v1 HTTP API endpoint.
     *
     * @return endpoint
     */
    URI endpoint();

    /**
     * Returns the advertised WebSocket endpoint.
     *
     * @return endpoint
     */
    URI webSocketEndpoint();

    /**
     * Reports whether the server is accepting requests.
     *
     * @return running state
     */
    boolean isRunning();

    /**
     * Starts idempotent graceful shutdown.
     *
     * @return shutdown completion
     */
    CompletionStage<Void> closeAsync();

    /** Gracefully closes the server. */
    @Override
    void close();
}
