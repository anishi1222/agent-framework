// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Selects group-chat speakers in deterministic declaration order. */
public final class RoundRobinGroupChatSelector implements GroupChatSelector {
    @Override
    public CompletionStage<String> selectNextAsync(GroupChatContext context) {
        java.util.Objects.requireNonNull(context, "context");
        ArrayList<String> identifiers = new ArrayList<>(context.participants().keySet());
        return CompletableFuture.completedFuture(identifiers.get(context.turn() % identifiers.size()));
    }
}
