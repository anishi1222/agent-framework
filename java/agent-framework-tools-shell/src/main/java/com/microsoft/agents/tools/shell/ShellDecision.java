// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import java.util.Objects;

/**
 * Reports whether a shell command passed policy evaluation.
 *
 * @param allowed whether execution may continue
 * @param reason human-readable rationale, possibly empty
 */
public record ShellDecision(boolean allowed, String reason) {
    /** Creates a non-null decision. */
    public ShellDecision {
        reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns an allow decision.
     *
     * @return shared-shape allow decision
     */
    public static ShellDecision allow() {
        return new ShellDecision(true, "");
    }

    /**
     * Returns a deny decision.
     *
     * @param reason non-blank denial rationale
     * @return deny decision
     */
    public static ShellDecision deny(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank.");
        }
        return new ShellDecision(false, reason);
    }
}
