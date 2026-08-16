// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.agents.AgentRunContext;
import com.microsoft.agents.agents.AgentSession;
import java.util.Objects;

/**
 * Carries the agent run and session requesting skills.
 *
 * @param runContext explicit agent run context
 * @param session active agent session
 */
public record SkillsSourceContext(AgentRunContext runContext, AgentSession session) {
    /** Creates an immutable skills-source context. */
    public SkillsSourceContext {
        Objects.requireNonNull(runContext, "runContext");
        Objects.requireNonNull(session, "session");
    }
}
