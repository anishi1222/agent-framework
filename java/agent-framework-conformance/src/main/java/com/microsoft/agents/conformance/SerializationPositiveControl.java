// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import java.util.Objects;

/**
 * Describes one valid raw state document that a conforming implementation must accept.
 *
 * @param schemaVersion serialization-corpus schema version
 * @param controlId stable independently addressable control identifier
 * @param documentKind target production reader
 * @param resource raw classpath resource; the corpus loader never parses it
 * @param limits named parser limits supplied to the production reader
 */
public record SerializationPositiveControl(
        int schemaVersion,
        String controlId,
        SerializationDocumentKind documentKind,
        String resource,
        SerializationLimits limits) {
    /** Validates positive-control metadata without opening or parsing the raw resource. */
    public SerializationPositiveControl {
        if (schemaVersion != 1) {
            throw new ConformanceValidationException(
                    "Unsupported serialization positive-control schemaVersion " + schemaVersion + ".");
        }
        FixtureValidation.requireNonBlank(controlId, "controlId");
        Objects.requireNonNull(documentKind, "documentKind");
        FixtureValidation.requireNonBlank(resource, "resource");
        Objects.requireNonNull(limits, "limits");
    }
}
