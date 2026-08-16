// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

/** Identifies the route operation evaluated by hosting authorization policy. */
public enum HostingAuthorizationAction {
    /** Lists or reads route descriptors. */
    DISCOVER,
    /** Starts a finite or streaming run. */
    START,
    /** Resumes an approved process-local continuation. */
    RESUME,
    /** Cancels an active run. */
    CANCEL
}
