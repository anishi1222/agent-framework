// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

/** Selects whether shell state is retained between command invocations. */
public enum ShellMode {
    /** Reuses one long-lived shell session and preserves shell state. */
    PERSISTENT,
    /** Starts a fresh shell process or container for every command. */
    STATELESS
}
