// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/** Indicates that the current participant response is the terminal handoff output. */
public record HandoffCompletion() implements HandoffDirective {
    private static final HandoffCompletion INSTANCE = new HandoffCompletion();

    /**
     * Returns the shared immutable completion directive.
     *
     * @return completion directive
     */
    public static HandoffCompletion completed() {
        return INSTANCE;
    }
}
