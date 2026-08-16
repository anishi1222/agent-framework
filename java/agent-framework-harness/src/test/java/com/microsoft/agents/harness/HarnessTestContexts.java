// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

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

final class HarnessTestContexts {
    private HarnessTestContexts() {}

    static ContextProviderRequest request(AgentSession session, String runId) {
        AgentRunContext runContext = new AgentRunContext(
                runId,
                new AgentMetadata("harness-test", "Harness test", null),
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
