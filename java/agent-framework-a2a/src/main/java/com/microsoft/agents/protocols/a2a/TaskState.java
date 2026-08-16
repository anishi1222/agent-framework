// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

/** Defines A2A v1 task lifecycle states using their ProtoJSON names. */
public enum TaskState {
    /** Task was received and queued. */
    TASK_STATE_SUBMITTED(false, false),
    /** Task is actively executing. */
    TASK_STATE_WORKING(false, false),
    /** Task completed successfully. */
    TASK_STATE_COMPLETED(true, false),
    /** Task failed. */
    TASK_STATE_FAILED(true, false),
    /** Task was canceled. */
    TASK_STATE_CANCELED(true, false),
    /** Task requires additional input. */
    TASK_STATE_INPUT_REQUIRED(false, true),
    /** Task was rejected. */
    TASK_STATE_REJECTED(true, false),
    /** Task requires authentication or authorization. */
    TASK_STATE_AUTH_REQUIRED(false, true),
    /** Task state was not specified. */
    TASK_STATE_UNSPECIFIED(false, false);

    private final boolean terminal;

    private final boolean interrupted;

    TaskState(boolean terminal, boolean interrupted) {
        this.terminal = terminal;
        this.interrupted = interrupted;
    }

    /**
     * Reports whether no further task transitions are valid.
     *
     * @return terminal flag
     */
    public boolean isTerminal() {
        return terminal;
    }

    /**
     * Reports whether external input is required before work can resume.
     *
     * @return interrupted flag
     */
    public boolean isInterrupted() {
        return interrupted;
    }
}
