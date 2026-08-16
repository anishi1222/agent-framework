// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import java.util.Objects;

/**
 * Describes one raw state-reader input that a conforming implementation must reject.
 *
 * @param schemaVersion rejection-corpus schema version
 * @param caseId stable independently addressable case identifier
 * @param documentKind target production reader
 * @param reason expected portable rejection category
 * @param resource raw classpath resource; the corpus loader never parses it
 * @param limits named parser limits supplied to the production reader
 */
public record SerializationRejectionCase(
        int schemaVersion,
        String caseId,
        SerializationDocumentKind documentKind,
        SerializationRejectionReason reason,
        String resource,
        SerializationLimits limits) {
    /** Validates rejection case metadata without opening or parsing the raw resource. */
    public SerializationRejectionCase {
        if (schemaVersion != 1) {
            throw new ConformanceValidationException(
                    "Unsupported serialization rejection case schemaVersion " + schemaVersion + ".");
        }
        FixtureValidation.requireNonBlank(caseId, "caseId");
        Objects.requireNonNull(documentKind, "documentKind");
        Objects.requireNonNull(reason, "reason");
        FixtureValidation.requireNonBlank(resource, "resource");
        Objects.requireNonNull(limits, "limits");
    }
}
