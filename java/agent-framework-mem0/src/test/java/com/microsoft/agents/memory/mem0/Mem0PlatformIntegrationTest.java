// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.memory.mem0;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.AgentRunContext;
import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.agents.ContextProviderCompletion;
import com.microsoft.agents.agents.ContextProviderRequest;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunOptions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class Mem0PlatformIntegrationTest {
    @Test
    void livePlatform_shouldAddPollSearchAndClearUniqueScope() throws Exception {
        String enabled = System.getenv("MEM0_INTEGRATION_TESTS");
        String apiKey = System.getenv("MEM0_API_KEY");
        Assumptions.assumeTrue(
                "true".equalsIgnoreCase(enabled) && apiKey != null && !apiKey.isBlank(),
                "Set MEM0_INTEGRATION_TESTS=true and MEM0_API_KEY to run the live Mem0 Platform test.");

        String marker = "agent-framework-java-" + UUID.randomUUID();
        Mem0Scope scope = Mem0Scope.forUser(marker);
        Mem0ClientOptions options = Mem0ClientOptions.builder()
                .endpoint(Mem0Endpoint.platform())
                .requestTimeout(Duration.ofSeconds(30))
                .operationTimeout(Duration.ofMinutes(2))
                .initialEventPollDelay(Duration.ofMillis(500))
                .maxEventPollDelay(Duration.ofSeconds(3))
                .build();

        try (Mem0ContextProvider provider = Mem0ContextProvider.builder(Mem0ApiKey.of(apiKey), scope)
                .clientOptions(options)
                .build()) {
            DefaultRunCancellation cancellation = new DefaultRunCancellation();
            try {
                provider.clearAsync(scope, cancellation).toCompletableFuture().join();
                ContextProviderRequest request = request(marker, cancellation);
                provider.completedAsync(new ContextProviderCompletion(
                                request,
                                request.runContext().inputMessages(),
                                AgentResponse.builder().messages(List.of()).build(),
                                null))
                        .toCompletableFuture()
                        .join();

                List<Mem0Memory> memories = List.of();
                for (int attempt = 0; attempt < 6 && memories.isEmpty(); attempt++) {
                    memories = provider.searchAsync(scope, marker, cancellation)
                            .toCompletableFuture()
                            .join();
                    if (memories.isEmpty()) {
                        Thread.sleep(Duration.ofSeconds(1));
                    }
                }
                assertThat(memories)
                        .anySatisfy(memory -> assertThat(memory.memory()).containsIgnoringCase("agent-framework-java"));
            } finally {
                provider.clearAsync(scope, cancellation).toCompletableFuture().join();
            }
        }
    }

    private static ContextProviderRequest request(String marker, DefaultRunCancellation cancellation) {
        AgentSession session = new AgentSession("mem0-live-" + UUID.randomUUID());
        Message input = Message.text(Role.USER, "Remember this unique integration marker exactly: " + marker);
        AgentRunContext runContext = new AgentRunContext(
                "mem0-live-run-" + UUID.randomUUID(),
                new AgentMetadata("mem0-live-agent", null, null),
                Instant.now(),
                List.of(input),
                RunOptions.empty(),
                cancellation,
                Map.of(),
                session,
                ContextContribution.empty());
        return new ContextProviderRequest(session, runContext, List.of(input), List.of(), Map.of(), List.of());
    }
}
