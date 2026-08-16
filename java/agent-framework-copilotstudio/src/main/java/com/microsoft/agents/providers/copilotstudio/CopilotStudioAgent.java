// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapts a Copilot Studio agent to the framework {@link
 * com.microsoft.agents.agents.Agent} contract.
 *
 * <p>Conversation identity is continuation state, not authorization. Hosting applications must bind
 * each {@link AgentSession} to an authenticated principal and persist it through a compare-and-set
 * {@link com.microsoft.agents.agents.SessionStore}. Stable input message IDs are reserved atomically
 * in session metadata before transport so one framework session never submits the same ID twice.
 */
public final class CopilotStudioAgent extends BaseAgent<Void> {
    /** Session-state key containing the service conversation identity. */
    public static final String CONVERSATION_ID_STATE_KEY = "copilotStudio.conversationId";

    /** Session-state key containing the latest SSE event identity. */
    public static final String LAST_EVENT_ID_STATE_KEY = "copilotStudio.lastEventId";

    /** Session-state key containing the local cursor sequence. */
    public static final String CURSOR_SEQUENCE_STATE_KEY = "copilotStudio.cursorSequence";

    /** Session-state key containing atomically reserved stable input activity IDs. */
    public static final String SUBMITTED_ACTIVITY_IDS_STATE_KEY = "copilotStudio.submittedActivityIds";

    /** Session-state key containing bounded received activity IDs. */
    public static final String RECEIVED_ACTIVITY_IDS_STATE_KEY = "copilotStudio.receivedActivityIds";

    private final CopilotStudioChatClient chatClient;

    /**
     * Creates an agent using a caller-owned client.
     *
     * @param client caller-owned client
     * @param metadata agent metadata
     */
    public CopilotStudioAgent(CopilotStudioClient client, AgentMetadata metadata) {
        this(client, metadata, false);
    }

    /**
     * Creates an agent with explicit client ownership.
     *
     * @param client client
     * @param metadata agent metadata
     * @param closeClient whether closing the agent closes the client
     */
    public CopilotStudioAgent(CopilotStudioClient client, AgentMetadata metadata, boolean closeClient) {
        super(Objects.requireNonNull(metadata, "metadata"));
        chatClient = new CopilotStudioChatClient(Objects.requireNonNull(client, "client"), closeClient);
    }

    /**
     * Starts a finite run bound to a framework session.
     *
     * @param session principal-bound session
     * @param messages new messages
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
     * @param session principal-bound session
     * @param messages new messages
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
    public CopilotStudioChatClient chatClient() {
        return chatClient;
    }

    @Override
    protected CompletionStage<AgentResponse<Void>> executeAsync(AgentRunContext context) {
        if (context.session() != null) {
            reserveStableMessageIds(context.session(), context.inputMessages());
        }
        return chatClient
                .completeAsync(request(context), context.cancellation())
                .thenApply(response -> {
                    persist(context.session(), response);
                    return agentResponse(response);
                });
    }

    @Override
    protected StreamingExecution<Void> executeStreaming(AgentRunContext context) {
        if (context.session() != null) {
            reserveStableMessageIds(context.session(), context.inputMessages());
        }
        CompletableFuture<AgentResponse<Void>> terminal = new CompletableFuture<>();
        AtomicReference<Flow.Subscription> upstream = new AtomicReference<>();
        AtomicReference<SingleSubscriberPublisher<AgentResponseUpdate>> sinkReference = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean();
        ArrayList<ChatResponseUpdate> updates = new ArrayList<>();
        SingleSubscriberPublisher<AgentResponseUpdate> sink = new SingleSubscriberPublisher<>(
                () -> chatClient
                        .completeStreaming(request(context), context.cancellation())
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
                                persist(context.session(), update);
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
                                    persist(context.session(), response);
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
        ChatOptions.Builder options = ChatOptions.builder();
        AgentSession session = context.session();
        String conversationId = stateString(session, CONVERSATION_ID_STATE_KEY);
        if (conversationId != null) {
            options.conversationId(conversationId);
        }
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        String eventId = stateString(session, LAST_EVENT_ID_STATE_KEY);
        if (eventId != null) {
            metadata.put(CopilotStudioChatClient.LAST_EVENT_ID_METADATA_KEY, StateValue.string(eventId));
        }
        long sequence = stateLong(session, CURSOR_SEQUENCE_STATE_KEY);
        metadata.put(CopilotStudioChatClient.CURSOR_SEQUENCE_METADATA_KEY, StateValue.integer(sequence));
        options.metadata(metadata);
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
                .metadata(response.metadata())
                .updateSequences(response.updateSequences())
                .build();
    }

    private AgentResponseUpdate agentUpdate(ChatResponseUpdate update) {
        AgentResponseUpdate.Builder builder = AgentResponseUpdate.builder()
                .contents(update.contents())
                .role(update.role())
                .agentId(metadata().id())
                .metadata(update.metadata());
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

    private void persist(AgentSession session, ChatResponse response) {
        if (session == null) {
            return;
        }
        if (response.conversationId() != null) {
            session.putState(CONVERSATION_ID_STATE_KEY, StateValue.string(response.conversationId()));
        }
        persistCursor(session, response.metadata());
    }

    private void persist(AgentSession session, ChatResponseUpdate update) {
        if (session == null) {
            return;
        }
        if (update.conversationId() != null) {
            session.putState(CONVERSATION_ID_STATE_KEY, StateValue.string(update.conversationId()));
        }
        persistCursor(session, update.metadata());
    }

    private void persistCursor(AgentSession session, Map<String, StateValue> metadata) {
        StateValue eventId = metadata.get(CopilotStudioChatClient.LAST_EVENT_ID_METADATA_KEY);
        if (eventId instanceof StateValue.StringValue string) {
            session.putState(LAST_EVENT_ID_STATE_KEY, StateValue.string(string.value()));
        }
        StateValue sequence = metadata.get(CopilotStudioChatClient.CURSOR_SEQUENCE_METADATA_KEY);
        if (sequence instanceof StateValue.NumberValue number) {
            session.putState(CURSOR_SEQUENCE_STATE_KEY, StateValue.number(number.value()));
        }
        StateValue activityId = metadata.get(CopilotStudioChatClient.ACTIVITY_ID_METADATA_KEY);
        if (activityId instanceof StateValue.StringValue string) {
            addBoundedId(session, RECEIVED_ACTIVITY_IDS_STATE_KEY, string.value());
        }
    }

    private void reserveStableMessageIds(AgentSession session, List<Message> messages) {
        if (session == null) {
            return;
        }
        List<String> ids = messages.stream()
                .filter(message -> message.role() == com.microsoft.agents.core.Role.USER)
                .map(Message::messageId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        AtomicReference<String> duplicate = new AtomicReference<>();
        session.updateState(SUBMITTED_ACTIVITY_IDS_STATE_KEY, current -> {
            LinkedHashSet<String> known = new LinkedHashSet<>();
            if (current instanceof StateValue.ArrayValue array) {
                array.values().forEach(value -> {
                    if (value instanceof StateValue.StringValue string) {
                        known.add(string.value());
                    }
                });
            }
            for (String id : ids) {
                if (!known.add(id)) {
                    duplicate.compareAndSet(null, id);
                }
            }
            while (known.size() > chatClient.client().options().limits().maxRememberedActivityIds()) {
                known.remove(known.iterator().next());
            }
            return StateValue.array(known.stream().map(StateValue::string).toList());
        });
        if (duplicate.get() != null) {
            throw new CopilotStudioException(
                    "Stable input activity ID was already submitted by this session.",
                    null,
                    CopilotStudioException.Kind.CONFIGURATION,
                    null,
                    "duplicate_activity_id");
        }
    }

    private void addBoundedId(AgentSession session, String key, String id) {
        session.updateState(key, current -> {
            LinkedHashSet<String> known = new LinkedHashSet<>();
            if (current instanceof StateValue.ArrayValue array) {
                array.values().forEach(value -> {
                    if (value instanceof StateValue.StringValue string) {
                        known.add(string.value());
                    }
                });
            }
            known.add(id);
            while (known.size() > chatClient.client().options().limits().maxRememberedActivityIds()) {
                known.remove(known.iterator().next());
            }
            return StateValue.array(known.stream().map(StateValue::string).toList());
        });
    }

    private static String stateString(AgentSession session, String key) {
        if (session == null) {
            return null;
        }
        StateValue value = session.state().get(key).orElse(null);
        return value instanceof StateValue.StringValue string ? string.value() : null;
    }

    private static long stateLong(AgentSession session, String key) {
        if (session == null) {
            return 0;
        }
        StateValue value = session.state().get(key).orElse(null);
        if (!(value instanceof StateValue.NumberValue number)) {
            return 0;
        }
        try {
            return Math.max(0, number.value().longValueExact());
        } catch (ArithmeticException exception) {
            return 0;
        }
    }
}
