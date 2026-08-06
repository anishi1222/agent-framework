// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/** Determines whether a group chat should terminate before selecting another speaker. */
@FunctionalInterface
public interface GroupChatTerminationPredicate {
    /**
     * Evaluates immutable shared group-chat state.
     *
     * @param context group-chat context
     * @return {@code true} to terminate
     */
    boolean shouldTerminate(GroupChatContext context);
}
