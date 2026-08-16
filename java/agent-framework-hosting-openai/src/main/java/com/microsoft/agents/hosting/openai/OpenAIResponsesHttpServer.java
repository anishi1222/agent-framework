// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.openai;

import java.net.URI;
import java.util.concurrent.CompletionStage;

/** Represents a running loopback-first JDK OpenAI Responses HTTP and SSE server. */
public interface OpenAIResponsesHttpServer extends AutoCloseable {
    /**
     * Starts a server with the handler's shared generic transport options.
     *
     * @param handler OpenAI Responses handler
     * @return running server
     */
    static OpenAIResponsesHttpServer start(OpenAIResponsesHttpHandler handler) {
        return JdkOpenAIResponsesHttpServer.start(handler);
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
