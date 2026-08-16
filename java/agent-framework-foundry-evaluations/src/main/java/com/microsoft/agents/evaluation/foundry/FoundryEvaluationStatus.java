// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation.foundry;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Represents an expandable Foundry evaluation status. */
public final class FoundryEvaluationStatus {
    /** Queued evaluation. */
    public static final FoundryEvaluationStatus QUEUED = new FoundryEvaluationStatus("queued");
    /** Running evaluation. */
    public static final FoundryEvaluationStatus IN_PROGRESS = new FoundryEvaluationStatus("in_progress");
    /** Completed evaluation. */
    public static final FoundryEvaluationStatus COMPLETED = new FoundryEvaluationStatus("completed");
    /** Failed evaluation. */
    public static final FoundryEvaluationStatus FAILED = new FoundryEvaluationStatus("failed");
    /** Cancelled evaluation. */
    public static final FoundryEvaluationStatus CANCELLED = new FoundryEvaluationStatus("cancelled");

    private static final Set<String> KNOWN =
            Set.of("queued", "in_progress", "completed", "failed", "cancelled", "canceled");
    private final String value;

    private FoundryEvaluationStatus(String value) {
        this.value = value;
    }

    /** Creates a known or future status from a service value. */
    public static FoundryEvaluationStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank.");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "queued" -> QUEUED;
            case "in_progress" -> IN_PROGRESS;
            case "completed" -> COMPLETED;
            case "failed" -> FAILED;
            case "cancelled", "canceled" -> CANCELLED;
            default -> new FoundryEvaluationStatus(normalized);
        };
    }

    /** Returns the service status value. */
    public String value() {
        return value;
    }

    /** Reports whether the status is known to the adapter. */
    public boolean isKnown() {
        return KNOWN.contains(value);
    }

    /** Reports whether the status is a known terminal state. */
    public boolean isTerminal() {
        return equals(COMPLETED) || equals(FAILED) || equals(CANCELLED);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof FoundryEvaluationStatus status && value.equals(status.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
