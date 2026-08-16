// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.AgentRunContext;
import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.BaseAgent;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.ResponseAggregator;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.internal.SingleSubscriberPublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapts GitHub Copilot CLI sessions to the framework {@link
 * com.microsoft.agents.agents.Agent} contract.
 *
 * <p>The external Copilot session ID is stored as JSON-shaped session metadata. It identifies
 * continuation state but is not an authorization credential; hosting applications must partition
 * framework sessions per authenticated principal.
 */
public final class GitHubCopilotAgent extends BaseAgent<Void> {
    /** Session-state key containing the external Copilot session identity. */
    public static final String SESSION_ID_STATE_KEY = "githubCopilot.sessionId";

    private final GitHubCopilotChatClient chatClient;

    private final GitHubCopilotSessionConfig sessionConfig;

    /**
     * Creates an agent using a caller-owned client.
     *
     * @param client caller-owned client
     * @param sessionConfig default session configuration
     * @param metadata agent metadata
     */
    public GitHubCopilotAgent(
            GitHubCopilotClient client, GitHubCopilotSessionConfig sessionConfig, AgentMetadata metadata) {
        this(client, sessionConfig, metadata, false);
    }

    /**
     * Creates an agent with explicit client ownership.
     *
     * @param client client
     * @param sessionConfig default session configuration
     * @param metadata agent metadata
     * @param closeClient whether closing the agent closes the client
     */
    public GitHubCopilotAgent(
            GitHubCopilotClient client,
            GitHubCopilotSessionConfig sessionConfig,
            AgentMetadata metadata,
            boolean closeClient) {
        super(Objects.requireNonNull(metadata, "metadata"));
        this.sessionConfig = Objects.requireNonNull(sessionConfig, "sessionConfig");
        chatClient = new GitHubCopilotChatClient(Objects.requireNonNull(client, "client"), sessionConfig, closeClient);
    }

    /**
     * Starts a finite run bound to a framework session.
     *
     * @param session active framework session
     * @param messages new input messages
     * @param options run options
     * @param cancellation cancellation signal
     * @return run handle
     */
    public RunHandle<AgentResponse<Void>> startRun(
            AgentSession session, List<Message> messages, RunOptions options, RunCancellation cancellation) {
        return startRunWithSession(messages, options, cancellation, session);
    }

    /**
     * Streams a run bound to a framework session.
     *
     * @param session active framework session
     * @param messages new input messages
     * @param options run options
     * @param cancellation cancellation signal
     * @return streaming updates
     */
    public Flow.Publisher<AgentResponseUpdate> runStreaming(
            AgentSession session, List<Message> messages, RunOptions options, RunCancellation cancellation) {
        return runStreamingWithSession(messages, options, cancellation, session);
    }

    /**
     * Returns the provider-neutral chat adapter.
     *
     * @return chat adapter
     */
    public GitHubCopilotChatClient chatClient() {
        return chatClient;
    }

    @Override
    protected CompletionStage<AgentResponse<Void>> executeAsync(AgentRunContext context) {
        ChatClientRequest request = request(context);
        return chatClient.completeAsync(request, context.cancellation()).thenApply(response -> {
            persistSession(context.session(), response.conversationId());
            return agentResponse(response);
        });
    }

    @Override
    protected StreamingExecution<Void> executeStreaming(AgentRunContext context) {
        CompletableFuture<AgentResponse<Void>> terminal = new CompletableFuture<>();
        AtomicReference<Flow.Subscription> upstream = new AtomicReference<>();
        AtomicReference<SingleSubscriberPublisher<AgentResponseUpdate>> sinkReference = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean();
        ArrayList<ChatResponseUpdate> updates = new ArrayList<>();
        ChatClientRequest request = request(context);
        SingleSubscriberPublisher<AgentResponseUpdate> sink = new SingleSubscriberPublisher<>(
                () -> chatClient
                        .completeStreaming(request, context.cancellation())
                        .subscribe(new Flow.Subscriber<>() {
                            @Override
                            public void onSubscribe(Flow.Subscription subscription) {
                                if (!upstream.compareAndSet(null, subscription)) {
                                    subscription.cancel();
                                } else {
                                    subscription.request(Long.MAX_VALUE);
                                }
                            }

                            @Override
                            public void onNext(ChatResponseUpdate update) {
                                updates.add(update);
                                if (update.conversationId() != null) {
                                    persistSession(context.session(), update.conversationId());
                                }
                                sinkReference.get().emit(agentUpdate(update));
                            }

                            @Override
                            public void onError(Throwable failure) {
                                if (finished.compareAndSet(false, true)) {
                                    terminal.completeExceptionally(failure);
                                    sinkReference.get().fail(failure);
                                }
                            }

                            @Override
                            public void onComplete() {
                                if (!finished.compareAndSet(false, true)) {
                                    return;
                                }
                                try {
                                    ChatResponse response = ResponseAggregator.aggregateChat(updates);
                                    persistSession(context.session(), response.conversationId());
                                    terminal.complete(agentResponse(response));
                                    sinkReference.get().complete();
                                } catch (RuntimeException failure) {
                                    terminal.completeExceptionally(failure);
                                    sinkReference.get().fail(failure);
                                }
                            }
                        }),
                () -> {
                    context.cancellation().cancel();
                    Flow.Subscription subscription = upstream.get();
                    if (subscription != null) {
                        subscription.cancel();
                    }
                },
                chatClient.client().options().limits().maxBufferedEvents());
        sinkReference.set(sink);
        return new StreamingExecution<>(sink, terminal.minimalCompletionStage());
    }

    @Override
    protected void closeResources() {
        chatClient.close();
    }

    private ChatClientRequest request(AgentRunContext context) {
        String externalSessionId = stateString(context.session());
        ChatOptions.Builder options = ChatOptions.builder();
        if (sessionConfig.model() != null) {
            options.model(sessionConfig.model());
        }
        if (externalSessionId != null) {
            options.conversationId(externalSessionId);
        }
        return new ChatClientRequest(context.inputMessages(), options.build());
    }

    private AgentResponse<Void> agentResponse(ChatResponse response) {
        return AgentResponse.<Void>builder()
                .messages(response.messages())
                .responseId(response.responseId())
                .agentId(metadata().id())
                .createdAt(response.createdAt())
                .finishReason(response.finishReason())
                .usage(response.usage())
                .continuationToken(response.continuationToken())
                .metadata(withSession(response.metadata(), response.conversationId()))
                .updateSequences(response.updateSequences())
                .build();
    }

    private AgentResponseUpdate agentUpdate(ChatResponseUpdate update) {
        AgentResponseUpdate.Builder builder = AgentResponseUpdate.builder()
                .contents(update.contents())
                .role(update.role())
                .agentId(metadata().id())
                .metadata(withSession(update.metadata(), update.conversationId()));
        if (update.sequence() != null) {
            builder.sequence(update.sequence());
        }
        if (update.authorName() != null) {
            builder.authorName(update.authorName());
        }
        if (update.responseId() != null) {
            builder.responseId(update.responseId());
        }
        if (update.messageId() != null) {
            builder.messageId(update.messageId());
        }
        if (update.createdAt() != null) {
            builder.createdAt(update.createdAt());
        }
        if (update.finishReason() != null) {
            builder.finishReason(update.finishReason());
        }
        if (update.usage() != null) {
            builder.usage(update.usage());
        }
        if (update.continuationToken() != null) {
            builder.continuationToken(update.continuationToken());
        }
        return builder.build();
    }

    private static Map<String, StateValue> withSession(Map<String, StateValue> metadata, String sessionId) {
        if (sessionId == null) {
            return metadata;
        }
        java.util.LinkedHashMap<String, StateValue> result = new java.util.LinkedHashMap<>(metadata);
        result.put(SESSION_ID_STATE_KEY, StateValue.string(sessionId));
        return Map.copyOf(result);
    }

    private static void persistSession(AgentSession session, String sessionId) {
        if (session != null && sessionId != null && !sessionId.isBlank()) {
            session.putState(SESSION_ID_STATE_KEY, StateValue.string(sessionId));
        }
    }

    private static String stateString(AgentSession session) {
        if (session == null) {
            return null;
        }
        StateValue value = session.state().get(SESSION_ID_STATE_KEY).orElse(null);
        return value instanceof StateValue.StringValue string ? string.value() : null;
    }
}
