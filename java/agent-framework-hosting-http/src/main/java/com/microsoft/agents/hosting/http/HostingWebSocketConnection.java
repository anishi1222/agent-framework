// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

/** Handles typed client frames for one authenticated hosting WebSocket connection. */
public interface HostingWebSocketConnection extends AutoCloseable {
    /**
     * Accepts one complete bounded text message.
     *
     * @param text client frame
     */
    void receiveText(String text);

    /** Records receipt of a pong control frame. */
    void receivePong();

    /** Cancels the active operation after peer disconnect without sending another frame. */
    void peerClosed();

    /**
     * Reports whether the protocol connection remains open.
     *
     * @return open state
     */
    boolean isOpen();

    /** Closes the protocol connection and cancels its active operation. */
    @Override
    void close();
}
