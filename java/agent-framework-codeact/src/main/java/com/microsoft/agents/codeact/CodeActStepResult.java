// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

import java.util.Objects;

/**
 * Describes one completed bounded CodeAct step.
 *
 * @param index zero-based program index
 * @param step immutable submitted step
 * @param stdout retained standard output
 * @param stderr retained standard error
 * @param exitCode process exit status
 * @param truncated whether shell or aggregate output was truncated
 * @param timedOut whether the shell runtime terminated the step for timeout
 */
public record CodeActStepResult(
        int index, CodeActStep step, String stdout, String stderr, int exitCode, boolean truncated, boolean timedOut) {
    /** Creates a validated immutable step result. */
    public CodeActStepResult {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative.");
        }
        step = Objects.requireNonNull(step, "step");
        stdout = Objects.requireNonNull(stdout, "stdout");
        stderr = Objects.requireNonNull(stderr, "stderr");
        if (timedOut && exitCode != 124) {
            throw new IllegalArgumentException("Timed-out steps must use exit code 124.");
        }
    }
}
