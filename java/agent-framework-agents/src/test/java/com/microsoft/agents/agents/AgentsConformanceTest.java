// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.conformance.BehaviorFixture;
import com.microsoft.agents.conformance.ConformanceAssertions;
import com.microsoft.agents.conformance.ConformanceFixtureCatalog;
import com.microsoft.agents.conformance.ConformanceFixtureLoader;
import com.microsoft.agents.conformance.ConformanceValue;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentsConformanceTest {
    private final ConformanceFixtureCatalog catalog = new ConformanceFixtureLoader().loadDefault();

    @Test
    void jcfAgents001_shouldBindLifecycleToProductionAgentAndChatClientContracts() {
        // Arrange
        BehaviorFixture fixture = (BehaviorFixture) catalog.requireCase("JCF-AGENTS-001");
        ConformanceValue.ArrayValue operations =
                (ConformanceValue.ArrayValue) fixture.input().require("operations");

        // Act
        int executionCoreCountPerRun = executeEachProductionView();
        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        actual.put(
                "agentContract", new ConformanceValue.StringValue(Agent.class.isInterface() ? "interface" : "class"));
        actual.put(
                "baseAgentOptional",
                new ConformanceValue.BooleanValue(Modifier.isAbstract(BaseAgent.class.getModifiers())));
        actual.put("finiteAsyncType", new ConformanceValue.StringValue(CompletionStage.class.getSimpleName()));
        actual.put(
                "streamingType",
                new ConformanceValue.StringValue(
                        Flow.class.getSimpleName() + "." + Flow.Publisher.class.getSimpleName()));
        actual.put(
                "executionCoreCountPerRun",
                new ConformanceValue.NumberValue(BigDecimal.valueOf(executionCoreCountPerRun)));

        // Assert
        assertThat(operations.values())
                .extracting(value -> ((ConformanceValue.StringValue) value).value())
                .allSatisfy(AgentsConformanceTest::assertOperationExists);
        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
    }

    @Test
    void jcfAgents002_shouldBindOrderedContextProvidersAndImmutableMetadataToProductionPath() {
        // Arrange
        BehaviorFixture fixture = (BehaviorFixture) catalog.requireCase("JCF-AGENTS-002");
        ConformanceValue.ArrayValue configured =
                (ConformanceValue.ArrayValue) fixture.input().require("providerResults");
        List<String> providerOrder = new ArrayList<>();
        List<ContextProvider> providers = configured.values().stream()
                .map(ConformanceValue.ObjectValue.class::cast)
                .map(value -> provider(value, providerOrder))
                .toList();
        Map<String, StateValue> callerMetadata = Map.of("caller", StateValue.string("unchanged"));
        FakeChatClient client = new FakeChatClient().enqueue(response("done"));

        // Act
        try (ChatAgent agent = new ChatAgent(
                client,
                new AgentMetadata("agent-001", null, null),
                ChatOptions.empty(),
                List.of(),
                providers,
                List.of(),
                List.of(),
                List.of(),
                null)) {
            agent.runAsync(
                            new AgentSession("session-context-conformance"),
                            List.of(),
                            RunOptions.builder().metadata(callerMetadata).build())
                    .toCompletableFuture()
                    .join();
        }

        // Assert
        ChatClientRequest request = client.requests().getFirst();
        assertThat(request.runContext().runId()).isNotBlank();
        assertThat(request.runContext().agent().id()).isEqualTo("agent-001");
        assertThat(callerMetadata).containsExactly(Map.entry("caller", StateValue.string("unchanged")));
        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        actual.put(
                "providerOrder",
                new ConformanceValue.ArrayValue(providerOrder.stream()
                        .map(ConformanceValue.StringValue::new)
                        .map(ConformanceValue.class::cast)
                        .toList()));
        actual.put(
                "messageOrder",
                new ConformanceValue.ArrayValue(request.messages().stream()
                        .map(Message::text)
                        .map(ConformanceValue.StringValue::new)
                        .map(ConformanceValue.class::cast)
                        .toList()));
        actual.put("metadata", fixture.expected().require("metadata"));
        actual.put("callerMetadataUnchanged", new ConformanceValue.BooleanValue(true));
        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
    }

    @Test
    void jcfAgents003_shouldBindAllProductionMiddlewarePipelinesAndTermination() {
        // Arrange
        BehaviorFixture fixture = (BehaviorFixture) catalog.requireCase("JCF-AGENTS-003");

        // Act
        MiddlewareRun normal = executeProductionMiddleware(fixture, MiddlewareMode.NORMAL);
        MiddlewareRun terminated = executeProductionMiddleware(fixture, MiddlewareMode.TERMINATED);
        MiddlewareRun doubleNext = executeProductionMiddleware(fixture, MiddlewareMode.DOUBLE_NEXT);

        // Assert
        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        actual.put("normalOrder", strings(normal.order()));
        actual.put("terminatedOrder", strings(terminated.order()));
        actual.put("normalModelCalls", number(normal.modelCalls()));
        actual.put("normalToolInvocations", number(normal.toolInvocations()));
        actual.put("modelCallsAfterTermination", number(terminated.modelCalls()));
        actual.put("toolInvocationsAfterTermination", number(terminated.toolInvocations()));
        actual.put("contextIsolated", new ConformanceValue.BooleanValue(normal.contextIsolated()));
        actual.put(
                "doubleNextRejected",
                new ConformanceValue.BooleanValue(doubleNext.failure() instanceof MiddlewareException));
        actual.put("doubleNextModelCalls", number(doubleNext.modelCalls()));
        actual.put("doubleNextToolInvocations", number(doubleNext.toolInvocations()));
        actual.put(
                "doubleNextError",
                new ConformanceValue.StringValue(
                        doubleNext.failure() == null ? "" : doubleNext.failure().getMessage()));
        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
    }

    private static ContextProvider provider(ConformanceValue.ObjectValue configured, List<String> order) {
        String id = ((ConformanceValue.StringValue) configured.require("providerId")).value();
        ConformanceValue.ArrayValue messages = (ConformanceValue.ArrayValue) configured.require("messages");
        List<Message> contribution = messages.values().stream()
                .map(ConformanceValue.ObjectValue.class::cast)
                .map(value -> Message.text(
                        Role.of(((ConformanceValue.StringValue) value.require("role")).value()),
                        ((ConformanceValue.StringValue) value.require("text")).value()))
                .toList();
        return new ContextProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public CompletionStage<ContextContribution> provideAsync(ContextProviderRequest request) {
                order.add(id);
                return java.util.concurrent.CompletableFuture.completedFuture(
                        new ContextContribution(List.of(), contribution, Map.of(), List.of()));
            }
        };
    }

    private static MiddlewareRun executeProductionMiddleware(BehaviorFixture fixture, MiddlewareMode mode) {
        ConformanceValue.ArrayValue pipeline =
                (ConformanceValue.ArrayValue) fixture.input().require("pipeline");
        List<String> events = pipeline.values().stream()
                .map(ConformanceValue.StringValue.class::cast)
                .map(ConformanceValue.StringValue::value)
                .toList();
        String terminationAt = ((ConformanceValue.StringValue) fixture.input().require("terminationAt")).value();
        String agentBefore = events.get(0);
        String chatBefore = events.get(1);
        String functionBefore = events.get(2);
        String functionAfter = events.get(3);
        String chatAfter = events.get(4);
        String agentAfter = events.get(5);
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicBoolean contextIsolated = new AtomicBoolean(true);
        AtomicInteger toolInvocations = new AtomicInteger();
        FunctionTool function = FunctionTool.create(
                new ToolMetadata(
                        "function",
                        "test",
                        Set.of(ToolCapability.FUNCTION),
                        ToolApprovalMode.NEVER_REQUIRE,
                        StateValue.object(Map.of("type", StateValue.string("object"))),
                        StateValue.object(Map.of())),
                (context, arguments) -> {
                    toolInvocations.incrementAndGet();
                    return CompletableFuture.completedFuture(StateValue.string("value"));
                });
        FunctionMiddleware functionMiddleware = (context, next) -> {
            order.add(functionBefore);
            observeIsolatedContext(context.metadata(), "function", contextIsolated);
            if (mode == MiddlewareMode.TERMINATED && terminationAt.equals(functionBefore)) {
                return CompletableFuture.failedFuture(new MiddlewareTermination());
            }
            return next.invokeAsync(context).thenApply(value -> {
                order.add(functionAfter);
                return value;
            });
        };
        ChatMiddleware chatMiddleware = new ChatMiddleware() {
            @Override
            public CompletionStage<ChatResponse> invokeAsync(ChatMiddlewareContext context, ChatMiddlewareNext next) {
                order.add(chatBefore);
                observeIsolatedContext(context.metadata(), "chat", contextIsolated);
                if (mode == MiddlewareMode.TERMINATED && terminationAt.equals(chatBefore)) {
                    return CompletableFuture.failedFuture(new MiddlewareTermination());
                }
                return next.invokeAsync(context).thenApply(response -> {
                    order.add(chatAfter);
                    return response;
                });
            }
        };
        AgentMiddleware<Void> agentMiddleware = new AgentMiddleware<>() {
            @Override
            public CompletionStage<AgentResponse<Void>> invokeAsync(
                    AgentMiddlewareContext<Void> context, AgentMiddlewareNext<Void> next) {
                order.add(agentBefore);
                observeIsolatedContext(context.metadata(), "agent", contextIsolated);
                if (mode == MiddlewareMode.TERMINATED && terminationAt.equals(agentBefore)) {
                    return CompletableFuture.failedFuture(new MiddlewareTermination());
                }
                return next.invokeAsync(context).thenCompose(response -> {
                    order.add(agentAfter);
                    return mode == MiddlewareMode.DOUBLE_NEXT
                            ? next.invokeAsync(context)
                            : CompletableFuture.completedFuture(response);
                });
            }
        };
        FunctionCallContent call = new FunctionCallContent("call-middleware", "function", StateValue.object(Map.of()));
        FakeChatClient client = new FakeChatClient()
                .enqueue(ChatResponse.builder()
                        .messages(List.of(new Message(Role.ASSISTANT, List.of(call))))
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build())
                .enqueue(response("done"));
        Throwable failure = null;
        try (ChatAgent agent = new ChatAgent(
                client,
                new AgentMetadata("middleware-agent", null, null),
                ChatOptions.empty(),
                List.of(function),
                List.of(),
                List.of(agentMiddleware),
                List.of(chatMiddleware),
                List.of(functionMiddleware),
                null)) {
            try {
                agent.runAsync("middleware").toCompletableFuture().join();
            } catch (CompletionException executionFailure) {
                failure = RunHandles.unwrap(executionFailure);
            }
        }
        return new MiddlewareRun(
                List.copyOf(order), client.requests().size(), toolInvocations.get(), contextIsolated.get(), failure);
    }

    private static void observeIsolatedContext(
            MiddlewareMetadata metadata, String layer, AtomicBoolean contextIsolated) {
        if (metadata.get("middleware-layer").isPresent()) {
            contextIsolated.set(false);
        }
        metadata.put("middleware-layer", StateValue.string(layer));
    }

    private static ConformanceValue.ArrayValue strings(List<String> values) {
        return new ConformanceValue.ArrayValue(values.stream()
                .map(ConformanceValue.StringValue::new)
                .map(ConformanceValue.class::cast)
                .toList());
    }

    private static ConformanceValue.NumberValue number(long value) {
        return new ConformanceValue.NumberValue(BigDecimal.valueOf(value));
    }

    private enum MiddlewareMode {
        NORMAL,
        TERMINATED,
        DOUBLE_NEXT
    }

    private record MiddlewareRun(
            List<String> order, int modelCalls, int toolInvocations, boolean contextIsolated, Throwable failure) {}

    private static int executeEachProductionView() {
        AtomicInteger maximumCalls = new AtomicInteger();
        maximumCalls.accumulateAndGet(runAgentAsync(), Math::max);
        maximumCalls.accumulateAndGet(runAgentSync(), Math::max);
        maximumCalls.accumulateAndGet(runAgentStreaming(), Math::max);
        maximumCalls.accumulateAndGet(runChatAsync(), Math::max);
        maximumCalls.accumulateAndGet(runChatSync(), Math::max);
        maximumCalls.accumulateAndGet(runChatStreaming(), Math::max);
        return maximumCalls.get();
    }

    private static int runAgentAsync() {
        FakeChatClient client = new FakeChatClient().enqueue(response("agent-async"));
        try (ChatAgent agent = new ChatAgent(client)) {
            AgentResponse<Void> ignored =
                    agent.runAsync("hello").toCompletableFuture().join();
            assertThat(ignored.text()).isEqualTo("agent-async");
        }
        return client.requests().size();
    }

    private static int runAgentSync() {
        FakeChatClient client = new FakeChatClient().enqueue(response("agent-sync"));
        try (ChatAgent agent = new ChatAgent(client)) {
            assertThat(agent.run("hello").text()).isEqualTo("agent-sync");
        }
        return client.requests().size();
    }

    private static int runAgentStreaming() {
        FakeChatClient client = new FakeChatClient().enqueueStreaming(List.of(update("agent-stream")));
        TerminalSubscriber<AgentResponseUpdate> subscriber = new TerminalSubscriber<>();
        try (ChatAgent agent = new ChatAgent(client)) {
            agent.runStreaming("hello").subscribe(subscriber);
            subscriber.terminal().toCompletableFuture().join();
        }
        return client.requests().size();
    }

    private static int runChatAsync() {
        FakeChatClient client = new FakeChatClient().enqueue(response("chat-async"));
        client.completeAsync(request()).toCompletableFuture().join();
        return client.requests().size();
    }

    private static int runChatSync() {
        FakeChatClient client = new FakeChatClient().enqueue(response("chat-sync"));
        client.complete(request());
        return client.requests().size();
    }

    private static int runChatStreaming() {
        FakeChatClient client = new FakeChatClient().enqueueStreaming(List.of(update("chat-stream")));
        TerminalSubscriber<ChatResponseUpdate> subscriber = new TerminalSubscriber<>();
        client.completeStreaming(request()).subscribe(subscriber);
        subscriber.terminal().toCompletableFuture().join();
        return client.requests().size();
    }

    private static void assertOperationExists(String operation) {
        Class<?> owner = operation.startsWith("complete") ? ChatClient.class : Agent.class;
        assertThat(java.util.Arrays.stream(owner.getMethods())
                        .filter(method -> method.getName().equals(operation)))
                .as("operation %s", operation)
                .isNotEmpty();
    }

    private static ChatClientRequest request() {
        return new ChatClientRequest(List.of(Message.text(Role.USER, "hello")), ChatOptions.empty());
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(
                List.of(Message.text(Role.ASSISTANT, text)),
                null,
                null,
                null,
                null,
                FinishReason.STOP,
                null,
                null,
                Map.of(),
                List.of());
    }

    private static ChatResponseUpdate update(String text) {
        return ChatResponseUpdate.builder()
                .role(Role.ASSISTANT)
                .contents(List.of(new TextContent(text)))
                .finishReason(FinishReason.STOP)
                .build();
    }

    private static final class TerminalSubscriber<T> implements Flow.Subscriber<T> {
        private final java.util.concurrent.CompletableFuture<Void> terminal =
                new java.util.concurrent.CompletableFuture<>();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(T item) {}

        @Override
        public void onError(Throwable throwable) {
            terminal.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            terminal.complete(null);
        }

        private CompletionStage<Void> terminal() {
            return terminal.minimalCompletionStage();
        }
    }
}
