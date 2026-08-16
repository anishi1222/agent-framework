// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

/** Identifies one role from the current AG-UI message schema. */
public enum AGUIRole {
    /** Developer instruction. */
    DEVELOPER("developer"),
    /** System instruction. */
    SYSTEM("system"),
    /** Assistant output. */
    ASSISTANT("assistant"),
    /** End-user input. */
    USER("user"),
    /** Tool result. */
    TOOL("tool"),
    /** Frontend-only structured activity. */
    ACTIVITY("activity"),
    /** Reasoning message. */
    REASONING("reasoning");

    private final String value;

    AGUIRole(String value) {
        this.value = value;
    }

    /**
     * Returns the exact lower-case wire value.
     *
     * @return wire value
     */
    public String value() {
        return value;
    }

    /**
     * Resolves an exact role wire value.
     *
     * @param value wire value
     * @return role
     */
    public static AGUIRole fromValue(String value) {
        for (AGUIRole role : values()) {
            if (role.value.equals(value)) {
                return role;
            }
        }
        throw AGUIValidation.invalid("Unknown AG-UI role.");
    }
}
