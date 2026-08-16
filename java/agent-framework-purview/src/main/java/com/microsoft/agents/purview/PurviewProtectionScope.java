// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

import java.util.List;
import java.util.Set;

/**
 * Represents one protection scope.
 *
 * @param activities protected activities
 * @param executionMode inline or offline mode
 * @param locations protected application locations
 * @param policyActions immutable scope actions
 */
public record PurviewProtectionScope(
        Set<PurviewActivity> activities,
        PurviewExecutionMode executionMode,
        List<PurviewAppLocation> locations,
        List<PurviewPolicyAction> policyActions) {
    /** Creates and defensively copies a scope. */
    public PurviewProtectionScope {
        activities = activities == null ? Set.of() : Set.copyOf(activities);
        executionMode = java.util.Objects.requireNonNull(executionMode, "executionMode");
        locations = locations == null ? List.of() : List.copyOf(locations);
        policyActions = policyActions == null ? List.of() : List.copyOf(policyActions);
    }
}
