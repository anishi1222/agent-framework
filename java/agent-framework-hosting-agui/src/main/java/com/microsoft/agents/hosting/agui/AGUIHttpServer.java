// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import java.net.URI;
import java.util.concurrent.CompletionStage;

/** Represents a running loopback-first JDK AG-UI HTTP/SSE server. */
public interface AGUIHttpServer extends AutoCloseable {
    /**
     * Starts a server with the handler's shared generic transport options.
     *
     * @param handler AG-UI handler
     * @return running server
     */
    static AGUIHttpServer start(AGUIHostingHttpHandler handler) {
        return JdkAGUIHttpServer.start(handler);
    }

    /**
     * Returns the advertised HTTP origin or configured base endpoint.
     *
     * @return advertised URI
     */
    URI endpoint();

    /**
     * Reports whether requests are accepted.
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

    /** Gracefully closes the listener and handler. */
    @Override
    void close();
}
