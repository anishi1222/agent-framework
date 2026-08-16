// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

/** Identifies the entity referenced by an encrypted reasoning value. */
public enum AGUIReasoningEncryptedSubtype {
    /** Tool-call entity. */
    TOOL_CALL("tool-call"),
    /** Message entity. */
    MESSAGE("message");

    private final String value;

    AGUIReasoningEncryptedSubtype(String value) {
        this.value = value;
    }

    /**
     * Returns the exact wire value.
     *
     * @return wire value
     */
    public String value() {
        return value;
    }

    /**
     * Resolves an exact subtype.
     *
     * @param value wire value
     * @return subtype
     */
    public static AGUIReasoningEncryptedSubtype fromValue(String value) {
        for (AGUIReasoningEncryptedSubtype subtype : values()) {
            if (subtype.value.equals(value)) {
                return subtype;
            }
        }
        throw AGUIValidation.invalid("Unknown encrypted reasoning subtype.");
    }
}
