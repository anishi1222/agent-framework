// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

import java.util.List;

/**
 * Represents the policy decision for one content unit.
 *
 * @param blocked whether access must be blocked
 * @param protectionScopeModified whether cached scopes are stale
 * @param actions immutable service actions
 * @param requestId optional request identifier
 */
public record PurviewDecision(
        boolean blocked, boolean protectionScopeModified, List<PurviewPolicyAction> actions, String requestId) {
    /** Creates and defensively copies a decision. */
    public PurviewDecision {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    /** Returns a local allow decision. */
    public static PurviewDecision allow() {
        return new PurviewDecision(false, false, List.of(), null);
    }
}
