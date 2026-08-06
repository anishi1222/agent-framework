// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.AgentResponse;

/** Decodes an agent-based selector response into a typed manager decision. */
@FunctionalInterface
public interface GroupChatSelectionDecoder {
    /**
     * Decodes one selector response.
     *
     * @param response selector-agent response
     * @param context immutable selection context
     * @return non-null decision
     */
    GroupChatDecision decode(AgentResponse<?> response, GroupChatContext context);
}
