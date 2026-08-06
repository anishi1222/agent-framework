// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/** Identifies a stable orchestration lifecycle event kind. */
public enum OrchestrationEventType {
    /** A logical run was created. */
    RUN_STARTED,

    /** A participant invocation was scheduled. */
    PARTICIPANT_STARTED,

    /** A participant produced a terminal response. */
    PARTICIPANT_COMPLETED,

    /** A participant failed. */
    PARTICIPANT_FAILED,

    /** A participant was deterministically skipped after a pattern policy stopped its work. */
    PARTICIPANT_SKIPPED,

    /** A participant requires approval or input. */
    INPUT_REQUIRED,

    /** A handoff was accepted. */
    HANDOFF,

    /** A group-chat speaker was selected. */
    SPEAKER_SELECTED,

    /** A Magentic plan or replan was produced. */
    PLAN_UPDATED,

    /** Magentic progress was assessed. */
    PROGRESS_ASSESSED,

    /** A terminal domain result completed successfully. */
    RUN_COMPLETED,

    /** The one run-level terminal event for a terminated, failed, or unsolved domain result. */
    RUN_TERMINATED,

    /** The run failed exceptionally. */
    RUN_FAILED,

    /** Cancellation won the run terminal race. */
    RUN_CANCELLED
}
