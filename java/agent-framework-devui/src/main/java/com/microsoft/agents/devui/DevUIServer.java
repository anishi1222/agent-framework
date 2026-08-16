// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.devui;

import com.microsoft.agents.hosting.HostingDispatcher;
import java.net.URI;
import java.util.concurrent.CompletionStage;

/** Represents a running opt-in developer UI and same-origin generic hosting endpoint. */
public interface DevUIServer extends AutoCloseable {
    /** Stable developer UI base path. */
    String UI_PATH = "/devui/";

    /** Stable same-origin browser configuration path. */
    String CONFIG_PATH = "/devui/config.json";

    /** Stable generic hosting API base path. */
    String API_PATH = "/v1";

    /**
     * Starts an embedded developer UI.
     *
     * @param dispatcher generic hosting dispatcher
     * @param options developer UI options
     * @return running server
     */
    static DevUIServer start(HostingDispatcher dispatcher, DevUIServerOptions options) {
        return EmbeddedDevUIServer.start(dispatcher, options);
    }

    /**
     * Returns the advertised developer UI endpoint.
     *
     * @return developer UI endpoint
     */
    URI endpoint();

    /**
     * Returns the same-origin generic hosting API endpoint.
     *
     * @return generic hosting API endpoint
     */
    URI apiEndpoint();

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

    /** Gracefully closes the listener and its transport resources. */
    @Override
    void close();
}
