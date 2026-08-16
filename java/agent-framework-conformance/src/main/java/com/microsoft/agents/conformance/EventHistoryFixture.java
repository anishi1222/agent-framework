// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Describes an ordered tool, run-signal, or workflow event history.
 *
 * @param schemaVersion fixture schema version
 * @param caseId stable conformance case identifier
 * @param kind explicit fixture kind
 * @param description behavior description
 * @param events ordered language-neutral events
 * @param expected observable expected data
 */
public record EventHistoryFixture(
        int schemaVersion,
        String caseId,
        FixtureKind kind,
        String description,
        List<ConformanceValue.ObjectValue> events,
        ConformanceValue.ObjectValue expected)
        implements ConformanceFixture {
    private static final EnumSet<FixtureKind> SUPPORTED_KINDS =
            EnumSet.of(FixtureKind.TOOL_LOOP, FixtureKind.RUN_SIGNAL, FixtureKind.WORKFLOW_TRACE);

    /** Creates and validates an event-history fixture. */
    public EventHistoryFixture {
        FixtureValidation.validateCommon(schemaVersion, caseId, description);
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(expected, "expected");
        if (!SUPPORTED_KINDS.contains(kind)) {
            throw new ConformanceValidationException("Fixture kind " + kind + " is not an event-history fixture.");
        }
        events = List.copyOf(events);
        if (events.isEmpty()) {
            throw new ConformanceValidationException("events must not be empty.");
        }
    }
}
