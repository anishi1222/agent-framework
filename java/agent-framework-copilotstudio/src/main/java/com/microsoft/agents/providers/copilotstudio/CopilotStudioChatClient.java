// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.ErrorContent;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.MetadataContent;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.internal.SingleSubscriberPublisher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapts Copilot Studio conversations to the provider-neutral {@link ChatClient} contract.
 *
 * <p>Only the newest user activity is sent on each turn. Earlier framework messages are never
 * replayed into a conversation identified by {@code ChatOptions.conversationId}.
 */
public final class CopilotStudioChatClient implements ChatClient {
    /** Request/response metadata key for the SSE resume identity. */
    public static final String LAST_EVENT_ID_METADATA_KEY = "copilotStudio.lastEventId";

    /** Request/response metadata key for the local cursor sequence. */
    public static final String CURSOR_SEQUENCE_METADATA_KEY = "copilotStudio.cursorSequence";

    /** Response metadata key containing the latest activity identity. */
    public static final String ACTIVITY_ID_METADATA_KEY = "copilotStudio.activityId";

    private final CopilotStudioClient client;

    private final boolean closeClient;

    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a caller-owned-client adapter.
     *
     * @param client caller-owned client
     */
    public CopilotStudioChatClient(CopilotStudioClient client) {
        this(client, false);
    }

    /**
     * Creates an adapter with explicit client ownership.
     *
     * @param client client
     * @param closeClient whether closing this adapter closes the client
     */
    public CopilotStudioChatClient(CopilotStudioClient client, boolean closeClient) {
        this.client = Objects.requireNonNull(client, "client");
        this.closeClient = closeClient;
    }

    /**
     * Returns the configured client.
     *
     * @return client
     */
    public CopilotStudioClient client() {
        return client;
    }

    @Override
    public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
        validate(request, cancellation);
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("CopilotStudioChatClient is closed."));
        }
        return contextAsync(request, cancellation).thenCompose(context -> {
            CopilotStudioActivity activity =
                    CopilotStudioActivity.message(activityId(request), context.conversationId(), prompt(request));
            return client.sendActivityAsync(context.conversationId(), activity, context.cursor(), cancellation)
                    .thenApply(events -> response(context.conversationId(), events, false));
        });
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> completeStreaming(
            ChatClientRequest request, RunCancellation cancellation) {
        validate(request, cancellation);
        StreamingRun run = new StreamingRun(request, cancellation);
        SingleSubscriberPublisher<ChatResponseUpdate> publisher = new SingleSubscriberPublisher<>(
                run::start,
                run::cancel,
                client.options().limits().maxBufferedEvents(),
                ignored -> new CopilotStudioException(
                        "Copilot Studio chat event buffer overflow.",
                        null,
                        CopilotStudioException.Kind.LIMIT,
                        null,
                        "chat_event_buffer"));
        run.sink = publisher;
        return publisher;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && closeClient) {
            client.close();
        }
    }

    private CompletionStage<ConversationContext> contextAsync(ChatClientRequest request, RunCancellation cancellation) {
        String existing = request.options().conversationId();
        if (existing != null) {
            return CompletableFuture.completedStage(new ConversationContext(existing, cursor(request)));
        }
        return client.startConversationAsync(cancellation)
                .thenApply(
                        conversation -> new ConversationContext(conversation.conversationId(), conversation.cursor()));
    }

    private static ChatResponse response(String conversationId, List<CopilotStudioEvent> events, boolean streaming) {
        ArrayList<Message> messages = new ArrayList<>();
        CopilotStudioEvent last = null;
        CopilotStudioEvent continuation = null;
        for (CopilotStudioEvent event : events) {
            Message mapped = message(event, streaming);
            if (mapped != null) {
                messages.add(mapped);
            }
            if (event.type() == CopilotStudioEventType.OAUTH_REQUIRED
                    || event.type() == CopilotStudioEventType.INPUT_REQUIRED) {
                continuation = event;
            }
            last = event;
        }
        FinishReason finishReason = continuation == null ? FinishReason.STOP : FinishReason.of("inputRequired");
        return ChatResponse.builder()
                .messages(messages)
                .responseId(last == null ? null : last.activity().id())
                .conversationId(conversationId)
                .createdAt(last == null ? null : last.activity().timestamp())
                .finishReason(finishReason)
                .continuationToken(
                        continuation == null ? null : continuation.activity().raw())
                .metadata(metadata(last, conversationId))
                .updateSequences(
                        events.stream().map(event -> event.cursor().sequence()).toList())
                .build();
    }

    private static Message message(CopilotStudioEvent event, boolean streaming) {
        if (streaming && event.type() == CopilotStudioEventType.MESSAGE) {
            return null;
        }
        if (!streaming && event.type() == CopilotStudioEventType.TYPING) {
            return null;
        }
        CopilotStudioActivity activity = event.activity();
        ArrayList<Content> contents = new ArrayList<>();
        if (activity.text() != null && !activity.text().isBlank()) {
            contents.add(new TextContent(activity.text()));
        }
        if (!activity.attachments().isEmpty() || !activity.citations().isEmpty()) {
            LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
            values.put("activity", activity.raw());
            contents.add(new MetadataContent(values));
        }
        if (event.type() == CopilotStudioEventType.ERROR) {
            contents.add(new ErrorContent(
                    activity.text() == null || activity.text().isBlank()
                            ? "Copilot Studio activity reported an error."
                            : activity.text(),
                    "copilot_studio",
                    null));
        }
        if (contents.isEmpty()
                && event.type() != CopilotStudioEventType.OAUTH_REQUIRED
                && event.type() != CopilotStudioEventType.INPUT_REQUIRED) {
            return null;
        }
        if (contents.isEmpty()) {
            contents.add(new MetadataContent(Map.of("activity", activity.raw())));
        }
        return Message.builder(Role.ASSISTANT)
                .contents(contents)
                .authorName(activity.from() == null ? null : activity.from().name())
                .messageId(activity.id())
                .metadata(eventMetadata(event))
                .build();
    }

    private static ChatResponseUpdate update(CopilotStudioEvent event) {
        Message message = message(event, true);
        if (message == null) {
            return null;
        }
        return new ChatResponseUpdate(
                event.cursor().sequence(),
                message.contents(),
                Role.ASSISTANT,
                message.authorName(),
                event.activity().id(),
                event.activity().id(),
                event.activity().conversationId(),
                null,
                event.activity().timestamp(),
                null,
                null,
                event.type() == CopilotStudioEventType.OAUTH_REQUIRED
                                || event.type() == CopilotStudioEventType.INPUT_REQUIRED
                        ? event.activity().raw()
                        : null,
                eventMetadata(event));
    }

    private static ChatResponseUpdate terminal(
            CopilotStudioEvent last, String conversationId, CopilotStudioEvent continuation) {
        FinishReason reason = continuation == null ? FinishReason.STOP : FinishReason.of("inputRequired");
        return new ChatResponseUpdate(
                last == null ? 0 : last.cursor().sequence() + 1,
                List.of(),
                Role.ASSISTANT,
                null,
                last == null ? null : last.activity().id(),
                last == null ? null : last.activity().id(),
                conversationId,
                null,
                last == null ? null : last.activity().timestamp(),
                reason,
                null,
                continuation == null ? null : continuation.activity().raw(),
                metadata(last, conversationId));
    }

    private static Map<String, StateValue> metadata(CopilotStudioEvent event, String conversationId) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        values.put("copilotStudio.conversationId", StateValue.string(conversationId));
        if (event != null) {
            values.putAll(eventMetadata(event));
        }
        return Map.copyOf(values);
    }

    private static Map<String, StateValue> eventMetadata(CopilotStudioEvent event) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        if (event.cursor().lastEventId() != null) {
            values.put(
                    LAST_EVENT_ID_METADATA_KEY, StateValue.string(event.cursor().lastEventId()));
        }
        values.put(
                CURSOR_SEQUENCE_METADATA_KEY, StateValue.integer(event.cursor().sequence()));
        if (event.activity().id() != null) {
            values.put(
                    ACTIVITY_ID_METADATA_KEY, StateValue.string(event.activity().id()));
        }
        values.put("copilotStudio.eventType", StateValue.string(event.type().name()));
        return Map.copyOf(values);
    }

    private static CopilotStudioCursor cursor(ChatClientRequest request) {
        StateValue eventId = request.options().metadata().get(LAST_EVENT_ID_METADATA_KEY);
        StateValue sequence = request.options().metadata().get(CURSOR_SEQUENCE_METADATA_KEY);
        String id = eventId instanceof StateValue.StringValue string ? string.value() : null;
        long number =
                sequence instanceof StateValue.NumberValue value ? value.value().longValue() : 0;
        return new CopilotStudioCursor(id, Math.max(0, number));
    }

    private static String activityId(ChatClientRequest request) {
        for (int index = request.messages().size() - 1; index >= 0; index--) {
            Message message = request.messages().get(index);
            if (message.role() == Role.USER && message.messageId() != null) {
                return message.messageId();
            }
        }
        return UUID.randomUUID().toString();
    }

    private static String prompt(ChatClientRequest request) {
        for (int index = request.messages().size() - 1; index >= 0; index--) {
            Message message = request.messages().get(index);
            if (message.role() == Role.USER && !message.text().isBlank()) {
                return message.text();
            }
        }
        throw new IllegalArgumentException("request must contain a non-blank user message.");
    }

    private static void validate(ChatClientRequest request, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        if (request.messages().isEmpty()) {
            throw new IllegalArgumentException("request.messages must not be empty.");
        }
        if (request.options().structuredOutput() != null) {
            throw new IllegalArgumentException("Copilot Studio does not support ChatOptions.structuredOutput.");
        }
        if (!request.tools().isEmpty()) {
            throw new IllegalArgumentException(
                    "Copilot Studio card actions are explicit activities, not framework function tools.");
        }
        prompt(request);
    }

    private record ConversationContext(String conversationId, CopilotStudioCursor cursor) {}

    private final class StreamingRun {
        private final ChatClientRequest request;

        private final RunCancellation cancellation;

        private final AtomicBoolean finished = new AtomicBoolean();

        private final AtomicReference<Flow.Subscription> upstream = new AtomicReference<>();

        private String conversationId;

        private CopilotStudioEvent last;

        private CopilotStudioEvent continuation;

        private SingleSubscriberPublisher<ChatResponseUpdate> sink;

        private StreamingRun(ChatClientRequest request, RunCancellation cancellation) {
            this.request = request;
            this.cancellation = cancellation;
        }

        private void start() {
            if (closed.get()) {
                fail(new IllegalStateException("CopilotStudioChatClient is closed."));
                return;
            }
            if (cancellation.isCancellationRequested()) {
                fail(new RunCancelledException());
                return;
            }
            contextAsync(request, cancellation).whenComplete((context, contextFailure) -> {
                if (contextFailure != null) {
                    fail(contextFailure);
                    return;
                }
                conversationId = context.conversationId();
                CopilotStudioActivity activity =
                        CopilotStudioActivity.message(activityId(request), conversationId, prompt(request));
                client.sendActivityStreaming(conversationId, activity, context.cursor(), cancellation)
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
                            public void onNext(CopilotStudioEvent event) {
                                last = event;
                                if (event.type() == CopilotStudioEventType.OAUTH_REQUIRED
                                        || event.type() == CopilotStudioEventType.INPUT_REQUIRED) {
                                    continuation = event;
                                }
                                ChatResponseUpdate mapped = update(event);
                                if (mapped != null) {
                                    sink.emit(mapped);
                                }
                                if (event.type() == CopilotStudioEventType.ERROR) {
                                    fail(new CopilotStudioException(
                                            event.activity().text() == null
                                                    ? "Copilot Studio activity reported an error."
                                                    : event.activity().text(),
                                            null,
                                            CopilotStudioException.Kind.SERVICE,
                                            null,
                                            "activity_error"));
                                }
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                fail(throwable);
                            }

                            @Override
                            public void onComplete() {
                                if (finished.compareAndSet(false, true)) {
                                    sink.emit(terminal(last, conversationId, continuation));
                                    sink.complete();
                                }
                            }
                        });
            });
        }

        private void fail(Throwable failure) {
            if (finished.compareAndSet(false, true)) {
                Flow.Subscription subscription = upstream.getAndSet(null);
                if (subscription != null) {
                    subscription.cancel();
                }
                sink.fail(CopilotStudioClient.normalize(failure, "chat"));
            }
        }

        private void cancel() {
            cancellation.cancel();
            fail(new RunCancelledException());
        }
    }
}
