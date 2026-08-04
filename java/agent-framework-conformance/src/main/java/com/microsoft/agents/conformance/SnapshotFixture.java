// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import java.util.List;
import java.util.Objects;

/**
 * Describes a Java versioned state envelope and deterministic store operations.
 *
 * @param schemaVersion fixture schema version
 * @param caseId stable conformance case identifier
 * @param kind explicit fixture kind
 * @param description behavior description
 * @param envelope Java state envelope
 * @param operations ordered storage operations
 * @param expected observable expected data
 */
public record SnapshotFixture(
        int schemaVersion,
        String caseId,
        FixtureKind kind,
        String description,
        ConformanceValue.ObjectValue envelope,
        List<ConformanceValue.ObjectValue> operations,
        ConformanceValue.ObjectValue expected)
        implements ConformanceFixture {
    /** Creates and validates a snapshot fixture. */
    public SnapshotFixture {
        FixtureValidation.validateCommon(schemaVersion, caseId, description);
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(operations, "operations");
        Objects.requireNonNull(expected, "expected");
        if (kind != FixtureKind.SESSION_SNAPSHOT) {
            throw new ConformanceValidationException("Fixture kind " + kind + " is not a snapshot fixture.");
        }
        operations = List.copyOf(operations);
    }
}
