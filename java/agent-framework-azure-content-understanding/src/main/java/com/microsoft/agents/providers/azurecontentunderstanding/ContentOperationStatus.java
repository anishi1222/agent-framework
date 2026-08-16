// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azurecontentunderstanding;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Represents an expandable Content Understanding operation state. */
public final class ContentOperationStatus {
    /** Operation has not started. */
    public static final ContentOperationStatus NOT_STARTED = new ContentOperationStatus("NotStarted");
    /** Operation is running. */
    public static final ContentOperationStatus RUNNING = new ContentOperationStatus("Running");
    /** Operation succeeded. */
    public static final ContentOperationStatus SUCCEEDED = new ContentOperationStatus("Succeeded");
    /** Operation failed. */
    public static final ContentOperationStatus FAILED = new ContentOperationStatus("Failed");
    /** Operation was cancelled locally or by the service. */
    public static final ContentOperationStatus CANCELLED = new ContentOperationStatus("Canceled");

    private static final Set<String> KNOWN =
            Set.of("notstarted", "running", "succeeded", "failed", "canceled", "cancelled");
    private final String value;

    private ContentOperationStatus(String value) {
        this.value = value;
    }

    /** Creates a known or future state. */
    public static ContentOperationStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank.");
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "notstarted" -> NOT_STARTED;
            case "running" -> RUNNING;
            case "succeeded" -> SUCCEEDED;
            case "failed" -> FAILED;
            case "canceled", "cancelled" -> CANCELLED;
            default -> new ContentOperationStatus(value);
        };
    }

    /** Returns the service value. */
    public String value() {
        return value;
    }

    /** Reports whether this value is known. */
    public boolean isKnown() {
        return KNOWN.contains(value.toLowerCase(Locale.ROOT));
    }

    /** Reports whether this is a known terminal state. */
    public boolean isTerminal() {
        return equals(SUCCEEDED) || equals(FAILED) || equals(CANCELLED);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ContentOperationStatus status && value.equalsIgnoreCase(status.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value.toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return value;
    }
}
