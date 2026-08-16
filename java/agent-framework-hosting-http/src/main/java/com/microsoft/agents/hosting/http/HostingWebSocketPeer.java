// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import java.util.concurrent.CompletionStage;

/** Represents server-to-peer WebSocket operations without exposing a WebSocket library type. */
public interface HostingWebSocketPeer {
    /**
     * Sends one complete text message.
     *
     * @param text UTF-8 text
     * @return send completion
     */
    CompletionStage<Void> sendTextAsync(String text);

    /**
     * Sends one ping control frame.
     *
     * @param payload bounded ping payload
     * @return send completion
     */
    CompletionStage<Void> pingAsync(byte[] payload);

    /**
     * Closes the connection.
     *
     * @param code RFC 6455 close code
     * @param reason short safe reason
     * @return close completion
     */
    CompletionStage<Void> closeAsync(int code, String reason);
}
