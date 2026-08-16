// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.AgentRunContext;
import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.BaseAgent;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.UsageDetails;
import com.microsoft.agents.core.internal.SingleSubscriberPublisher;
import com.microsoft.agents.core.internal.StrictJsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapts an existing Azure AI Persistent service agent to the framework {@link
 * com.microsoft.agents.agents.Agent} contract.
 *
 * <p>Session-bound calls persist the service agent, thread, last-run, and stable submitted message
 * identifiers in {@link AgentSession} state. Messages with a stable {@link Message#messageId()} are
 * submitted at most once per session. Remote agents and threads remain caller-owned and are never
 * deleted on close.
 */
public final class AzureAIPersistentAgent extends BaseAgent<Void> {
    /** Session state key containing the service agent identifier. */
    public static final String AGENT_ID_STATE_KEY = "azureAiPersistent.agentId";
    /** Session state key containing the service thread identifier. */
    public static final String THREAD_ID_STATE_KEY = "azureAiPersistent.threadId";
    /** Session state key containing the latest service run identifier. */
    public static final String RUN_ID_STATE_KEY = "azureAiPersistent.runId";
    /** Session state key containing stable submitted framework message identifiers. */
    public static final String MESSAGE_IDS_STATE_KEY = "azureAiPersistent.submittedMessageIds";

    private static final StrictJsonCodec JSON = new StrictJsonCodec(1_048_576, 1_048_576, 64, 262_144, 256, 16_384);

    private final AzureAIPersistentClient client;
    private final PersistentAgentDefinition definition;
    private final boolean closeClient;

    AzureAIPersistentAgent(AzureAIPersistentClient client, PersistentAgentDefinition definition, boolean closeClient) {
        super(new AgentMetadata(
                definition.id(),
                definition.name() == null ? definition.id() : definition.name(),
                definition.description()));
        this.client = Objects.requireNonNull(client, "client");
        this.definition = Objects.requireNonNull(definition, "definition");
        this.closeClient = closeClient;
    }

    /** Returns immutable service-agent metadata. */
    public PersistentAgentDefinition serviceAgent() {
        return definition;
    }

    /**
     * Runs against a framework session and persists provider continuation metadata.
     *
     * @param session active framework session
     * @param messages ordered input messages
     * @param options run options
     * @param cancellation cancellation signal
     * @return terminal response stage
     */
    public CompletionStage<AgentResponse<Void>> runAsync(
            AgentSession session, List<Message> messages, RunOptions options, RunCancellation cancellation) {
        return startRunWithSession(messages, options, cancellation, session).resultAsync();
    }

    /**
     * Streams against a framework session and persists provider continuation metadata.
     *
     * @param session active framework session
     * @param messages ordered input messages
     * @param options run options
     * @param cancellation cancellation signal
     * @return native persistent run updates
     */
    public Flow.Publisher<AgentResponseUpdate> runStreaming(
            AgentSession session, List<Message> messages, RunOptions options, RunCancellation cancellation) {
        return runStreamingWithSession(messages, options, cancellation, session);
    }

    /**
     * Runs on an already authorized service thread.
     *
     * <p>The thread identifier selects continuation state; it is not an authorization credential.
     * Hosting code must bind it to an authenticated principal before calling this method.
     *
     * @param threadId authorized thread identifier
     * @param messages new input messages
     * @param options run options
     * @param cancellation cancellation signal
     * @return terminal response stage
     */
    public CompletionStage<AgentResponse<Void>> runOnThreadAsync(
            String threadId, List<Message> messages, RunOptions options, RunCancellation cancellation) {
        return runOnThreadAsync(threadId, messages, Set.of(), options, cancellation);
    }

    /**
     * Runs on an authorized service thread while skipping stable message identifiers that the host
     * already reserved.
     *
     * <p>Durable hosts should reserve new identifiers atomically before calling this overload. This
     * prevents concurrent retries from appending the same service message more than once.
     *
     * @param threadId authorized thread identifier
     * @param messages new input messages
     * @param submittedMessageIds stable identifiers already reserved by the host
     * @param options run options
     * @param cancellation cancellation signal
     * @return terminal response stage
     */
    public CompletionStage<AgentResponse<Void>> runOnThreadAsync(
            String threadId,
            List<Message> messages,
            Set<String> submittedMessageIds,
            RunOptions options,
            RunCancellation cancellation) {
        Set<String> submitted = Set.copyOf(Objects.requireNonNull(submittedMessageIds, "submittedMessageIds"));
        return submitMessagesAsync(nonBlank(threadId, "threadId"), List.copyOf(messages), null, submitted, cancellation)
                .thenCompose(ignored -> runAndMapAsync(threadId, options, cancellation, null));
    }

    /**
     * Creates a service thread for a host that has already established its authorization boundary.
     *
     * @param metadata bounded non-secret service metadata
     * @param cancellation cancellation signal
     * @return created thread
     */
    public CompletionStage<PersistentThread> createServiceThreadAsync(
            Map<String, String> metadata, RunCancellation cancellation) {
        return client.createThreadAsync(metadata, cancellation);
    }

    /**
     * Deletes a service thread only when an authorized caller explicitly requests cleanup.
     *
     * @param threadId authorized thread identifier
     * @param cancellation cancellation signal
     * @return completion stage
     */
    public CompletionStage<Void> deleteServiceThreadAsync(String threadId, RunCancellation cancellation) {
        return client.deleteThreadAsync(threadId, cancellation);
    }

    /**
     * Applies an explicit continuation and maps the resulting run to an Agent response.
     *
     * @param continuation continuation request
     * @param cancellation cancellation signal
     * @return mapped response
     */
    public CompletionStage<AgentResponse<Void>> continueRunAsync(
            PersistentRunContinuation continuation, RunCancellation cancellation) {
        return client.continueRunAsync(continuation, cancellation)
                .thenCompose(run -> responseForRunAsync(run, cancellation));
    }

    @Override
    protected CompletionStage<AgentResponse<Void>> executeAsync(AgentRunContext context) {
        return resolveThreadAsync(context.session(), context.cancellation())
                .thenCompose(threadId -> submitMessagesAsync(
                                threadId, context.inputMessages(), context.session(), Set.of(), context.cancellation())
                        .thenCompose(ignored -> runAndMapAsync(
                                threadId, context.options(), context.cancellation(), context.session())));
    }

    @Override
    protected StreamingExecution<Void> executeStreaming(AgentRunContext context) {
        CompletableFuture<AgentResponse<Void>> terminal = new CompletableFuture<>();
        AtomicReference<Flow.Subscription> upstream = new AtomicReference<>();
        AtomicReference<SingleSubscriberPublisher<AgentResponseUpdate>> sinkRef = new AtomicReference<>();
        AtomicReference<PersistentRun> latestRun = new AtomicReference<>();
        AtomicLong sequence = new AtomicLong();
        AtomicBoolean finished = new AtomicBoolean();

        SingleSubscriberPublisher<AgentResponseUpdate> sink = new SingleSubscriberPublisher<>(
                () -> resolveThreadAsync(context.session(), context.cancellation())
                        .thenCompose(threadId -> submitMessagesAsync(
                                        threadId,
                                        context.inputMessages(),
                                        context.session(),
                                        Set.of(),
                                        context.cancellation())
                                .thenApply(ignored -> threadId))
                        .whenComplete((threadId, setupFailure) -> {
                            if (setupFailure != null) {
                                failStream(sinkRef.get(), terminal, finished, unwrap(setupFailure));
                                return;
                            }
                            PersistentRunRequest request = runRequest(threadId, context.options());
                            client.createRunStreaming(request, context.cancellation())
                                    .subscribe(new Flow.Subscriber<>() {
                                        @Override
                                        public void onSubscribe(Flow.Subscription subscription) {
                                            if (!upstream.compareAndSet(null, subscription)) {
                                                subscription.cancel();
                                                return;
                                            }
                                            subscription.request(Long.MAX_VALUE);
                                        }

                                        @Override
                                        public void onNext(PersistentRunEvent item) {
                                            if (finished.get()) {
                                                return;
                                            }
                                            if (item.run() != null) {
                                                latestRun.set(item.run());
                                                persistRun(context.session(), item.run());
                                            }
                                            if (item.textDelta() != null
                                                    && !item.textDelta().isEmpty()) {
                                                sinkRef.get()
                                                        .emit(AgentResponseUpdate.builder()
                                                                .sequence(sequence.getAndIncrement())
                                                                .role(Role.ASSISTANT)
                                                                .agentId(definition.id())
                                                                .responseId(item.runId())
                                                                .messageId(item.messageId())
                                                                .contents(List.of(new TextContent(item.textDelta())))
                                                                .metadata(providerMetadata(threadId, item.runId()))
                                                                .build());
                                            }
                                        }

                                        @Override
                                        public void onError(Throwable throwable) {
                                            failStream(sinkRef.get(), terminal, finished, unwrap(throwable));
                                        }

                                        @Override
                                        public void onComplete() {
                                            PersistentRun run = latestRun.get();
                                            if (run == null) {
                                                failStream(
                                                        sinkRef.get(),
                                                        terminal,
                                                        finished,
                                                        protocol("stream_without_run"));
                                                return;
                                            }
                                            responseForRunAsync(run, context.cancellation())
                                                    .whenComplete((response, failure) -> {
                                                        if (failure != null) {
                                                            failStream(
                                                                    sinkRef.get(), terminal, finished, unwrap(failure));
                                                        } else if (finished.compareAndSet(false, true)) {
                                                            terminal.complete(response);
                                                            sinkRef.get().complete();
                                                        }
                                                    });
                                        }
                                    });
                        }),
                () -> {
                    Flow.Subscription subscription = upstream.get();
                    if (subscription != null) {
                        subscription.cancel();
                    }
                    context.cancellation().cancel();
                    if (finished.compareAndSet(false, true)) {
                        terminal.completeExceptionally(new RunCancelledException());
                    }
                },
                client.options().maxBufferedEvents());
        sinkRef.set(sink);
        return new StreamingExecution<>(sink, terminal.minimalCompletionStage());
    }

    @Override
    protected void closeResources() {
        if (closeClient) {
            client.close();
        }
    }

    private CompletionStage<String> resolveThreadAsync(AgentSession session, RunCancellation cancellation) {
        if (session == null) {
            return client.createThreadAsync(Map.of("af_agent_id", definition.id()), cancellation)
                    .thenApply(PersistentThread::id);
        }
        String storedAgent = stateString(session, AGENT_ID_STATE_KEY);
        if (storedAgent != null && !storedAgent.equals(definition.id())) {
            return CompletableFuture.failedFuture(new AzureAIPersistentException(
                    "AgentSession belongs to a different persistent agent.",
                    null,
                    AzureAIPersistentException.Kind.CONFIGURATION,
                    null,
                    null,
                    "session_agent_mismatch",
                    null));
        }
        String threadId = stateString(session, THREAD_ID_STATE_KEY);
        if (threadId != null) {
            return CompletableFuture.completedStage(threadId);
        }
        return client.createThreadAsync(
                        Map.of(
                                "af_agent_id", definition.id(),
                                "af_session_id", session.sessionId()),
                        cancellation)
                .thenApply(thread -> {
                    session.putState(AGENT_ID_STATE_KEY, StateValue.string(definition.id()));
                    session.putState(THREAD_ID_STATE_KEY, StateValue.string(thread.id()));
                    return thread.id();
                });
    }

    private CompletionStage<Void> submitMessagesAsync(
            String threadId,
            List<Message> input,
            AgentSession session,
            Set<String> externallySubmitted,
            RunCancellation cancellation) {
        LinkedHashSet<String> submitted = new LinkedHashSet<>(submittedIds(session));
        submitted.addAll(externallySubmitted);
        CompletionStage<Void> stage = CompletableFuture.completedStage(null);
        for (Message message : input) {
            if (!Role.USER.equals(message.role()) && !Role.ASSISTANT.equals(message.role())) {
                continue;
            }
            stage = stage.thenCompose(ignored -> {
                String messageId = message.messageId();
                if (messageId != null && submitted.contains(messageId)) {
                    return CompletableFuture.completedStage(null);
                }
                Map<String, String> metadata = messageId == null ? Map.of() : Map.of("af_message_id", messageId);
                return client.createMessageAsync(
                                threadId, message.role(), message.text(), List.of(), metadata, cancellation)
                        .thenAccept(created -> {
                            if (messageId != null) {
                                submitted.add(messageId);
                                if (session != null) {
                                    addSubmittedId(session, messageId);
                                }
                            }
                        });
            });
        }
        return stage;
    }

    private CompletionStage<AgentResponse<Void>> runAndMapAsync(
            String threadId, RunOptions options, RunCancellation cancellation, AgentSession session) {
        RunHandle<PersistentRun> handle = client.startRun(runRequest(threadId, options), cancellation);
        return handle.resultAsync().thenCompose(run -> {
            persistRun(session, run);
            return responseForRunAsync(run, cancellation);
        });
    }

    private PersistentRunRequest runRequest(String threadId, RunOptions options) {
        StateValue additional = options.metadata().get("azureAiPersistent.additionalInstructions");
        String instructions = additional instanceof StateValue.StringValue value ? value.value() : null;
        return new PersistentRunRequest(
                threadId, definition.id(), instructions, null, null, Map.of("af_agent_id", definition.id()));
    }

    private CompletionStage<AgentResponse<Void>> responseForRunAsync(PersistentRun run, RunCancellation cancellation) {
        if (run.status().equals(PersistentRunStatus.CANCELLED)) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        if (run.status().equals(PersistentRunStatus.FAILED) || run.status().equals(PersistentRunStatus.EXPIRED)) {
            return CompletableFuture.failedFuture(new AzureAIPersistentException(
                    "Azure AI Persistent run ended with status " + run.status().value() + ".",
                    null,
                    AzureAIPersistentException.Kind.SERVICE,
                    null,
                    null,
                    run.errorCode() == null ? run.status().value() : run.errorCode(),
                    null));
        }
        if (run.status().equals(PersistentRunStatus.REQUIRES_ACTION)) {
            return CompletableFuture.completedStage(requiredActionResponse(run));
        }
        if (!run.status().equals(PersistentRunStatus.COMPLETED)) {
            return CompletableFuture.failedFuture(protocol("non_terminal_run_result"));
        }
        return collectMessagesAsync(run.threadId(), run.id(), cancellation)
                .thenApply(messages -> AgentResponse.<Void>builder()
                        .messages(messages.stream()
                                .filter(message -> Role.ASSISTANT.equals(message.role()))
                                .map(message -> Message.builder(message.role())
                                        .messageId(message.id())
                                        .contents(List.of(new TextContent(message.text())))
                                        .metadata(Map.of("azureAiPersistent.runId", StateValue.string(run.id())))
                                        .build())
                                .toList())
                        .responseId(run.id())
                        .agentId(definition.id())
                        .createdAt(run.createdAt())
                        .finishReason(FinishReason.STOP)
                        .usage(usage(run.usage()))
                        .metadata(providerMetadata(run.threadId(), run.id()))
                        .build());
    }

    private CompletionStage<List<PersistentMessage>> collectMessagesAsync(
            String threadId, String runId, RunCancellation cancellation) {
        ArrayList<PersistentMessage> result = new ArrayList<>();
        CompletableFuture<List<PersistentMessage>> terminal = new CompletableFuture<>();
        collectMessagePage(threadId, runId, null, 0, result, cancellation, terminal);
        return terminal.minimalCompletionStage();
    }

    private void collectMessagePage(
            String threadId,
            String runId,
            String cursor,
            int page,
            ArrayList<PersistentMessage> result,
            RunCancellation cancellation,
            CompletableFuture<List<PersistentMessage>> terminal) {
        if (page >= 100) {
            terminal.completeExceptionally(protocol("message_page_limit"));
            return;
        }
        client.listMessagesAsync(threadId, runId, client.options().maxPageSize(), cursor, cancellation)
                .whenComplete((messages, failure) -> {
                    if (failure != null) {
                        terminal.completeExceptionally(unwrap(failure));
                        return;
                    }
                    result.addAll(messages.items());
                    if (messages.hasMore()) {
                        collectMessagePage(
                                threadId, runId, messages.nextCursor(), page + 1, result, cancellation, terminal);
                    } else {
                        terminal.complete(List.copyOf(result));
                    }
                });
    }

    private AgentResponse<Void> requiredActionResponse(PersistentRun run) {
        PersistentRequiredAction action = run.requiredAction();
        if (action == null || !action.supported()) {
            throw protocol("unsupported_required_action");
        }
        List<FunctionCallContent> calls = action.toolCalls().stream()
                .map(call -> new FunctionCallContent(
                        call.id(),
                        call.name() == null ? call.type() : call.name(),
                        arguments(call.argumentsJson()),
                        true,
                        Map.of("azureAiPersistent.supported", StateValue.bool(call.supported()))))
                .toList();
        StateValue continuation = StateValue.object(Map.of(
                "kind", StateValue.string("submit_tool_outputs"),
                "threadId", StateValue.string(run.threadId()),
                "runId", StateValue.string(run.id()),
                "processLocal", StateValue.bool(false)));
        return AgentResponse.<Void>builder()
                .messages(List.of(new Message(Role.ASSISTANT, calls)))
                .responseId(run.id())
                .agentId(definition.id())
                .createdAt(run.createdAt())
                .finishReason(FinishReason.TOOL_CALLS)
                .continuationToken(continuation)
                .metadata(providerMetadata(run.threadId(), run.id()))
                .build();
    }

    private static StateValue arguments(String json) {
        if (json == null || json.isBlank()) {
            return StateValue.object(Map.of());
        }
        return JSON.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static UsageDetails usage(PersistentRunUsage usage) {
        if (usage == null) {
            return null;
        }
        return UsageDetails.builder()
                .inputTokens(usage.promptTokens())
                .outputTokens(usage.completionTokens())
                .totalTokens(usage.totalTokens())
                .build();
    }

    private static Map<String, StateValue> providerMetadata(String threadId, String runId) {
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        metadata.put("azureAiPersistent.threadId", StateValue.string(threadId));
        if (runId != null) {
            metadata.put("azureAiPersistent.runId", StateValue.string(runId));
        }
        return Map.copyOf(metadata);
    }

    private static void persistRun(AgentSession session, PersistentRun run) {
        if (session != null) {
            session.putState(RUN_ID_STATE_KEY, StateValue.string(run.id()));
        }
    }

    private static String stateString(AgentSession session, String key) {
        return session.state()
                .get(key)
                .filter(StateValue.StringValue.class::isInstance)
                .map(StateValue.StringValue.class::cast)
                .map(StateValue.StringValue::value)
                .orElse(null);
    }

    private static Set<String> submittedIds(AgentSession session) {
        if (session == null) {
            return Set.of();
        }
        StateValue value = session.state().get(MESSAGE_IDS_STATE_KEY).orElse(null);
        if (!(value instanceof StateValue.ArrayValue array)) {
            return Set.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        array.values().stream()
                .filter(StateValue.StringValue.class::isInstance)
                .map(StateValue.StringValue.class::cast)
                .map(StateValue.StringValue::value)
                .forEach(ids::add);
        return Set.copyOf(ids);
    }

    private static void addSubmittedId(AgentSession session, String messageId) {
        session.updateState(MESSAGE_IDS_STATE_KEY, current -> {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            if (current instanceof StateValue.ArrayValue array) {
                array.values().stream()
                        .filter(StateValue.StringValue.class::isInstance)
                        .map(StateValue.StringValue.class::cast)
                        .map(StateValue.StringValue::value)
                        .forEach(ids::add);
            }
            ids.add(messageId);
            if (ids.size() > 10_000) {
                throw protocol("submitted_message_id_limit");
            }
            return StateValue.array(ids.stream().map(StateValue::string).toList());
        });
    }

    private static void failStream(
            SingleSubscriberPublisher<AgentResponseUpdate> sink,
            CompletableFuture<AgentResponse<Void>> terminal,
            AtomicBoolean finished,
            Throwable failure) {
        if (finished.compareAndSet(false, true)) {
            terminal.completeExceptionally(failure);
            sink.fail(failure);
        }
    }

    private static AzureAIPersistentException protocol(String code) {
        return new AzureAIPersistentException(
                "Azure AI Persistent protocol mapping failed.",
                null,
                AzureAIPersistentException.Kind.PROTOCOL,
                null,
                null,
                code,
                null);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
