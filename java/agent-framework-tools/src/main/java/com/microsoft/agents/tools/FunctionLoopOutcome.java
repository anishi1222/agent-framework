// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

/**
 * Classifies the observable outcome of one tool-loop phase.
 */
public enum FunctionLoopOutcome {
    /** The logical run produced a terminal assistant response. */
    SUCCESS,
    /** The logical run is suspended with one or more pending approvals. */
    INPUT_REQUIRED
}
