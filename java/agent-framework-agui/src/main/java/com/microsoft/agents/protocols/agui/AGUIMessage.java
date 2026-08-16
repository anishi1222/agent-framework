// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

/** Represents one immutable message accepted by the AG-UI 0.0.57 wire schema. */
public sealed interface AGUIMessage permits AGUIMessages.Message {
    /**
     * Returns the stable message identifier.
     *
     * @return identifier
     */
    String id();

    /**
     * Returns the message role.
     *
     * @return role
     */
    AGUIRole role();
}
