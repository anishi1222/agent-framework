// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import java.util.concurrent.CompletionStage;

/** Selects the next registered group-chat participant. */
@FunctionalInterface
public interface GroupChatSelector {
    /**
     * Selects one participant identifier.
     *
     * @param context immutable group-chat context
     * @return non-null stage producing a registered identifier
     */
    CompletionStage<String> selectNextAsync(GroupChatContext context);
}
