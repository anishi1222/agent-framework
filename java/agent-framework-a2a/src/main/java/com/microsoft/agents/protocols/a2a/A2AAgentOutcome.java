// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

/** Classifies the observable boundary reached by a remote A2A agent call. */
public enum A2AAgentOutcome {
    /** Remote work completed successfully. */
    COMPLETED,
    /** Remote work remains submitted or working. */
    WORKING,
    /** Remote work requires additional user input. */
    INPUT_REQUIRED,
    /** Remote work requires authentication or authorization. */
    AUTH_REQUIRED
}
