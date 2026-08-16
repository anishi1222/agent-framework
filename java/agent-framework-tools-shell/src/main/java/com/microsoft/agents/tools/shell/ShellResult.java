// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Describes one completed shell command.
 *
 * @param stdout captured standard output, possibly truncated
 * @param stderr captured standard error, possibly truncated
 * @param exitCode process exit status, {@code 124} for timeout
 * @param duration end-to-end command duration
 * @param truncated whether either output stream exceeded its configured bound
 * @param timedOut whether the command was terminated by its timeout
 */
public record ShellResult(
        String stdout, String stderr, int exitCode, Duration duration, boolean truncated, boolean timedOut) {
    /** Creates a validated immutable result. */
    public ShellResult {
        stdout = Objects.requireNonNull(stdout, "stdout");
        stderr = Objects.requireNonNull(stderr, "stderr");
        duration = Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative.");
        }
        if (timedOut && exitCode != 124) {
            throw new IllegalArgumentException("timed-out commands must use exit code 124.");
        }
    }

    /**
     * Formats this result as deterministic text suitable for a language model.
     *
     * @return stdout, stderr, status markers, and exit code
     */
    public String formatForModel() {
        List<String> parts = new ArrayList<>();
        if (!stdout.isEmpty()) {
            parts.add(stdout);
        }
        if (!stderr.isEmpty()) {
            parts.add("stderr: " + stderr);
        }
        if (truncated) {
            parts.add("[output truncated]");
        }
        if (timedOut) {
            parts.add("[command timed out]");
        }
        parts.add("exit_code: " + exitCode);
        return String.join("\n", parts);
    }
}
