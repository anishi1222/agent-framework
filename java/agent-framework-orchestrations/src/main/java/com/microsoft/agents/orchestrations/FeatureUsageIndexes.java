// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.FeatureUsageIndex;
import com.microsoft.agents.core.FeatureUsageRegistry;

final class FeatureUsageIndexes {
    static final FeatureUsageIndex SEQUENTIAL = new FeatureUsageIndex(32, "orchestration.sequential");

    static final FeatureUsageIndex CONCURRENT = new FeatureUsageIndex(33, "orchestration.concurrent");

    static final FeatureUsageIndex GROUP_CHAT = new FeatureUsageIndex(34, "orchestration.group_chat");

    static final FeatureUsageIndex MAGENTIC = new FeatureUsageIndex(35, "orchestration.magentic");

    static final FeatureUsageIndex HANDOFF = new FeatureUsageIndex(36, "orchestration.handoff");

    private FeatureUsageIndexes() {}

    static void markUsed(FeatureUsageIndex index) {
        FeatureUsageRegistry.global().markUsed(index);
    }
}
