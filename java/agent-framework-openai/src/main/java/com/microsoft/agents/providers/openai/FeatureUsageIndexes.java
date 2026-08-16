// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import com.microsoft.agents.core.FeatureUsageIndex;
import com.microsoft.agents.core.FeatureUsageRegistry;

final class FeatureUsageIndexes {
    static final FeatureUsageIndex OPENAI = new FeatureUsageIndex(56, "openai");

    private FeatureUsageIndexes() {}

    static void markOpenAiUsed() {
        FeatureUsageRegistry.global().markUsed(OPENAI);
    }
}
