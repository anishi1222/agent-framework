// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.Message;
import java.util.List;
import java.util.Objects;

/**
 * Represents an immutable Magentic task and progress ledger snapshot.
 *
 * @param originalInput immutable original input
 * @param transcript immutable deterministic conversation
 * @param plan optional current plan
 * @param assessments immutable assessments in iteration order
 * @param iteration completed participant iterations
 * @param stallCount current consecutive stall count
 * @param replanCount completed replan count
 */
public record MagenticLedger(
        List<Message> originalInput,
        List<Message> transcript,
        MagenticPlan plan,
        List<MagenticProgressAssessment> assessments,
        int iteration,
        int stallCount,
        int replanCount) {
    /** Creates a validated immutable ledger snapshot. */
    public MagenticLedger {
        originalInput = OrchestrationValidation.copyMessages(originalInput);
        transcript = OrchestrationValidation.copyMessages(transcript);
        assessments = List.copyOf(Objects.requireNonNull(assessments, "assessments"));
        if (assessments.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("assessments contains null");
        }
        if (iteration < 0 || stallCount < 0 || replanCount < 0) {
            throw new IllegalArgumentException("iteration, stallCount, and replanCount must not be negative.");
        }
    }
}
