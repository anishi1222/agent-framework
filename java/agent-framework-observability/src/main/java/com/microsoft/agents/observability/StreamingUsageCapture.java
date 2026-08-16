// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import com.microsoft.agents.core.UsageDetails;
import java.math.BigInteger;

final class StreamingUsageCapture {
    private BigInteger inputTokens;

    private BigInteger outputTokens;

    synchronized void add(UsageDetails update) {
        if (update == null) {
            return;
        }
        inputTokens = add(inputTokens, update.inputTokens().orElse(null));
        outputTokens = add(outputTokens, update.outputTokens().orElse(null));
    }

    synchronized UsageDetails value() {
        if (inputTokens == null && outputTokens == null) {
            return null;
        }
        UsageDetails.Builder builder = UsageDetails.builder();
        if (inputTokens != null) {
            builder.integer(UsageDetails.INPUT_TOKENS, inputTokens);
        }
        if (outputTokens != null) {
            builder.integer(UsageDetails.OUTPUT_TOKENS, outputTokens);
        }
        return builder.build();
    }

    private static BigInteger add(BigInteger current, BigInteger update) {
        if (update == null) {
            return current;
        }
        return current == null ? update : current.add(update);
    }
}
