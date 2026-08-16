// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import com.microsoft.agents.core.RunCancellation;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Executes one A2A task through a framework agent or workflow adapter. */
public interface A2AExecutor {
    /**
     * Executes a request and emits task updates.
     *
     * @param context execution context
     * @param sink ordered event sink
     * @param cancellation cancellation signal
     * @return boundary completion
     */
    CompletionStage<Void> executeAsync(A2AExecutionContext context, A2AEventSink sink, RunCancellation cancellation);

    /**
     * Gives the adapter an opportunity to interrupt external work.
     *
     * @param context execution context
     * @return cancellation completion
     */
    default CompletionStage<Void> cancelAsync(A2AExecutionContext context) {
        return CompletableFuture.completedFuture(null);
    }
}
