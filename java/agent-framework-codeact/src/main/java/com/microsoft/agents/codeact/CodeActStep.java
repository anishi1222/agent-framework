// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

/**
 * Describes one ordered shell-backed CodeAct step.
 *
 * @param id stable non-blank step identifier
 * @param command complete non-blank command text
 */
public record CodeActStep(String id, String command) {
    /** Creates a validated immutable step. */
    public CodeActStep {
        id = CodeActValidation.requireNonBlank(id, "id");
        command = CodeActValidation.requireNonBlank(command, "command");
    }
}
