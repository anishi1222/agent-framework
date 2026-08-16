// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.workflows.WorkflowRunOptions;

/**
 * Converts between the safe hosted value model and one workflow's typed input and output.
 *
 * @param <I> workflow input type
 * @param <O> workflow output type
 */
public interface HostingWorkflowCodec<I, O> {
    /**
     * Decodes one hosted request into typed workflow input.
     *
     * @param request hosted request
     * @return workflow input
     */
    I decodeInput(HostingRunRequest request);

    /**
     * Encodes one terminal workflow output.
     *
     * @param output workflow output
     * @return safe JSON-shaped result
     */
    StateValue encodeOutput(O output);

    /**
     * Applies route-specific workflow options such as real checkpoint storage.
     *
     * <p>The default leaves generic hosting options unchanged. Implementations must not fabricate a
     * checkpoint or input-required boundary.
     *
     * @param request hosted request
     * @param builder initialized run-options builder
     * @return configured builder
     */
    default WorkflowRunOptions.Builder configureOptions(HostingRunRequest request, WorkflowRunOptions.Builder builder) {
        return builder;
    }
}
