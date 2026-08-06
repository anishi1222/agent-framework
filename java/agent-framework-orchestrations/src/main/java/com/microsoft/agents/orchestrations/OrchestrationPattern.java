// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/** Identifies the orchestration pattern that owns a continuation. */
public enum OrchestrationPattern {
    /** Ordered participant pipeline. */
    SEQUENTIAL,

    /** Declaration-ordered concurrent fan-out. */
    CONCURRENT,

    /** Typed participant handoff. */
    HANDOFF,

    /** Manager-directed group chat. */
    GROUP_CHAT,

    /** Magentic planning and execution. */
    MAGENTIC
}
