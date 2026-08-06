// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/** Identifies why an orchestration reached a terminal domain result. */
public enum OrchestrationTerminationReason {
    /** All required work completed. */
    COMPLETED,

    /** A configured predicate requested termination. */
    PREDICATE_SATISFIED,

    /** The maximum turn count was reached. */
    MAX_TURNS,

    /** The maximum handoff count was reached. */
    MAX_HANDOFFS,

    /** A handoff policy rejected an unknown, disallowed, self, or repeated-path target. */
    HANDOFF_REJECTED,

    /** A participant or manager requested human input or approval. */
    INPUT_REQUIRED,

    /** A configured failure policy stopped execution. */
    PARTICIPANT_FAILURE,

    /** One or more concurrent participants failed under collect-errors policy. */
    COLLECTED_ERRORS,

    /** Magentic progress stalled after all allowed replans. */
    STALLED,

    /** Magentic exhausted its bounded iteration count. */
    MAX_ITERATIONS,

    /** A manager, selector, planner, or framework contract failed. */
    MANAGER_FAILURE
}
