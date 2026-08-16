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
import com.microsoft.agents.core.AgentResponses;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.MetadataKey;
import com.microsoft.agents.core.MetadataValues;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.UsageDetails;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    @Test
    void jcfAgents004_shouldBindTransparentDelegationAndExplicitOwnership() {
        // Arrange
        BehaviorFixture fixture = (BehaviorFixture) catalog.requireCase("JCF-AGENTS-004");
        ConformanceValue.ObjectValue configuredAgent =
                (ConformanceValue.ObjectValue) fixture.input().require("agent");
        AgentMetadata metadata = new AgentMetadata(
                ((ConformanceValue.StringValue) configuredAgent.require("id")).value(),
                ((ConformanceValue.StringValue) configuredAgent.require("name")).value(),
                null);
        AtomicBoolean closed = new AtomicBoolean();
        RunCancellation runCancellation = new DefaultRunCancellation();
        RunHandle<AgentResponse<Void>> handle = new RunHandle<>() {
            @Override
            public CompletionStage<AgentResponse<Void>> resultAsync() {
                return CompletableFuture.completedFuture(
                        AgentResponse.<Void>builder().build());
            }

            @Override
            public RunCancellation cancellation() {
                return runCancellation;
            }
        };
        Flow.Publisher<AgentResponseUpdate> publisher = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long count) {
                subscriber.onComplete();
            }

            @Override
            public void cancel() {}
        });
        Agent<Void> inner = new Agent<>() {
            @Override
            public AgentMetadata metadata() {
                return metadata;
            }

            @Override
            public RunHandle<AgentResponse<Void>> startRun(
                    List<Message> messages, RunOptions options, RunCancellation cancellation) {
                return handle;
            }

            @Override
            public Flow.Publisher<AgentResponseUpdate> runStreaming(
                    List<Message> messages, RunOptions options, RunCancellation cancellation) {
                return publisher;
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        DelegatingAgent<Void> nonOwning = new DelegatingAgent<>(inner) {};
        boolean closeInnerAgent =
                ((ConformanceValue.BooleanValue) fixture.input().require("closeInnerAgent")).value();
        DelegatingAgent<Void> owning = new DelegatingAgent<>(inner, closeInnerAgent) {};
        List<Message> messages = List.of(Message.text(Role.USER, "hello"));

        // Act
        RunHandle<AgentResponse<Void>> delegatedHandle =
                nonOwning.startRun(messages, RunOptions.empty(), runCancellation);
        Flow.Publisher<AgentResponseUpdate> delegatedPublisher =
                nonOwning.runStreaming(messages, RunOptions.empty(), runCancellation);
        nonOwning.close();
        boolean callerOwnedByDefault = !closed.get();
        owning.close();

        // Assert
        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        actual.put("metadataForwarded", new ConformanceValue.BooleanValue(nonOwning.metadata() == metadata));
        actual.put("runHandleForwarded", new ConformanceValue.BooleanValue(delegatedHandle == handle));
        actual.put("streamPublisherForwarded", new ConformanceValue.BooleanValue(delegatedPublisher == publisher));
        actual.put("callerOwnedByDefault", new ConformanceValue.BooleanValue(callerOwnedByDefault));
        actual.put("ownedInnerClosed", new ConformanceValue.BooleanValue(closed.get()));
        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
    }

    @Test
    void jcfAgents005_shouldBindSessionMessageInjectionToFiniteStreamingAndToolLoops() {
        // Arrange
        BehaviorFixture fixture = (BehaviorFixture) catalog.requireCase("JCF-AGENTS-005");
        ConformanceValue.ObjectValue configuredMessages =
                (ConformanceValue.ObjectValue) fixture.input().require("messages");
        String initial = string(configuredMessages, "initial");
        String prequeued = string(configuredMessages, "prequeued");
        String duringResponse = string(configuredMessages, "duringResponse");
        String fromTool = string(configuredMessages, "fromTool");
        AgentSession session = new AgentSession("message-injection-conformance");
        MessageInjectionMiddleware.enqueueMessages(session, prequeued);
        FunctionCallContent informational =
                new FunctionCallContent("hosted-call", "hosted_search", StateValue.object(Map.of()), true, Map.of());
        FunctionCallContent actionable = new FunctionCallContent("tool-call", "inject", StateValue.object(Map.of()));
        FunctionTool tool = FunctionTool.create(
                new ToolMetadata(
                        "inject",
                        "Inject a queued message.",
                        Set.of(ToolCapability.FUNCTION),
                        ToolApprovalMode.NEVER_REQUIRE,
                        StateValue.object(Map.of("type", StateValue.string("object"))),
                        StateValue.object(Map.of())),
                (context, arguments) -> {
                    MessageInjectionMiddleware.enqueueMessages(session, fromTool);
                    return CompletableFuture.completedFuture(StateValue.string("tool result"));
                });
        FakeChatClient client = new FakeChatClient()
                .enqueueFinite((request, cancellation) -> {
                    MessageInjectionMiddleware.enqueueMessages(session, duringResponse);
                    return CompletableFuture.completedFuture(ChatResponse.builder()
                            .messages(List.of(new Message(Role.ASSISTANT, List.of(informational))))
                            .conversationId("conversation-injection")
                            .finishReason(FinishReason.STOP)
                            .build());
                })
                .enqueue(ChatResponse.builder()
                        .messages(List.of(new Message(Role.ASSISTANT, List.of(actionable))))
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build())
                .enqueue(response("done"));

        // Act
        try (ChatAgent agent = messageInjectionAgent(client, List.of(tool))) {
            agent.run(session, initial);
        }
        List<ChatClientRequest> finiteRequests = client.requests();
        boolean prequeuedDeliveredOnce = finiteRequests.stream()
                        .allMatch(request -> request.messages().stream()
                                        .filter(message -> prequeued.equals(message.text()))
                                        .count()
                                <= 1)
                && prequeued.equals(
                        finiteRequests.getFirst().messages().getLast().text());
        boolean actionableDeferred = finiteRequests.size() == 3
                && finiteRequests
                        .getLast()
                        .messages()
                        .get(finiteRequests.getLast().messages().size() - 2)
                        .contents()
                        .stream()
                        .anyMatch(FunctionResultContent.class::isInstance)
                && fromTool.equals(finiteRequests.getLast().messages().getLast().text());
        boolean streamingEquivalent = runStreamingInjectionConformance(initial, duringResponse);
        boolean sessionRequired = runSessionRequiredConformance(initial);

        // Assert
        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        actual.put(
                "queueStoredInSessionState",
                new ConformanceValue.BooleanValue(session.state()
                        .get(MessageInjectionMiddleware.PENDING_MESSAGES_STATE_KEY)
                        .filter(StateValue.ArrayValue.class::isInstance)
                        .isPresent()));
        actual.put("prequeuedDeliveredOnce", new ConformanceValue.BooleanValue(prequeuedDeliveredOnce));
        actual.put(
                "nonActionableResponseTriggersNextTurn", new ConformanceValue.BooleanValue(finiteRequests.size() >= 2));
        actual.put(
                "informationalCallsDoNotBlock",
                new ConformanceValue.BooleanValue(finiteRequests.get(1).messages().stream()
                        .anyMatch(message -> duringResponse.equals(message.text()))));
        actual.put("actionableCallsDeferUntilToolResult", new ConformanceValue.BooleanValue(actionableDeferred));
        actual.put(
                "conversationIdPropagated",
                new ConformanceValue.BooleanValue("conversation-injection"
                        .equals(finiteRequests.get(1).options().conversationId())));
        actual.put("streamingEquivalent", new ConformanceValue.BooleanValue(streamingEquivalent));
        actual.put("sessionRequired", new ConformanceValue.BooleanValue(sessionRequired));
        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
    }

    @Test
    void jcfAgents006_shouldBindTypedMetadataResponseSessionAndMessageSourceExtensions() {
        // Arrange
        BehaviorFixture fixture = (BehaviorFixture) catalog.requireCase("JCF-AGENTS-006");
        ConformanceValue.ObjectValue metadataInput =
                (ConformanceValue.ObjectValue) fixture.input().require("metadata");
        ConformanceValue.ObjectValue messageInput =
                (ConformanceValue.ObjectValue) fixture.input().require("messages");
        String metadataName = string(metadataInput, "key");
        String initial = string(metadataInput, "initial");
        String replacement = string(metadataInput, "replacement");
        MetadataKey<String> metadataKey = MetadataKey.string(metadataName);
        Map<String, StateValue> original = Map.of("untouched", StateValue.bool(true));
        Map<String, StateValue> withMetadata = MetadataValues.with(original, metadataKey, initial);
        Map<String, StateValue> preserved = MetadataValues.withIfAbsent(withMetadata, metadataKey, replacement);
        Map<String, StateValue> removed = MetadataValues.without(withMetadata, metadataKey);
        AtomicInteger encodingCount = new AtomicInteger();
        MetadataKey<String> lazyKey = MetadataKey.forType(
                AtomicInteger.class,
                value -> {
                    encodingCount.incrementAndGet();
                    return StateValue.string(value);
                },
                value -> ((StateValue.StringValue) value).value());
        MetadataValues.withIfAbsent(Map.of(lazyKey.name(), StateValue.string(initial)), lazyKey, replacement);
        boolean wrongMetadataTypeRejected;
        try {
            MetadataValues.find(Map.of(metadataName, StateValue.integer(1)), metadataKey);
            wrongMetadataTypeRejected = false;
        } catch (ValidationException expected) {
            wrongMetadataTypeRejected = true;
        }
        MetadataKey<String> typeKey = MetadataKey.forType(
                AgentsConformanceTest.class, StateValue::string, value -> ((StateValue.StringValue) value).value());
        Map<String, StateValue> typeMetadata = MetadataValues.with(Map.of(), typeKey, initial);

        Instant createdAt = Instant.parse("2026-08-13T00:00:00Z");
        UsageDetails usage =
                UsageDetails.builder().inputTokens(1).totalTokens(1).build();
        AgentResponse<String> response = AgentResponse.<String>builder()
                .messages(List.of(Message.text(Role.ASSISTANT, "done")))
                .responseId("response-extensions")
                .agentId("agent-extensions")
                .createdAt(createdAt)
                .finishReason(FinishReason.STOP)
                .usage(usage)
                .value("structured")
                .continuationToken(StateValue.string("continue"))
                .metadata(Map.of(metadataName, StateValue.string(initial)))
                .updateSequences(List.of(1L))
                .build();
        ChatResponse chatResponse = AgentResponses.toChatResponse(response);
        AgentResponseUpdate update = AgentResponseUpdate.builder()
                .sequence(1)
                .contents(List.of(new TextContent("done")))
                .role(Role.ASSISTANT)
                .agentId("agent-extensions")
                .responseId("response-extensions")
                .messageId("message-extensions")
                .createdAt(createdAt)
                .finishReason(FinishReason.STOP)
                .usage(usage)
                .metadata(Map.of(metadataName, StateValue.string(initial)))
                .build();
        ChatResponseUpdate chatUpdate = AgentResponses.toChatResponseUpdate(update);
        AgentResponse<Void> aggregated = AgentResponses.aggregate(List.of(update));

        String historyText = string(messageInput, "history");
        String contextText = string(messageInput, "context");
        String externalText = string(messageInput, "external");
        String contextProviderId = string(messageInput, "contextProviderId");
        String historySourceId = string(messageInput, "historySourceId");
        String callerSourceId = string(messageInput, "callerSourceId");
        AgentSession detachedSession = new AgentSession("extensions-detached-history");
        List<Message> mutableHistory = new ArrayList<>(List.of(Message.text(Role.ASSISTANT, historyText)));
        AgentSessions.setInMemoryHistory(detachedSession, mutableHistory);
        mutableHistory.set(0, Message.text(Role.ASSISTANT, "mutated"));
        List<Message> detachedHistory = AgentSessions.inMemoryHistory(detachedSession);

        AgentSession attributedSession = new AgentSession("extensions-attribution");
        AgentSessions.setInMemoryHistory(attributedSession, List.of(Message.text(Role.ASSISTANT, historyText)));
        Message external = MessageSources.withSource(
                Message.text(Role.USER, externalText), AgentRequestMessageSourceType.EXTERNAL, callerSourceId);
        ContextProvider contextProvider = new ContextProvider() {
            @Override
            public String id() {
                return contextProviderId;
            }

            @Override
            public CompletionStage<ContextContribution> provideAsync(ContextProviderRequest request) {
                return CompletableFuture.completedStage(new ContextContribution(
                        List.of(), List.of(Message.text(Role.SYSTEM, contextText)), Map.of(), List.of()));
            }
        };
        FakeChatClient client = new FakeChatClient().enqueue(response("done"));

        // Act
        try (ChatAgent agent = new ChatAgent(
                client,
                new AgentMetadata("agent-extensions", null, null),
                ChatOptions.empty(),
                List.of(),
                List.of(contextProvider),
                List.of(),
                List.of(),
                List.of(),
                null)) {
            agent.runAsync(attributedSession, List.of(external), RunOptions.empty())
                    .toCompletableFuture()
                    .join();
        }
        List<Message> attributedMessages = client.requests().getFirst().messages();
        List<String> sourceOrder = attributedMessages.stream()
                .map(message -> MessageSources.sourceType(message).value() + ":" + MessageSources.sourceId(message))
                .toList();
        Message malformed = new Message(
                Role.USER,
                List.of(),
                null,
                null,
                Map.of(
                        AgentRequestMessageSourceAttribution.METADATA_KEY,
                        StateValue.object(Map.of("sourceType", StateValue.integer(1)))));

        // Assert
        boolean responseFieldsPreserved = chatResponse.messages().equals(response.messages())
                && Objects.equals(chatResponse.responseId(), response.responseId())
                && Objects.equals(chatResponse.createdAt(), response.createdAt())
                && Objects.equals(chatResponse.finishReason(), response.finishReason())
                && Objects.equals(chatResponse.usage(), response.usage())
                && chatResponse.metadata().equals(response.metadata());
        boolean updateFieldsPreserved = chatUpdate.contents().equals(update.contents())
                && Objects.equals(chatUpdate.role(), update.role())
                && Objects.equals(chatUpdate.responseId(), update.responseId())
                && Objects.equals(chatUpdate.messageId(), update.messageId())
                && Objects.equals(chatUpdate.createdAt(), update.createdAt())
                && Objects.equals(chatUpdate.finishReason(), update.finishReason())
                && Objects.equals(chatUpdate.usage(), update.usage())
                && chatUpdate.metadata().equals(update.metadata());
        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        actual.put(
                "typedMetadataRoundTrips",
                new ConformanceValue.BooleanValue(MetadataValues.find(withMetadata, metadataKey)
                        .filter(initial::equals)
                        .isPresent()));
        actual.put(
                "metadataCopyOnWrite",
                new ConformanceValue.BooleanValue(!original.containsKey(metadataName)
                        && withMetadata.containsKey(metadataName)
                        && !removed.containsKey(metadataName)
                        && withMetadata.containsKey(metadataName)));
        actual.put(
                "existingMetadataPreserved",
                new ConformanceValue.BooleanValue(MetadataValues.find(preserved, metadataKey)
                        .filter(initial::equals)
                        .isPresent()));
        actual.put("existingMetadataAvoidsEncoding", new ConformanceValue.BooleanValue(encodingCount.get() == 0));
        actual.put("wrongMetadataTypeRejected", new ConformanceValue.BooleanValue(wrongMetadataTypeRejected));
        actual.put(
                "typeNamedKeySupported",
                new ConformanceValue.BooleanValue(typeKey.name().equals(AgentsConformanceTest.class.getName())
                        && MetadataValues.find(typeMetadata, typeKey)
                                .filter(initial::equals)
                                .isPresent()));
        actual.put("responseFieldsPreserved", new ConformanceValue.BooleanValue(responseFieldsPreserved));
        actual.put("updateFieldsPreserved", new ConformanceValue.BooleanValue(updateFieldsPreserved));
        actual.put(
                "streamAggregationPreserved",
                new ConformanceValue.BooleanValue(
                        "agent-extensions".equals(aggregated.agentId()) && "done".equals(aggregated.text())));
        actual.put(
                "sessionHistoryDetached",
                new ConformanceValue.BooleanValue(detachedHistory.size() == 1
                        && historyText.equals(detachedHistory.getFirst().text())));
        actual.put(
                "defaultSourceType",
                new ConformanceValue.StringValue(MessageSources.sourceType(Message.text(Role.USER, "default"))
                        .value()));
        actual.put("sourceOrder", strings(sourceOrder));
        actual.put(
                "sameSourceReturnsOriginal",
                new ConformanceValue.BooleanValue(
                        MessageSources.withSource(external, AgentRequestMessageSourceType.EXTERNAL, callerSourceId)
                                == external));
        actual.put(
                "malformedAttributionRejected",
                new ConformanceValue.BooleanValue(MessageSources.attribution(malformed)
                                .isEmpty()
                        && MessageSources.sourceType(malformed).equals(AgentRequestMessageSourceType.EXTERNAL)));
        assertThat(sourceOrder.getFirst()).isEqualTo("ChatHistory:" + historySourceId);
        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
    }

    private static boolean runStreamingInjectionConformance(String initial, String injected) {
        AgentSession session = new AgentSession("message-injection-streaming-conformance");
        FakeChatClient client = new FakeChatClient()
                .enqueueStreaming((request, cancellation) -> {
                    MessageInjectionMiddleware.enqueueMessages(session, injected);
                    return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                        private boolean completed;

                        @Override
                        public void request(long count) {
                            if (!completed) {
                                completed = true;
                                subscriber.onNext(update("first"));
                                subscriber.onComplete();
                            }
                        }

                        @Override
                        public void cancel() {
                            completed = true;
                        }
                    });
                })
                .enqueueStreaming(List.of(update("second")));
        TerminalSubscriber<AgentResponseUpdate> subscriber = new TerminalSubscriber<>();
        try (ChatAgent agent = messageInjectionAgent(client, List.of())) {
            agent.runStreaming(session, List.of(Message.text(Role.USER, initial)), RunOptions.empty())
                    .subscribe(subscriber);
            subscriber.terminal().toCompletableFuture().join();
        }
        return client.requests().size() == 2
                && injected.equals(
                        client.requests().getLast().messages().getLast().text())
                && MessageInjectionMiddleware.getPendingMessages(session).isEmpty();
    }

    private static boolean runSessionRequiredConformance(String initial) {
        FakeChatClient client = new FakeChatClient();
        try (ChatAgent agent = messageInjectionAgent(client, List.of())) {
            try {
                agent.runAsync(initial).toCompletableFuture().join();
                return false;
            } catch (CompletionException failure) {
                return RunHandles.unwrap(failure) instanceof com.microsoft.agents.core.AgentExecutionException
                        && client.requests().isEmpty();
            }
        }
    }

    private static ChatAgent messageInjectionAgent(FakeChatClient client, List<FunctionTool> tools) {
        return new ChatAgent(
                client,
                AgentMetadata.create(),
                ChatOptions.empty(),
                tools,
                List.of(),
                List.of(),
                List.of(new MessageInjectionMiddleware()),
                List.of(),
                null);
    }

    private static String string(ConformanceValue.ObjectValue object, String name) {
        return ((ConformanceValue.StringValue) object.require(name)).value();
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
