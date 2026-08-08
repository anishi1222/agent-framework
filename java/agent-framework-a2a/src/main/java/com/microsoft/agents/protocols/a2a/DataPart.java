// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.StateValue;
import java.util.Map;
import java.util.Objects;

/**
 * Represents JSON-shaped structured data.
 *
 * @param data immutable JSON value
 * @param mediaType structured-data media type
 * @param metadata immutable additive metadata
 */
public record DataPart(StateValue data, String mediaType, Map<String, StateValue> metadata) implements Part {
    /** Creates validated structured data. */
    public DataPart {
        data = Objects.requireNonNull(data, "data");
        mediaType = A2AValidation.nonBlank(mediaType, "mediaType");
        metadata = A2AValidation.metadata(metadata, "metadata");
    }

    /**
     * Creates JSON data without metadata.
     *
     * @param data JSON-shaped value
     */
    public DataPart(StateValue data) {
        this(data, "application/json", Map.of());
    }
}
