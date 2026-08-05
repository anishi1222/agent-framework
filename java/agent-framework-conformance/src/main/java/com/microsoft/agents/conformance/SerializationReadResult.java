// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import java.util.Objects;

/** Typed outcome returned by a production serialization-reader test adapter. */
public sealed interface SerializationReadResult
        permits SerializationReadResult.Accepted, SerializationReadResult.Rejected {
    /** Indicates that the selected reader accepted the document. */
    record Accepted() implements SerializationReadResult {}

    /**
     * Indicates that the selected reader rejected the document for a portable reason.
     *
     * @param reason mapped portable rejection category
     */
    record Rejected(SerializationRejectionReason reason) implements SerializationReadResult {
        /** Creates a typed rejection. */
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * Creates an accepted result.
     *
     * @return accepted result
     */
    static Accepted accepted() {
        return new Accepted();
    }

    /**
     * Creates a rejected result.
     *
     * @param reason mapped portable rejection category
     * @return rejected result
     */
    static Rejected rejected(SerializationRejectionReason reason) {
        return new Rejected(reason);
    }
}
