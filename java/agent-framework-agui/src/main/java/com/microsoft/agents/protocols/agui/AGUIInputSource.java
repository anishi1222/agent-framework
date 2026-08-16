// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

/** Represents one inline-data or URL source for AG-UI multimodal input. */
public sealed interface AGUIInputSource permits AGUIMessages.InputSource {
    /**
     * Returns the exact source discriminator.
     *
     * @return {@code data} or {@code url}
     */
    String type();

    /**
     * Returns the source value.
     *
     * @return base64 data or URL text
     */
    String value();
}
