// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import java.net.URI;
import java.util.concurrent.CompletionStage;

/** Represents a running embedded A2A JSON-RPC/SSE server without exposing server-library types. */
public interface A2AHttpServer extends AutoCloseable {
    /**
     * Starts an embedded host.
     *
     * @param service A2A service
     * @param options host options
     * @return running server
     */
    static A2AHttpServer start(A2AService service, A2AHttpServerOptions options) {
        return EmbeddedA2AHttpServer.start(service, options);
    }

    /** Returns the JSON-RPC endpoint URI. */
    URI endpoint();

    /** Returns the public well-known card URI. */
    URI agentCardUri();

    /** Reports whether the server is accepting requests. */
    boolean isRunning();

    /** Stops accepting requests and closes active streams. */
    CompletionStage<Void> closeAsync();

    /** Closes the server synchronously. */
    @Override
    void close();
}
