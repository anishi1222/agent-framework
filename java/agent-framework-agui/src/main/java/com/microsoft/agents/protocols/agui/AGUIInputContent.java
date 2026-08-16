// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

/** Represents one text, media, document, or legacy binary user input part. */
public sealed interface AGUIInputContent permits AGUIMessages.Input {
    /**
     * Returns the exact input-content discriminator.
     *
     * @return discriminator
     */
    String type();
}
