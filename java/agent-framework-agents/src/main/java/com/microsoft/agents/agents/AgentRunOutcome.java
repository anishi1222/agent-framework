// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

/** Classifies a session-aware agent run phase. */
public enum AgentRunOutcome {
    /** The logical run produced a terminal agent response. */
    COMPLETED,
    /** The logical run is suspended pending tool approval decisions. */
    INPUT_REQUIRED
}
