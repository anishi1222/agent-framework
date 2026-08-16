// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.InvocationId;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolInvocationContext;
import com.microsoft.agents.tools.ToolInvocationInterceptContext;
import com.microsoft.agents.tools.ToolMetadata;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ContextAndMiddlewareTest {
    @Test
    void contextProviders_shouldContributeInOrderWithoutMutatingCallerData_andPersistHistoryInOrder() {
        // Arrange
        AgentSession session = new AgentSession("session-context");
        session.appendMessages(List.of(Message.text(Role.USER, "earlier")));
        InMemoryHistoryProvider history = new InMemoryHistoryProvider("history");
        List<Message> callerMessages = new ArrayList<>(List.of(Message.text(Role.USER, "current")));
        FunctionTool contributedTool = tool("context-tool", new AtomicInteger());
        ContextProvider memory = new ContextProvider() {
            @Override
            public String id() {
                return "memory";
            }

            @Override
            public java.util.concurrent.CompletionStage<ContextContribution> provideAsync(
                    ContextProviderRequest request) {
                assertThat(request.messages()).extracting(Message::text).containsExactly("earlier", "current");
                return CompletableFuture.completedFuture(new ContextContribution(
                        List.of("remember this"),
                        List.of(Message.text(Role.SYSTEM, "remembered")),
                        Map.of("memory", StateValue.bool(true)),
                        List.of(contributedTool)));
            }
        };
        FakeChatClient client = new FakeChatClient().enqueue(response("answer"));

        // Act
        AgentRunResult<Void> result;
        try (ChatAgent agent =
                configured(client, List.of(), List.of(history, memory), List.of(), List.of(), List.of(), null)) {
            result = agent.runAsync(session, callerMessages, RunOptions.empty())
                    .toCompletableFuture()
                    .join();
        }

        // Assert
        assertThat(result.outcome()).isEqualTo(AgentRunOutcome.COMPLETED);
        assertThat(callerMessages).containsExactly(Message.text(Role.USER, "current"));
        assertThat(client.requests()).singleElement().satisfies(request -> {
            assertThat(request.messages())
                    .extracting(Message::text)
                    .containsExactly("earlier", "remembered", "current");
            assertThat(request.options().instructions()).isEqualTo("remember this");
            assertThat(request.tools()).extracting(ToolMetadata::name).containsExactly("context-tool");
            assertThat(request.runContext().metadata()).containsEntry("memory", StateValue.bool(true));
        });
        assertThat(session.messages()).extracting(Message::text).containsExactly("earlier", "current", "answer");
    }

    @Test
    void contextProviderFailure_shouldPropagateWithoutCallingModel() {
        // Arrange
        RuntimeException providerFailure = new RuntimeException("context unavailable");
        ContextProvider failing = provider("failing", request -> CompletableFuture.failedFuture(providerFailure));
        FakeChatClient client = new FakeChatClient();

        // Act and assert
        try (ChatAgent agent = configured(client, List.of(), List.of(failing), List.of(), List.of(), List.of(), null)) {
            assertThatThrownBy(() -> agent.runAsync(new AgentSession("s"), "hello")
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(CompletionException.class)
                    .hasRootCause(providerFailure);
        }
        assertThat(client.requests()).isEmpty();
    }

    @Test
    void laterContextProviderFailure_shouldCompleteEveryInvokedProvider() {
        // Arrange
        RuntimeException providerFailure = new RuntimeException("context unavailable");
        RuntimeException completionFailure = new RuntimeException("cleanup unavailable");
        AtomicInteger firstCompletions = new AtomicInteger();
        AtomicInteger secondCompletions = new AtomicInteger();
        ContextProvider first = new ContextProvider() {
            @Override
            public String id() {
                return "first";
            }

            @Override
            public java.util.concurrent.CompletionStage<ContextContribution> provideAsync(
                    ContextProviderRequest request) {
                return CompletableFuture.completedFuture(ContextContribution.empty());
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> completedAsync(ContextProviderCompletion completion) {
                firstCompletions.incrementAndGet();
                assertThat(completion.response()).isNull();
                assertThat(completion.failure()).isSameAs(providerFailure);
                return CompletableFuture.failedFuture(completionFailure);
            }
        };
        ContextProvider second = new ContextProvider() {
            @Override
            public String id() {
                return "second";
            }

            @Override
            public java.util.concurrent.CompletionStage<ContextContribution> provideAsync(
                    ContextProviderRequest request) {
                return CompletableFuture.failedFuture(providerFailure);
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> completedAsync(ContextProviderCompletion completion) {
                secondCompletions.incrementAndGet();
                assertThat(completion.response()).isNull();
                assertThat(completion.failure()).isSameAs(providerFailure);
                return CompletableFuture.completedFuture(null);
            }
        };
        FakeChatClient client = new FakeChatClient();

        // Act and assert
        try (ChatAgent agent =
                configured(client, List.of(), List.of(first, second), List.of(), List.of(), List.of(), null)) {
            assertThatThrownBy(() -> agent.runAsync(new AgentSession("s"), "hello")
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(CompletionException.class)
                    .hasRootCause(providerFailure);
        }
        assertThat(providerFailure.getSuppressed()).containsExactly(completionFailure);
        assertThat(firstCompletions).hasValue(1);
        assertThat(secondCompletions).hasValue(1);
        assertThat(client.requests()).isEmpty();
    }

    @Test
    void agentMiddleware_shouldNestInRegistrationOrder_shortCircuit_andIsolateMetadataAcrossRuns() {
        // Arrange
        List<String> order = new ArrayList<>();
        AtomicInteger isolatedRuns = new AtomicInteger();
        AgentMiddleware<Void> first = new AgentMiddleware<>() {
            @Override
            public java.util.concurrent.CompletionStage<AgentResponse<Void>> invokeAsync(
                    AgentMiddlewareContext<Void> context, AgentMiddlewareNext<Void> next) {
                order.add("first.before");
                assertThat(context.metadata().get("seen")).isEmpty();
                context.metadata().put("seen", StateValue.bool(true));
                isolatedRuns.incrementAndGet();
                return next.invokeAsync(context).thenApply(response -> {
                    order.add("first.after");
                    return response;
                });
            }
        };
        AgentMiddleware<Void> second = new AgentMiddleware<>() {
            @Override
            public java.util.concurrent.CompletionStage<AgentResponse<Void>> invokeAsync(
                    AgentMiddlewareContext<Void> context, AgentMiddlewareNext<Void> next) {
                order.add("second.before");
                return next.invokeAsync(context).thenApply(response -> {
                    order.add("second.after");
                    return response;
                });
            }
        };
        FakeChatClient client = new FakeChatClient().enqueue(response("one")).enqueue(response("two"));

        // Act
        try (ChatAgent agent =
                configured(client, List.of(), List.of(), List.of(first, second), List.of(), List.of(), null)) {
            agent.run("one");
            agent.run("two");
        }

        // Assert
        assertThat(order)
                .containsExactly(
                        "first.before",
                        "second.before",
                        "second.after",
                        "first.after",
                        "first.before",
                        "second.before",
                        "second.after",
                        "first.after");
        assertThat(isolatedRuns).hasValue(2);

        AgentResponse<Void> shortCircuit = responseAsAgent("blocked");
        AgentMiddleware<Void> terminating = new AgentMiddleware<>() {
            @Override
            public java.util.concurrent.CompletionStage<AgentResponse<Void>> invokeAsync(
                    AgentMiddlewareContext<Void> context, AgentMiddlewareNext<Void> next) {
                return CompletableFuture.completedFuture(shortCircuit);
            }
        };
        FakeChatClient unused = new FakeChatClient();
        try (ChatAgent agent =
                configured(unused, List.of(), List.of(), List.of(terminating), List.of(), List.of(), null)) {
            assertThat(agent.run("blocked")).isEqualTo(shortCircuit);
        }
        assertThat(unused.requests()).isEmpty();
    }

    @Test
    void middlewarePipelines_shouldRejectDoubleNext_propagateErrors_andHonorCancellation() {
        // Arrange
        ChatMiddleware doubleNext = new ChatMiddleware() {
            @Override
            public java.util.concurrent.CompletionStage<ChatResponse> invokeAsync(
                    ChatMiddlewareContext context, ChatMiddlewareNext next) {
                return next.invokeAsync(context).thenCompose(ignored -> next.invokeAsync(context));
            }
        };
        ChatMiddlewarePipeline pipeline = new ChatMiddlewarePipeline(List.of(doubleNext));
        ChatMiddlewareContext context = new ChatMiddlewareContext(
                new ChatClientRequest(List.of(Message.text(Role.USER, "hello")), ChatOptions.empty()),
                new DefaultRunCancellation(),
                new MiddlewareMetadata());

        // Act and assert
        assertThatThrownBy(() -> pipeline.executeAsync(
                                context, ignored -> CompletableFuture.completedFuture(response("unused")))
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(MiddlewareException.class)
                .hasMessageContaining("more than once");

        RuntimeException translated = new RuntimeException("translated");
        ChatMiddleware translator = new ChatMiddleware() {
            @Override
            public java.util.concurrent.CompletionStage<ChatResponse> invokeAsync(
                    ChatMiddlewareContext ignored, ChatMiddlewareNext next) {
                return next.invokeAsync(ignored).exceptionally(failure -> {
                    throw translated;
                });
            }
        };
        assertThatThrownBy(() -> new ChatMiddlewarePipeline(List.of(translator))
                        .executeAsync(context, ignored -> CompletableFuture.failedFuture(new RuntimeException("inner")))
                        .toCompletableFuture()
                        .join())
                .hasRootCause(translated);

        DefaultRunCancellation cancelled = new DefaultRunCancellation();
        cancelled.cancel();
        AtomicInteger terminalCalls = new AtomicInteger();
        ChatMiddlewareContext cancelledContext =
                new ChatMiddlewareContext(context.request(), cancelled, new MiddlewareMetadata());
        assertThatThrownBy(() -> new ChatMiddlewarePipeline(List.of())
                        .executeAsync(cancelledContext, ignored -> {
                            terminalCalls.incrementAndGet();
                            return CompletableFuture.completedFuture(response("unused"));
                        })
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(com.microsoft.agents.core.RunCancelledException.class);
        assertThat(terminalCalls).hasValue(0);
    }

    @Test
    void agentAndFunctionPipelines_shouldRejectDoubleNext_translateErrors_andHonorCancellation() {
        // Arrange
        FakeChatClient client = new FakeChatClient();
        try (ChatAgent agent = new ChatAgent(client)) {
            DefaultRunCancellation cancellation = new DefaultRunCancellation();
            AgentRunContext runContext = new AgentRunContext(
                    "run-middleware",
                    agent.metadata(),
                    Instant.now(),
                    List.of(Message.text(Role.USER, "hello")),
                    RunOptions.empty(),
                    cancellation,
                    Map.of());
            AgentMiddlewareContext<Void> agentContext =
                    new AgentMiddlewareContext<>(agent, runContext, new MiddlewareMetadata());
            AgentMiddleware<Void> doubleNext = new AgentMiddleware<>() {
                @Override
                public java.util.concurrent.CompletionStage<AgentResponse<Void>> invokeAsync(
                        AgentMiddlewareContext<Void> context, AgentMiddlewareNext<Void> next) {
                    return next.invokeAsync(context).thenCompose(ignored -> next.invokeAsync(context));
                }
            };
            assertThatThrownBy(() -> new AgentMiddlewarePipeline<>(List.of(doubleNext))
                            .executeAsync(
                                    agentContext,
                                    ignored -> CompletableFuture.completedFuture(responseAsAgent("terminal")))
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(MiddlewareException.class);

            FunctionTool functionTool = tool("function", new AtomicInteger());
            DefaultRunCancellation functionCancellation = new DefaultRunCancellation();
            FunctionMiddlewareContext functionContext = new FunctionMiddlewareContext(
                    null,
                    new ToolInvocationInterceptContext(
                            functionTool,
                            new ToolInvocationContext(
                                    "logical-run",
                                    "call",
                                    new InvocationId("invocation"),
                                    functionCancellation,
                                    Runnable::run,
                                    Map.of()),
                            StateValue.object(Map.of())),
                    new MiddlewareMetadata());
            FunctionMiddleware functionDouble =
                    (context, next) -> next.invokeAsync(context).thenCompose(ignored -> next.invokeAsync(context));
            assertThatThrownBy(() -> new FunctionMiddlewarePipeline(List.of(functionDouble))
                            .executeAsync(
                                    functionContext,
                                    ignored -> CompletableFuture.completedFuture(StateValue.string("terminal")))
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(MiddlewareException.class);

            RuntimeException translated = new RuntimeException("function translated");
            FunctionMiddleware translator =
                    (context, next) -> next.invokeAsync(context).exceptionally(failure -> {
                        throw translated;
                    });
            assertThatThrownBy(() -> new FunctionMiddlewarePipeline(List.of(translator))
                            .executeAsync(
                                    functionContext,
                                    ignored -> CompletableFuture.failedFuture(new RuntimeException("inner")))
                            .toCompletableFuture()
                            .join())
                    .hasRootCause(translated);

            functionCancellation.cancel();
            AtomicInteger terminalCalls = new AtomicInteger();
            assertThatThrownBy(() -> new FunctionMiddlewarePipeline(List.of())
                            .executeAsync(functionContext, ignored -> {
                                terminalCalls.incrementAndGet();
                                return CompletableFuture.completedFuture(StateValue.string("unused"));
                            })
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(com.microsoft.agents.core.RunCancelledException.class);
            assertThat(terminalCalls).hasValue(0);
        }
    }

    @Test
    void streamingMiddleware_shouldExecuteOnceInOrder_andSupportValidShortCircuit() {
        // Arrange
        List<String> order = new ArrayList<>();
        AgentMiddleware<Void> middleware = new AgentMiddleware<>() {
            @Override
            public AgentStreamingResult<Void> invokeStreaming(
                    AgentMiddlewareContext<Void> context, AgentStreamingMiddlewareNext<Void> next) {
                order.add("before");
                AgentStreamingResult<Void> result = next.invokeStreaming(context);
                order.add("after");
                return result;
            }
        };
        FakeChatClient client = new FakeChatClient()
                .enqueueStreaming(List.of(com.microsoft.agents.core.ChatResponseUpdate.builder()
                        .role(Role.ASSISTANT)
                        .contents(List.of(new com.microsoft.agents.core.TextContent("streamed")))
                        .finishReason(FinishReason.STOP)
                        .build()));
        ChatAgent agent = configured(client, List.of(), List.of(), List.of(middleware), List.of(), List.of(), null);
        RecordingAgentSubscriber subscriber = new RecordingAgentSubscriber();

        // Act
        try (agent) {
            agent.runStreaming("hello").subscribe(subscriber);
            subscriber.terminal().join();
        }

        // Assert
        assertThat(order).containsExactly("before", "after");
        assertThat(subscriber.updates)
                .singleElement()
                .satisfies(update -> assertThat(update.text()).isEqualTo("streamed"));

        AgentMiddleware<Void> shortCircuit = new AgentMiddleware<>() {
            @Override
            public AgentStreamingResult<Void> invokeStreaming(
                    AgentMiddlewareContext<Void> context, AgentStreamingMiddlewareNext<Void> next) {
                com.microsoft.agents.core.AgentResponseUpdate update =
                        com.microsoft.agents.core.AgentResponseUpdate.builder()
                                .contents(List.of(new com.microsoft.agents.core.TextContent("blocked")))
                                .build();
                return new AgentStreamingResult<>(
                        downstream -> downstream.onSubscribe(new java.util.concurrent.Flow.Subscription() {
                            private boolean emitted;

                            @Override
                            public void request(long count) {
                                if (!emitted) {
                                    emitted = true;
                                    downstream.onNext(update);
                                    downstream.onComplete();
                                }
                            }

                            @Override
                            public void cancel() {
                                emitted = true;
                            }
                        }),
                        CompletableFuture.completedFuture(responseAsAgent("blocked")));
            }
        };
        FakeChatClient unused = new FakeChatClient();
        RecordingAgentSubscriber shortSubscriber = new RecordingAgentSubscriber();
        try (ChatAgent shortAgent =
                configured(unused, List.of(), List.of(), List.of(shortCircuit), List.of(), List.of(), null)) {
            shortAgent.runStreaming("hello").subscribe(shortSubscriber);
            shortSubscriber.terminal().join();
        }
        assertThat(shortSubscriber.updates)
                .singleElement()
                .satisfies(update -> assertThat(update.text()).isEqualTo("blocked"));
        assertThat(unused.requests()).isEmpty();
    }

    @Test
    void chatAndFunctionMiddleware_shouldInterceptProductionCalls_andFunctionCanShortCircuit() {
        // Arrange
        List<String> order = new ArrayList<>();
        ChatMiddleware chat = new ChatMiddleware() {
            @Override
            public java.util.concurrent.CompletionStage<ChatResponse> invokeAsync(
                    ChatMiddlewareContext context, ChatMiddlewareNext next) {
                order.add("chat.before");
                return next.invokeAsync(context).thenApply(response -> {
                    order.add("chat.after");
                    return response;
                });
            }
        };
        FunctionMiddleware function = (context, next) -> {
            order.add("function.before");
            return next.invokeAsync(context).thenApply(value -> {
                order.add("function.after");
                return value;
            });
        };
        AtomicInteger toolCalls = new AtomicInteger();
        FunctionTool tool = tool("lookup", toolCalls);
        FakeChatClient client = functionConversation();

        // Act
        AgentResponse<Void> response;
        try (ChatAgent agent =
                configured(client, List.of(tool), List.of(), List.of(), List.of(chat), List.of(function), null)) {
            response = agent.run("use tool");
        }

        // Assert
        assertThat(response.text()).contains("done");
        assertThat(toolCalls).hasValue(1);
        assertThat(order)
                .containsExactly(
                        "chat.before", "chat.after", "function.before", "function.after", "chat.before", "chat.after");

        AtomicInteger blockedToolCalls = new AtomicInteger();
        FunctionMiddleware terminating =
                (context, next) -> CompletableFuture.completedFuture(StateValue.string("middleware-value"));
        FakeChatClient shortClient = functionConversation();
        try (ChatAgent agent = configured(
                shortClient,
                List.of(tool("lookup", blockedToolCalls)),
                List.of(),
                List.of(),
                List.of(),
                List.of(terminating),
                null)) {
            AgentResponse<Void> result = agent.run("short");
            assertThat(result.messages().stream()
                            .flatMap(message -> message.contents().stream())
                            .filter(FunctionResultContent.class::isInstance)
                            .map(FunctionResultContent.class::cast)
                            .map(FunctionResultContent::result))
                    .contains(StateValue.string("middleware-value"));
        }
        assertThat(blockedToolCalls).hasValue(0);
    }

    private static ContextProvider provider(
            String id,
            java.util.function.Function<
                            ContextProviderRequest, java.util.concurrent.CompletionStage<ContextContribution>>
                    function) {
        return new ContextProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public java.util.concurrent.CompletionStage<ContextContribution> provideAsync(
                    ContextProviderRequest request) {
                return function.apply(request);
            }
        };
    }

    private static ChatAgent configured(
            ChatClient client,
            List<? extends com.microsoft.agents.tools.Tool> tools,
            List<? extends ContextProvider> providers,
            List<? extends AgentMiddleware<Void>> agentMiddleware,
            List<? extends ChatMiddleware> chatMiddleware,
            List<? extends FunctionMiddleware> functionMiddleware,
            SessionStore store) {
        return new ChatAgent(
                client,
                new AgentMetadata("agent-test", "test", null),
                ChatOptions.empty(),
                tools,
                providers,
                agentMiddleware,
                chatMiddleware,
                functionMiddleware,
                store);
    }

    private static FunctionTool tool(String name, AtomicInteger calls) {
        ToolMetadata metadata = new ToolMetadata(
                name,
                "test tool",
                Set.of(ToolCapability.FUNCTION),
                ToolApprovalMode.NEVER_REQUIRE,
                StateValue.object(Map.of("type", StateValue.string("object"))),
                StateValue.object(Map.of()));
        return FunctionTool.create(metadata, (context, arguments) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(StateValue.string("tool-value"));
        });
    }

    private static FakeChatClient functionConversation() {
        FunctionCallContent call = new FunctionCallContent("call-1", "lookup", StateValue.object(Map.of()));
        return new FakeChatClient()
                .enqueue(ChatResponse.builder()
                        .messages(List.of(new Message(Role.ASSISTANT, List.of(call))))
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build())
                .enqueue(response("done"));
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder()
                .messages(List.of(Message.text(Role.ASSISTANT, text)))
                .finishReason(FinishReason.STOP)
                .build();
    }

    private static AgentResponse<Void> responseAsAgent(String text) {
        return new AgentResponse<>(
                List.of(Message.text(Role.ASSISTANT, text)),
                null,
                "agent-test",
                null,
                FinishReason.STOP,
                null,
                null,
                null,
                Map.of(),
                List.of());
    }

    private static final class RecordingAgentSubscriber
            implements java.util.concurrent.Flow.Subscriber<com.microsoft.agents.core.AgentResponseUpdate> {
        private final List<com.microsoft.agents.core.AgentResponseUpdate> updates =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        private final CompletableFuture<Void> terminal = new CompletableFuture<>();

        @Override
        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(com.microsoft.agents.core.AgentResponseUpdate item) {
            updates.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            terminal.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            terminal.complete(null);
        }

        private CompletableFuture<Void> terminal() {
            return terminal;
        }
    }
}
