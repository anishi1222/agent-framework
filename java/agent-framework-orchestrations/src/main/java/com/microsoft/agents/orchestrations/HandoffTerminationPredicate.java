// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/** Determines whether the latest handoff turn should terminate before another routing decision. */
@FunctionalInterface
public interface HandoffTerminationPredicate {
    /**
     * Evaluates one immutable completed turn.
     *
     * @param context handoff turn context
     * @return {@code true} to terminate with the latest response
     */
    boolean shouldTerminate(HandoffTurnContext context);
}
