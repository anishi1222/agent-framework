// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Coordinates termination and next-speaker selection for a group chat. */
@FunctionalInterface
public interface GroupChatManager {
    /**
     * Produces the next strongly typed manager decision.
     *
     * @param context immutable group-chat context
     * @return non-null decision stage
     */
    CompletionStage<GroupChatDecision> decideAsync(GroupChatContext context);

    /**
     * Adapts a selector and optional synchronous termination predicate into a manager.
     *
     * @param selector next-speaker selector
     * @param terminationPredicate optional termination predicate
     * @return manager
     */
    static GroupChatManager fromSelector(
            GroupChatSelector selector, GroupChatTerminationPredicate terminationPredicate) {
        GroupChatSelector checkedSelector = Objects.requireNonNull(selector, "selector");
        return context -> {
            if (terminationPredicate != null && terminationPredicate.shouldTerminate(context)) {
                return CompletableFuture.completedFuture(
                        GroupChatDecision.terminate("The configured termination predicate was satisfied."));
            }
            CompletionStage<String> selected =
                    Objects.requireNonNull(checkedSelector.selectNextAsync(context), "selector returned null");
            return selected.thenApply(GroupChatDecision::select);
        };
    }

    /**
     * Adapts a selector into a manager.
     *
     * @param selector next-speaker selector
     * @return manager
     */
    static GroupChatManager fromSelector(GroupChatSelector selector) {
        return fromSelector(selector, null);
    }
}
