// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/** Selects the default message projection passed between sequential participants. */
public enum SequentialHistoryPolicy {
    /** Passes the complete canonical transcript accumulated so far. */
    SHARED_TRANSCRIPT,

    /** Passes only the immediately preceding successful participant response. */
    PREVIOUS_RESPONSE
}
