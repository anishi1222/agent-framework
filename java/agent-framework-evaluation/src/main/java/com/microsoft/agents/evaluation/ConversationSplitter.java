// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import com.microsoft.agents.core.Message;
import java.util.List;

/**
 * Splits an immutable provider-neutral conversation at a deterministic message boundary.
 */
@FunctionalInterface
public interface ConversationSplitter {
    /**
     * Splits a conversation into query and response messages.
     *
     * @param conversation full ordered conversation
     * @return immutable split covering the conversation exactly once
     */
    ConversationSplit split(List<Message> conversation);
}
