// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.core.StateValue;
import java.util.Objects;

/**
 * Represents an explicit user decision for an MCP elicitation.
 *
 * @param action decision
 * @param content accepted form content, or {@code null}
 */
public record MCPElicitationResult(Action action, StateValue.ObjectValue content) {
    /** Creates an immutable elicitation result. */
    public MCPElicitationResult {
        Objects.requireNonNull(action, "action");
        if (action == Action.ACCEPT && content == null) {
            throw new com.microsoft.agents.core.ValidationException("accepted elicitation requires content.");
        }
        if (action != Action.ACCEPT && content != null) {
            throw new com.microsoft.agents.core.ValidationException(
                    "declined or cancelled elicitation must not contain content.");
        }
    }

    /**
     * Defines elicitation decisions.
     */
    public enum Action {
        /** The user accepted and supplied content. */
        ACCEPT,
        /** The user declined the request. */
        DECLINE,
        /** The interaction was cancelled. */
        CANCEL
    }
}
