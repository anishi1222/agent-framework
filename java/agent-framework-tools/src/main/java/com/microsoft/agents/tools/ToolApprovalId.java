// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

/**
 * Identifies one stable approval authority.
 *
 * @param value non-blank stable identifier
 */
public record ToolApprovalId(String value) {
    /** Creates a validated approval identifier. */
    public ToolApprovalId {
        value = ToolValidation.requireNonBlank(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}
