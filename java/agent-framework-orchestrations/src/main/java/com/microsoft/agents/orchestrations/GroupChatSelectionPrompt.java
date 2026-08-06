// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.Message;
import java.util.List;

/** Builds framework-owned input for an agent-based group-chat selector. */
@FunctionalInterface
public interface GroupChatSelectionPrompt {
    /**
     * Builds selector-agent messages.
     *
     * @param context immutable group-chat context
     * @return non-null ordered messages
     */
    List<Message> messages(GroupChatContext context);
}
