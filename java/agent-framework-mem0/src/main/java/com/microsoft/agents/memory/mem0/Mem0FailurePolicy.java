// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.memory.mem0;

/**
 * Controls whether eligible transient Mem0 failures fail the agent run.
 */
public enum Mem0FailurePolicy {
    /** Propagates every Mem0 failure to the agent runtime. */
    FAIL_RUN,

    /**
     * Continues without retrieval or persistence only for eligible transient failures.
     *
     * <p>Cancellation, validation, authentication, malformed-data, and partial-operation failures
     * always propagate.
     */
    CONTINUE_WITHOUT_MEMORY
}
