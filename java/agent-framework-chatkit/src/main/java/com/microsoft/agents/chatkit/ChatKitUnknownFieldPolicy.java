// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

/** Controls handling of unknown fields on supported ChatKit wire objects. */
public enum ChatKitUnknownFieldPolicy {
    /** Reject unknown fields to detect protocol drift. */
    REJECT,

    /** Ignore unknown fields after recursively applying all configured bounds. */
    IGNORE
}
