// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Objects;

/**
 * Wraps one versioned framework state payload.
 *
 * @param format stable format identifier
 * @param documentKind document schema discriminator
 * @param payloadVersion positive document payload version
 * @param payload JSON-shaped document payload
 */
public record StateEnvelope(String format, DocumentKind documentKind, int payloadVersion, StateValue payload) {
    /** Stable Java state format identifier. */
    public static final String FORMAT = "agent-framework-java-state";

    /** Creates a validated state envelope. */
    public StateEnvelope {
        if (!FORMAT.equals(format)) {
            throw new ValidationException("format must be '" + FORMAT + "'.");
        }
        Objects.requireNonNull(documentKind, "documentKind");
        if (payloadVersion <= 0) {
            throw new ValidationException("payloadVersion must be greater than zero.");
        }
        Objects.requireNonNull(payload, "payload");
    }

    /**
     * Creates an envelope using the stable format identifier.
     *
     * @param documentKind document schema discriminator
     * @param payloadVersion positive document payload version
     * @param payload JSON-shaped payload
     * @return state envelope
     */
    public static StateEnvelope of(DocumentKind documentKind, int payloadVersion, StateValue payload) {
        return new StateEnvelope(FORMAT, documentKind, payloadVersion, payload);
    }
}
