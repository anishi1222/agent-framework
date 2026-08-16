// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.AgentRunContext;
import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.agents.ContextProviderRequest;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunOptions;
import java.time.Instant;
import java.util.List;
import java.util.Map;

final class SkillsTestContexts {
    private SkillsTestContexts() {}

    static SkillsSourceContext context(String sessionId) {
        ContextProviderRequest request = request(sessionId);
        return new SkillsSourceContext(request.runContext(), request.session());
    }

    static ContextProviderRequest request(String sessionId) {
        AgentSession session = new AgentSession(sessionId);
        AgentRunContext runContext = new AgentRunContext(
                "run-" + sessionId,
                new AgentMetadata("agent", null, null),
                Instant.EPOCH,
                List.of(),
                RunOptions.empty(),
                new DefaultRunCancellation(),
                Map.of(),
                session,
                ContextContribution.empty());
        return new ContextProviderRequest(session, runContext, List.of(), List.of(), Map.of(), List.of());
    }
}
