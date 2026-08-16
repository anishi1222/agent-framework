// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Represents an expandable persistent-run status without treating unknown values as success. */
public final class PersistentRunStatus {
    /** Queued run. */
    public static final PersistentRunStatus QUEUED = new PersistentRunStatus("queued");
    /** Running run. */
    public static final PersistentRunStatus IN_PROGRESS = new PersistentRunStatus("in_progress");
    /** Run waiting for tool output or another continuation. */
    public static final PersistentRunStatus REQUIRES_ACTION = new PersistentRunStatus("requires_action");
    /** Run cancellation in progress. */
    public static final PersistentRunStatus CANCELLING = new PersistentRunStatus("cancelling");
    /** Cancelled run. */
    public static final PersistentRunStatus CANCELLED = new PersistentRunStatus("cancelled");
    /** Failed run. */
    public static final PersistentRunStatus FAILED = new PersistentRunStatus("failed");
    /** Completed run. */
    public static final PersistentRunStatus COMPLETED = new PersistentRunStatus("completed");
    /** Expired run. */
    public static final PersistentRunStatus EXPIRED = new PersistentRunStatus("expired");

    private static final Set<String> KNOWN = Set.of(
            QUEUED.value,
            IN_PROGRESS.value,
            REQUIRES_ACTION.value,
            CANCELLING.value,
            CANCELLED.value,
            FAILED.value,
            COMPLETED.value,
            EXPIRED.value);

    private final String value;

    private PersistentRunStatus(String value) {
        this.value = value;
    }

    /**
     * Creates a known or future status.
     *
     * @param value service value
     * @return status
     */
    public static PersistentRunStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank.");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "queued" -> QUEUED;
            case "in_progress" -> IN_PROGRESS;
            case "requires_action" -> REQUIRES_ACTION;
            case "cancelling" -> CANCELLING;
            case "cancelled" -> CANCELLED;
            case "failed" -> FAILED;
            case "completed" -> COMPLETED;
            case "expired" -> EXPIRED;
            default -> new PersistentRunStatus(normalized);
        };
    }

    /**
     * Returns the service value.
     *
     * @return status value
     */
    public String value() {
        return value;
    }

    /**
     * Reports whether the status is a known terminal service status.
     *
     * @return terminal indicator
     */
    public boolean isTerminal() {
        return this.equals(COMPLETED)
                || this.equals(CANCELLED)
                || this.equals(FAILED)
                || this.equals(EXPIRED)
                || this.equals(REQUIRES_ACTION);
    }

    /**
     * Reports whether this is a status known by the pinned SDK.
     *
     * @return known indicator
     */
    public boolean isKnown() {
        return KNOWN.contains(value);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof PersistentRunStatus status && value.equals(status.value);
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
