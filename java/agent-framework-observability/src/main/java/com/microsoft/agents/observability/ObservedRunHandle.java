// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import java.util.concurrent.CompletionStage;

record ObservedRunHandle<T>(CompletionStage<T> resultAsync, RunCancellation cancellation) implements RunHandle<T> {
    ObservedRunHandle {
        if (resultAsync == null) {
            throw new NullPointerException("resultAsync");
        }
        if (cancellation == null) {
            throw new NullPointerException("cancellation");
        }
    }
}
