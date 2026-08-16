// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.FeatureUsageIndex;
import com.microsoft.agents.core.FeatureUsageRegistry;

final class FeatureUsageIndexes {
    static final FeatureUsageIndex CORE_WORKFLOW = new FeatureUsageIndex(2, "core.workflow");

    private FeatureUsageIndexes() {}

    static void markCoreWorkflowUsed() {
        FeatureUsageRegistry.global().markUsed(CORE_WORKFLOW);
    }
}
