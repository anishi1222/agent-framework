// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

import com.microsoft.agents.core.StateValue;

/**
 * Describes a card action that must be explicitly returned by application code.
 *
 * @param type action type
 * @param title optional display title
 * @param id optional action identity
 * @param value JSON-shaped action value
 */
public record CopilotStudioCardAction(String type, String title, String id, StateValue value) {
    /** Creates a validated action descriptor. */
    public CopilotStudioCardAction {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank.");
        }
        value = value == null ? StateValue.nullValue() : value;
    }
}
