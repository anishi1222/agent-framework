// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/** Classifies one participant invocation result. */
public enum ParticipantStatus {
    /** The participant produced a terminal response. */
    COMPLETED,

    /** The participant failed. */
    FAILED,

    /** The participant requires approval or human input. */
    INPUT_REQUIRED,

    /** The participant was not invoked after a policy stopped the run. */
    SKIPPED
}
