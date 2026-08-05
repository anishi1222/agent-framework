// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import java.util.List;
import java.util.Objects;

/**
 * Describes a versioned Java workflow-checkpoint envelope and deterministic resume behavior.
 *
 * @param schemaVersion fixture schema version
 * @param caseId stable conformance case identifier
 * @param kind explicit fixture kind
 * @param description behavior description
 * @param envelope Java workflow-checkpoint envelope
 * @param encoded deterministic UTF-8 JSON text
 * @param resumeEvents ordered events after checkpoint restoration
 * @param expected observable expected data
 */
public record WorkflowCheckpointFixture(
        int schemaVersion,
        String caseId,
        FixtureKind kind,
        String description,
        ConformanceValue.ObjectValue envelope,
        String encoded,
        List<ConformanceValue.ObjectValue> resumeEvents,
        ConformanceValue.ObjectValue expected)
        implements ConformanceFixture {
    /** Creates and validates a workflow-checkpoint fixture. */
    public WorkflowCheckpointFixture {
        FixtureValidation.validateCommon(schemaVersion, caseId, description);
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(encoded, "encoded");
        Objects.requireNonNull(resumeEvents, "resumeEvents");
        Objects.requireNonNull(expected, "expected");
        if (kind != FixtureKind.WORKFLOW_CHECKPOINT) {
            throw new ConformanceValidationException("Fixture kind " + kind + " is not a workflow-checkpoint fixture.");
        }
        if (encoded.isBlank()) {
            throw new ConformanceValidationException("encoded must not be blank.");
        }
        resumeEvents = List.copyOf(resumeEvents);
        if (resumeEvents.isEmpty()) {
            throw new ConformanceValidationException("resumeEvents must not be empty.");
        }
    }
}
