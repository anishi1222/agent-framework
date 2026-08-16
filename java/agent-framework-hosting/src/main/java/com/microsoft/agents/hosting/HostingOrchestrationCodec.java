// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.orchestrations.OrchestrationContinuation;
import com.microsoft.agents.orchestrations.OrchestrationResumeInput;

/**
 * Converts orchestration terminal output and process-local resume input at the hosting boundary.
 *
 * @param <O> orchestration output type
 */
public interface HostingOrchestrationCodec<O> {
    /**
     * Encodes terminal output as an immutable JSON-shaped value.
     *
     * @param output orchestration output
     * @return encoded output
     */
    StateValue encodeOutput(O output);

    /**
     * Converts a validated generic hosting resume request into the continuation's typed input.
     *
     * @param continuation process-local orchestration continuation
     * @param request generic hosting resume request
     * @return typed resume input
     */
    OrchestrationResumeInput decodeResumeInput(OrchestrationContinuation continuation, HostingResumeRequest request);
}
