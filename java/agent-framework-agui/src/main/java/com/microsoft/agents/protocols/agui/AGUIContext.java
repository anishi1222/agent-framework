// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

/**
 * Represents one AG-UI context entry.
 *
 * @param description context description
 * @param value context value
 */
public record AGUIContext(String description, String value) {
    /** Creates a validated context entry. */
    public AGUIContext {
        description = AGUIValidation.nonBlank(description, "description");
        java.util.Objects.requireNonNull(value, "value");
    }
}
