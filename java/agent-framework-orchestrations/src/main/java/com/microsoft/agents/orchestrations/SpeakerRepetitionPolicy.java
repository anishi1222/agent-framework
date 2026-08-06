// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/** Selects whether a group-chat participant may speak in consecutive turns. */
public enum SpeakerRepetitionPolicy {
    /** Allows consecutive turns by the same registered participant. */
    ALLOW,

    /** Rejects a manager decision that repeats the immediately preceding speaker. */
    DISALLOW_CONSECUTIVE
}
