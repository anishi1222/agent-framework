// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

/** Identifies the command syntax expected by a shell environment. */
public enum ShellFamily {
    /** POSIX-compatible shell syntax such as bash or sh. */
    POSIX,
    /** PowerShell syntax. */
    POWERSHELL
}
