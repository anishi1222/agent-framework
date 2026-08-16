// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.FeatureUsageIndex;
import com.microsoft.agents.core.FeatureUsageRegistry;

final class FeatureUsageIndexes {
    static final FeatureUsageIndex CORE_AGENT = new FeatureUsageIndex(0, "core.agent");

    private FeatureUsageIndexes() {}

    static void markCoreAgentUsed() {
        FeatureUsageRegistry.global().markUsed(CORE_AGENT);
    }
}
