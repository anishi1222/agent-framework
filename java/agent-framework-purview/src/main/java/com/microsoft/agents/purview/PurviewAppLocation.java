// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

/**
 * Identifies the protected application location.
 *
 * @param type location type
 * @param value non-blank location value
 */
public record PurviewAppLocation(PurviewLocationType type, String value) {
    /** Creates and validates an application location. */
    public PurviewAppLocation {
        type = java.util.Objects.requireNonNull(type, "type");
        if (value == null || value.isBlank() || value.length() > 2048) {
            throw new IllegalArgumentException("value must contain 1 to 2048 characters.");
        }
    }
}
