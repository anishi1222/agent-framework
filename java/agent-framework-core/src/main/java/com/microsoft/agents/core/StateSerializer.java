// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

/**
 * Encodes and decodes safe versioned framework state documents.
 */
public interface StateSerializer {
    /**
     * Encodes a state envelope as deterministic compact UTF-8 JSON.
     *
     * @param envelope state envelope
     * @return encoded JSON bytes
     * @throws SerializationException when the value or output violates the configured contract
     */
    byte[] write(StateEnvelope envelope);

    /**
     * Decodes one complete UTF-8 JSON document for the selected reader.
     *
     * @param utf8Json complete document bytes
     * @param expectedKind selected document reader
     * @return validated envelope
     * @throws SerializationException when parsing, limits, kind, or version validation fails
     */
    StateEnvelope read(byte[] utf8Json, DocumentKind expectedKind);

    /**
     * Returns the mandatory limits applied by this serializer.
     *
     * @return configured limits
     */
    SerializationLimits limits();
}
