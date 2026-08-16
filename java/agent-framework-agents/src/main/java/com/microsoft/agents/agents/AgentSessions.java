// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.Message;
import java.util.List;
import java.util.Objects;

/** Provides explicit helpers for the session-owned in-memory chat history. */
public final class AgentSessions {
    private AgentSessions() {}

    /**
     * Returns detached in-memory history.
     *
     * @param session session
     * @return immutable chronological messages
     */
    public static List<Message> inMemoryHistory(AgentSession session) {
        return Objects.requireNonNull(session, "session").messages();
    }

    /**
     * Replaces in-memory history.
     *
     * @param session session
     * @param messages chronological replacement messages
     */
    public static void setInMemoryHistory(AgentSession session, List<Message> messages) {
        Objects.requireNonNull(session, "session").replaceMessages(Objects.requireNonNull(messages, "messages"));
    }
}
