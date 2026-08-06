// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.FunctionTool;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class OpenAIToolRoundTripTest {
    @Test
    void chatAgent_shouldExecuteParallelProviderFunctionCallsAndReturnEveryResultOnNextTurn() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        CompletableFuture<Void> bothInvoked = new CompletableFuture<>();
        FunctionTool tool = FunctionTool.create(OpenAIRequestMapperTest.functionTool(), (context, arguments) -> {
            if (invocations.incrementAndGet() == 2) {
                bothInvoked.complete(null);
            }
            return bothInvoked.thenApply(ignored -> arguments);
        });
        RoundTripTransport transport = new RoundTripTransport();
        OpenAIChatClient client = OpenAIChatClient.builder()
                .options(OpenAIChatClientOptions.builder().model("model-1").build())
                .transport(transport)
                .build();
        ChatAgent agent = new ChatAgent(client, List.of(tool));

        // Act
        AgentResponse<Void> response = agent.runAsync("What is the temperature in Paris?")
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(response.text()).contains("Both");
        assertThat(invocations).hasValue(2);
        assertThat(transport.requests).hasSize(2);
        assertThat(transport.requests.getFirst().tools())
                .extracting(OpenAITransport.FunctionTool::name)
                .containsExactly("lookup");
        assertThat(transport.requests.get(1).input())
                .filteredOn(OpenAITransport.FunctionResultInput.class::isInstance)
                .map(OpenAITransport.FunctionResultInput.class::cast)
                .satisfiesExactly(
                        result -> {
                            assertThat(result.callId()).isEqualTo("call-paris");
                            assertThat(result.result())
                                    .isEqualTo(StateValue.object(Map.of("city", StateValue.string("Paris"))));
                        },
                        result -> {
                            assertThat(result.callId()).isEqualTo("call-tokyo");
                            assertThat(result.result())
                                    .isEqualTo(StateValue.object(Map.of("city", StateValue.string("Tokyo"))));
                        });
    }

    @Test
    void chatAgentStreaming_shouldRoundTripInterleavedParallelCallsWithoutOrphans() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        CompletableFuture<Void> bothInvoked = new CompletableFuture<>();
        FunctionTool tool = FunctionTool.create(OpenAIRequestMapperTest.functionTool(), (context, arguments) -> {
            if (invocations.incrementAndGet() == 2) {
                bothInvoked.complete(null);
            }
            return bothInvoked.thenApply(ignored -> arguments);
        });
        StreamingRoundTripTransport transport = new StreamingRoundTripTransport();
        OpenAIChatClient client = OpenAIChatClient.builder()
                .options(OpenAIChatClientOptions.builder().model("model-1").build())
                .transport(transport)
                .build();
        ChatAgent agent = new ChatAgent(client, List.of(tool));

        // Act
        List<AgentResponseUpdate> updates = collect(() -> agent.runStreaming(
                        List.of(Message.text(Role.USER, "Compare Paris and Tokyo.")),
                        RunOptions.empty(),
                        new DefaultRunCancellation()))
                .join();

        // Assert
        assertThat(invocations).hasValue(2);
        assertThat(transport.requests).hasSize(2);
        assertThat(transport.requests.get(1).input())
                .filteredOn(OpenAITransport.FunctionResultInput.class::isInstance)
                .map(OpenAITransport.FunctionResultInput.class::cast)
                .extracting(OpenAITransport.FunctionResultInput::callId)
                .containsExactly("call-paris", "call-tokyo");
        assertThat(updates).flatExtracting(AgentResponseUpdate::contents).hasSizeGreaterThanOrEqualTo(1);
        assertThat(updates.stream().map(AgentResponseUpdate::text).reduce("", String::concat))
                .contains("Both");
        assertThat(updates)
                .filteredOn(update -> update.finishReason() != null)
                .extracting(AgentResponseUpdate::responseId)
                .containsExactly("response-1", "response-2");
    }

    private static CompletableFuture<List<AgentResponseUpdate>> collect(
            Supplier<Flow.Publisher<AgentResponseUpdate>> publisherFactory) {
        CopyOnWriteArrayList<AgentResponseUpdate> updates = new CopyOnWriteArrayList<>();
        CompletableFuture<List<AgentResponseUpdate>> result = new CompletableFuture<>();
        publisherFactory.get().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AgentResponseUpdate item) {
                updates.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.complete(List.copyOf(updates));
            }
        });
        return result;
    }

    private static final class RoundTripTransport implements OpenAITransport {
        private final AtomicInteger turns = new AtomicInteger();

        private final List<OpenAITransport.Request> requests = new CopyOnWriteArrayList<>();

        @Override
        public CompletionStage<OpenAITransport.Response> completeAsync(
                OpenAITransport.Request request, RunCancellation cancellation) {
            requests.add(request);
            int turn = turns.getAndIncrement();
            List<OpenAITransport.OutputItem> output = turn == 0
                    ? List.of(
                            new OpenAITransport.FunctionCallOutput(
                                    "call-paris",
                                    "lookup",
                                    StateValue.object(Map.of("city", StateValue.string("Paris"))),
                                    "item-paris",
                                    "completed"),
                            new OpenAITransport.FunctionCallOutput(
                                    "call-tokyo",
                                    "lookup",
                                    StateValue.object(Map.of("city", StateValue.string("Tokyo"))),
                                    "item-tokyo",
                                    "completed"))
                    : List.of(new OpenAITransport.TextOutput(
                            "message-2", "Both tool results were returned.", false, Map.of()));
            return CompletableFuture.completedFuture(new OpenAITransport.Response(
                    "response-" + (turn + 1),
                    null,
                    "model-1",
                    Instant.EPOCH.plusSeconds(turn),
                    OpenAITransport.ResponseStatus.COMPLETED,
                    output,
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
    }

    private static final class StreamingRoundTripTransport implements OpenAITransport {
        private final AtomicInteger turns = new AtomicInteger();

        private final List<OpenAITransport.Request> requests = new CopyOnWriteArrayList<>();

        @Override
        public CompletionStage<OpenAITransport.Response> completeAsync(
                OpenAITransport.Request request, RunCancellation cancellation) {
            throw new UnsupportedOperationException("This fixture uses streaming turns.");
        }

        @Override
        public Flow.Publisher<OpenAITransport.StreamEvent> completeStreaming(
                OpenAITransport.Request request, RunCancellation cancellation) {
            requests.add(request);
            int turn = turns.getAndIncrement();
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean emitted;

                @Override
                public void request(long count) {
                    if (count <= 0 || emitted) {
                        return;
                    }
                    emitted = true;
                    for (OpenAITransport.StreamEvent event : turn == 0 ? toolEvents() : textEvents()) {
                        subscriber.onNext(event);
                    }
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                    emitted = true;
                }
            });
        }

        private static List<OpenAITransport.StreamEvent> toolEvents() {
            StateValue paris = StateValue.object(Map.of("city", StateValue.string("Paris")));
            StateValue tokyo = StateValue.object(Map.of("city", StateValue.string("Tokyo")));
            return List.of(
                    started("response-1"),
                    new OpenAITransport.FunctionCallStarted(1, 1, "item-paris", "call-paris", "lookup"),
                    new OpenAITransport.FunctionCallStarted(2, 2, "item-tokyo", "call-tokyo", "lookup"),
                    new OpenAITransport.FunctionArgumentsDelta(3, 1, "item-paris", "{\"city\":\"Par"),
                    new OpenAITransport.FunctionArgumentsDelta(4, 2, "item-tokyo", "{\"city\":\"Tok"),
                    new OpenAITransport.FunctionArgumentsDelta(5, 1, "item-paris", "is\"}"),
                    new OpenAITransport.FunctionArgumentsDelta(6, 2, "item-tokyo", "yo\"}"),
                    new OpenAITransport.FunctionArgumentsDone(7, 2, "item-tokyo", "call-tokyo", "lookup", tokyo),
                    new OpenAITransport.FunctionArgumentsDone(8, 1, "item-paris", "call-paris", "lookup", paris),
                    new OpenAITransport.ResponseCompleted(
                            9,
                            response(
                                    "response-1",
                                    List.of(
                                            new OpenAITransport.FunctionCallOutput(
                                                    "call-paris", "lookup", paris, "item-paris", "completed"),
                                            new OpenAITransport.FunctionCallOutput(
                                                    "call-tokyo", "lookup", tokyo, "item-tokyo", "completed")))));
        }

        private static List<OpenAITransport.StreamEvent> textEvents() {
            return List.of(
                    started("response-2"),
                    new OpenAITransport.TextDelta(1, "message-2", "Both results returned.", Map.of()),
                    new OpenAITransport.ResponseCompleted(
                            2,
                            response(
                                    "response-2",
                                    List.of(new OpenAITransport.TextOutput(
                                            "message-2", "Both results returned.", false, Map.of())))));
        }

        private static OpenAITransport.ResponseStarted started(String responseId) {
            return new OpenAITransport.ResponseStarted(
                    0, responseId, null, "model-1", Instant.EPOCH, null, OpenAITransport.ResponseStatus.IN_PROGRESS);
        }

        private static OpenAITransport.Response response(String responseId, List<OpenAITransport.OutputItem> outputs) {
            return new OpenAITransport.Response(
                    responseId,
                    null,
                    "model-1",
                    Instant.EPOCH,
                    OpenAITransport.ResponseStatus.COMPLETED,
                    outputs,
                    null,
                    Map.of(),
                    null,
                    null,
                    null);
        }
    }
}
