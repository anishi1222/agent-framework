// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.AgentRunContext;
import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.agents.ContextProviderRequest;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunOptions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

final class ValkeyTestSupport {
    private ValkeyTestSupport() {}

    static ValkeyHistoryOptions options() {
        return options(Duration.ofSeconds(5), null, 10, 5, 1024 * 1024, 4 * 1024 * 1024);
    }

    static ValkeyHistoryOptions options(
            Duration operationTimeout,
            Duration timeToLive,
            int maxStoredMessages,
            int maxLoadedMessages,
            int maxMessageBytes,
            int maxDocumentBytes) {
        return new ValkeyHistoryOptions(
                new ValkeyClientOptions(
                        new ValkeyEndpoint("localhost", 6379),
                        ValkeyAuthentication.none(),
                        false,
                        "valkey-tests",
                        operationTimeout),
                new ValkeyPartitionContext("tenant-a", "isolation-a", "agent-a"),
                "test-history",
                "test:history",
                maxStoredMessages,
                maxLoadedMessages,
                timeToLive,
                maxMessageBytes,
                maxDocumentBytes);
    }

    static ContextProviderRequest request(String sessionId, String runId) {
        return request(sessionId, runId, new DefaultRunCancellation());
    }

    static ContextProviderRequest request(String sessionId, String runId, RunCancellation cancellation) {
        AgentSession session = new AgentSession(sessionId);
        Message input = Message.text(Role.USER, "input");
        AgentRunContext runContext = new AgentRunContext(
                runId,
                new AgentMetadata("agent", null, null),
                Instant.parse("2026-08-13T00:00:00Z"),
                List.of(input),
                RunOptions.empty(),
                cancellation,
                Map.of(),
                session,
                ContextContribution.empty());
        return new ContextProviderRequest(session, runContext, List.of(input), List.of(), Map.of(), List.of());
    }
}
