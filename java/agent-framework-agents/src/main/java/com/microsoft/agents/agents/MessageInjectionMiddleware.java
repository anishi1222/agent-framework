// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.AgentExecutionException;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.internal.MessageStateCodec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Enables session-scoped messages to be injected into an active {@link ChatAgent} model loop.
 *
 * <p>Register one instance in the chat-middleware collection passed to {@link ChatAgent}. The agent
 * recognizes this middleware at its function-loop boundary rather than invoking a chat continuation
 * repeatedly, which preserves the single-use continuation contract. Pending messages are encoded as
 * JSON-shaped session state and atomically drained before the next provider turn.
 */
public final class MessageInjectionMiddleware implements ChatMiddleware {
    /** Session-state key containing the encoded pending-message queue. */
    public static final String PENDING_MESSAGES_STATE_KEY = "messageInjection.pendingMessages";

    private static final MessageStateCodec MESSAGE_CODEC = new MessageStateCodec();

    /**
     * Enqueues one user text message.
     *
     * @param session target session
     * @param text non-blank user text
     */
    public static void enqueueMessages(AgentSession session, String text) {
        enqueueMessages(session, List.of(Message.text(Role.USER, text)));
    }

    /**
     * Enqueues one immutable message.
     *
     * @param session target session
     * @param message message to enqueue
     */
    public static void enqueueMessages(AgentSession session, Message message) {
        enqueueMessages(session, List.of(AgentValidation.requireNonNull(message, "message")));
    }

    /**
     * Atomically appends messages to the target session queue.
     *
     * @param session target session
     * @param messages ordered messages to enqueue
     */
    public static void enqueueMessages(AgentSession session, Collection<? extends Message> messages) {
        AgentSession safeSession = AgentValidation.requireNonNull(session, "session");
        AgentValidation.requireNonNull(messages, "messages");
        ArrayList<Message> copied = new ArrayList<>(messages.size());
        for (Message message : messages) {
            copied.add(AgentValidation.requireNonNull(message, "message"));
        }
        if (copied.isEmpty()) {
            return;
        }
        safeSession.updateState(PENDING_MESSAGES_STATE_KEY, current -> {
            ArrayList<Message> pending = new ArrayList<>(decodeQueue(current));
            pending.addAll(copied);
            return encodeQueue(pending);
        });
    }

    /**
     * Returns an immutable point-in-time snapshot of pending messages.
     *
     * @param session target session
     * @return queued messages in delivery order
     */
    public static List<Message> getPendingMessages(AgentSession session) {
        AgentSession safeSession = AgentValidation.requireNonNull(session, "session");
        return safeSession
                .state()
                .get(PENDING_MESSAGES_STATE_KEY)
                .map(MessageInjectionMiddleware::decodeQueue)
                .orElseGet(List::of);
    }

    static List<Message> drainPendingMessages(AgentSession session) {
        AgentSession safeSession = AgentValidation.requireNonNull(session, "session");
        AtomicReference<List<Message>> drained = new AtomicReference<>(List.of());
        safeSession.updateState(PENDING_MESSAGES_STATE_KEY, current -> {
            drained.set(decodeQueue(current));
            return encodeQueue(List.of());
        });
        return drained.get();
    }

    @Override
    public CompletionStage<ChatResponse> invokeAsync(ChatMiddlewareContext context, ChatMiddlewareNext next) {
        return CompletableFuture.failedFuture(directUseFailure());
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> invokeStreaming(
            ChatMiddlewareContext context, ChatStreamingMiddlewareNext next) {
        return MiddlewarePublishers.failed(directUseFailure());
    }

    private static StateValue.ArrayValue encodeQueue(List<Message> messages) {
        return StateValue.array(messages.stream().map(MESSAGE_CODEC::encode).toList());
    }

    private static List<Message> decodeQueue(StateValue value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof StateValue.ArrayValue array)) {
            throw new AgentExecutionException(
                    "Session state '" + PENDING_MESSAGES_STATE_KEY + "' must contain an encoded message array.");
        }
        try {
            return array.values().stream().map(MESSAGE_CODEC::decode).toList();
        } catch (RuntimeException failure) {
            throw new AgentExecutionException(
                    "Session state '" + PENDING_MESSAGES_STATE_KEY + "' contains an invalid message.", failure);
        }
    }

    private static AgentExecutionException directUseFailure() {
        return new AgentExecutionException(
                "MessageInjectionMiddleware must be registered on ChatAgent so it can span provider turns.");
    }
}
