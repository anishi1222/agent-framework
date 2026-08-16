// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import java.util.EnumSet;
import java.util.Objects;

/**
 * Describes input and expected data for a non-event behavioral contract.
 *
 * @param schemaVersion fixture schema version
 * @param caseId stable conformance case identifier
 * @param kind explicit fixture kind
 * @param description behavior description
 * @param input implementation-neutral input data
 * @param expected observable expected data
 */
public record BehaviorFixture(
        int schemaVersion,
        String caseId,
        FixtureKind kind,
        String description,
        ConformanceValue.ObjectValue input,
        ConformanceValue.ObjectValue expected)
        implements ConformanceFixture {
    private static final EnumSet<FixtureKind> SUPPORTED_KINDS =
            EnumSet.of(FixtureKind.CONTRACT, FixtureKind.MESSAGE_CONTENT, FixtureKind.RESPONSE_AGGREGATION);

    /** Creates and validates a behavior fixture. */
    public BehaviorFixture {
        FixtureValidation.validateCommon(schemaVersion, caseId, description);
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(expected, "expected");
        if (!SUPPORTED_KINDS.contains(kind)) {
            throw new ConformanceValidationException("Fixture kind " + kind + " is not a behavior fixture.");
        }
    }
}
