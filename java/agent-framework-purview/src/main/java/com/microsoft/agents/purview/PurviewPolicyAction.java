// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

/**
 * Represents a Purview DLP policy action.
 *
 * @param action optional service action value or normalized service subtype
 * @param restrictionAction optional restriction action
 */
public record PurviewPolicyAction(String action, String restrictionAction) {
    /** Creates a policy action. */
    public PurviewPolicyAction {
        if (action != null && action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank.");
        }
        if (restrictionAction != null && restrictionAction.isBlank()) {
            throw new IllegalArgumentException("restrictionAction must not be blank.");
        }
        if (action == null && restrictionAction == null) {
            throw new IllegalArgumentException("action or restrictionAction must be provided.");
        }
    }

    /** Reports whether the action requires blocking access. */
    public boolean blocksAccess() {
        return "blockAccess".equalsIgnoreCase(action) || "block".equalsIgnoreCase(restrictionAction);
    }
}
