// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import com.microsoft.agents.core.RunCancellation;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Exposes one streaming run's events, terminal outcome, and explicit cancellation.
 *
 * @param runId active-run identifier
 * @param events cold single-subscriber event publisher
 * @param terminalAsync terminal outcome stage
 * @param cancellation cancellation controller
 */
public record HostingRun(
        String runId,
        Flow.Publisher<HostingEvent> events,
        CompletionStage<HostingOutcome> terminalAsync,
        RunCancellation cancellation) {
    /** Creates a validated run view. */
    public HostingRun {
        runId = HostingValidation.nonBlank(runId, "runId");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(terminalAsync, "terminalAsync");
        Objects.requireNonNull(cancellation, "cancellation");
    }

    /**
     * Requests cancellation.
     *
     * @return {@code true} only when this call initiated cancellation
     */
    public boolean cancel() {
        return cancellation.cancel();
    }
}
