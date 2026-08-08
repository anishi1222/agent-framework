// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.StateValue;
import java.util.Map;

/** Represents one text, file, or structured-data A2A content part. */
public sealed interface Part permits DataPart, FilePart, TextPart {
    /**
     * Returns immutable additive metadata.
     *
     * @return metadata
     */
    Map<String, StateValue> metadata();

    /**
     * Returns the effective media type.
     *
     * @return media type
     */
    String mediaType();
}
