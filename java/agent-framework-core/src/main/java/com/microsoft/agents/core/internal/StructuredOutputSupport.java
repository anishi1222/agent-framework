// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core.internal;

import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StructuredOutputOptions;
import com.microsoft.agents.core.ValidationException;
import java.util.Objects;

/**
 * Resolves provider-neutral structured output with a temporary provider-metadata compatibility
 * key.
 */
public final class StructuredOutputSupport {
    private StructuredOutputSupport() {}

    /**
     * Resolves structured output and rejects ambiguous dual configuration.
     *
     * @param options chat options
     * @param legacyMetadataKey provider-specific schema metadata key
     * @return resolved structured output, or {@code null}
     */
    public static StructuredOutputOptions resolve(ChatOptions options, String legacyMetadataKey) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(legacyMetadataKey, "legacyMetadataKey");
        StateValue legacy = options.metadata().get(legacyMetadataKey);
        if (options.structuredOutput() != null && legacy != null) {
            throw new ValidationException("Configure structuredOutput or " + legacyMetadataKey + ", but not both.");
        }
        if (options.structuredOutput() != null) {
            return options.structuredOutput();
        }
        if (legacy == null) {
            return null;
        }
        if (!(legacy instanceof StateValue.ObjectValue schema)) {
            throw new ValidationException(legacyMetadataKey + " must be a JSON object.");
        }
        return new StructuredOutputOptions("response", "Agent Framework structured response", schema, true);
    }
}
