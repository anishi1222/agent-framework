// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import java.util.Objects;

/**
 * Describes one shell command awaiting a policy decision.
 *
 * @param command complete command text
 * @param workingDirectory optional working directory visible to the policy
 */
public record ShellRequest(String command, String workingDirectory) {
    /** Creates a validated request. */
    public ShellRequest {
        Objects.requireNonNull(command, "command");
    }

    /**
     * Creates a request without working-directory context.
     *
     * @param command complete command text
     */
    public ShellRequest(String command) {
        this(command, null);
    }
}
