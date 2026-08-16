// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.EncodedState;
import java.util.Objects;

record FunctionalCachedStep(EncodedState output, int autoRequestCount) {
    FunctionalCachedStep {
        Objects.requireNonNull(output, "output");
        if (autoRequestCount < 0) {
            throw new WorkflowValidationException("autoRequestCount must not be negative.");
        }
    }
}
