// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.core.credential.AccessToken;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.providers.openai.OpenAITransport;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class FoundryAgentToolRoundTripTest {
    @Test
    void foundryAgent_shouldRoundTripParallelCallsWithoutSendingLocalSchemas() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        CompletableFuture<Void> bothInvoked = new CompletableFuture<>();
        FunctionTool tool = FunctionTool.create(metadata(), (context, arguments) -> {
            if (invocations.incrementAndGet() == 2) {
                bothInvoked.complete(null);
            }
            return bothInvoked.thenApply(ignored -> arguments);
        });
        RoundTripTransport transport = new RoundTripTransport();
        FoundryAgent agent = FoundryAgent.builder()
                .options(FoundryChatClientOptions.builder()
                        .projectEndpoint("https://resource.services.ai.azure.com/api/projects/project-one")
                        .agentName("weather-agent")
                        .agentVersion("4")
                        .tokenCredential(context -> Mono.just(
                                new AccessToken("token", OffsetDateTime.now().plusHours(1))))
                        .build())
                .transport(transport)
                .tools(List.of(tool))
                .build();

        // Act
        AgentResponse<Void> response =
                agent.runAsync("Compare Paris and Tokyo.").toCompletableFuture().join();

        // Assert
        assertThat(agent.id()).isEqualTo("weather-agent");
        assertThat(response.text()).contains("Both");
        assertThat(invocations).hasValue(2);
        assertThat(transport.requests).hasSize(2);
        assertThat(transport.requests.getFirst().tools()).isEmpty();
        assertThat(transport.requests.get(1).input())
                .filteredOn(OpenAITransport.FunctionResultInput.class::isInstance)
                .map(OpenAITransport.FunctionResultInput.class::cast)
                .extracting(OpenAITransport.FunctionResultInput::callId)
                .containsExactly("call-paris", "call-tokyo");
    }

    private static ToolMetadata metadata() {
        return new ToolMetadata(
                "lookup",
                "Looks up a city.",
                Set.of(ToolCapability.FUNCTION),
                ToolApprovalMode.NEVER_REQUIRE,
                StateValue.object(Map.of("type", StateValue.string("object"))),
                StateValue.object(Map.of()));
    }

    private static final class RoundTripTransport implements FoundryTransport {
        private final AtomicInteger turns = new AtomicInteger();

        private final List<OpenAITransport.Request> requests = new CopyOnWriteArrayList<>();

        @Override
        public CompletionStage<OpenAITransport.Response> completeAsync(
                OpenAITransport.Request request, RunCancellation cancellation) {
            requests.add(request);
            int turn = turns.getAndIncrement();
            List<OpenAITransport.OutputItem> outputs = turn == 0
                    ? List.of(call("call-paris", "Paris", "item-paris"), call("call-tokyo", "Tokyo", "item-tokyo"))
                    : List.of(new OpenAITransport.TextOutput(
                            "message-2", "Both results were returned.", false, Map.of()));
            return CompletableFuture.completedFuture(new OpenAITransport.Response(
                    "response-" + (turn + 1),
                    null,
                    "deployment",
                    Instant.EPOCH.plusSeconds(turn),
                    OpenAITransport.ResponseStatus.COMPLETED,
                    outputs,
                    null,
                    Map.of(),
                    null,
                    null,
                    null));
        }

        @Override
        public Flow.Publisher<OpenAITransport.StreamEvent> completeStreaming(
                OpenAITransport.Request request, RunCancellation cancellation) {
            throw new UnsupportedOperationException("This fixture uses finite turns.");
        }

        private static OpenAITransport.FunctionCallOutput call(String callId, String city, String itemId) {
            return new OpenAITransport.FunctionCallOutput(
                    callId, "lookup", StateValue.object(Map.of("city", StateValue.string(city))), itemId, "completed");
        }
    }
}
