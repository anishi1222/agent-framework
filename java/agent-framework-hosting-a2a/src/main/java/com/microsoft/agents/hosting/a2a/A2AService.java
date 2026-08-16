// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.protocols.a2a.A2ACursorPage;
import com.microsoft.agents.protocols.a2a.A2AErrorCode;
import com.microsoft.agents.protocols.a2a.A2AException;
import com.microsoft.agents.protocols.a2a.A2AProtocolException;
import com.microsoft.agents.protocols.a2a.A2ARequests;
import com.microsoft.agents.protocols.a2a.A2AStreamEvent;
import com.microsoft.agents.protocols.a2a.AgentCard;
import com.microsoft.agents.protocols.a2a.Artifact;
import com.microsoft.agents.protocols.a2a.Message;
import com.microsoft.agents.protocols.a2a.PushNotificationConfig;
import com.microsoft.agents.protocols.a2a.SendMessageRequest;
import com.microsoft.agents.protocols.a2a.SendMessageResult;
import com.microsoft.agents.protocols.a2a.Task;
import com.microsoft.agents.protocols.a2a.TaskArtifactUpdateEvent;
import com.microsoft.agents.protocols.a2a.TaskState;
import com.microsoft.agents.protocols.a2a.TaskStatus;
import com.microsoft.agents.protocols.a2a.TaskStatusUpdateEvent;
import com.microsoft.agents.protocols.a2a.TextPart;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Implements principal-isolated A2A v1 task, streaming, cancellation, card, and push-config
 * operations.
 *
 * <p>Task and context identifiers are correlation values only. Every store lookup includes the
 * authenticated {@link A2APrincipal}; knowing an identifier never grants access. Push configuration
 * is stored but this service intentionally performs no outbound webhook delivery.
 */
public final class A2AService implements AutoCloseable {
    private final AgentCard publicCard;

    private final AgentCard extendedCard;

    private final A2AExecutor executor;

    private final A2ATaskStore taskStore;

    private final A2APushNotificationConfigStore pushStore;

    private final A2AEventBroker eventBroker;

    private final Clock clock;

    private final ExecutorService controlExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final Map<ExecutionKey, ActiveExecution> active = new ConcurrentHashMap<>();

    private final Map<MessageKey, String> idempotency = new LinkedHashMap<>();

    private final Set<MessageKey> idempotencyReservations = new HashSet<>();

    private final Map<MessageKey, CompletableFuture<PreparedTask>> preparations = new ConcurrentHashMap<>();

    private final int maxIdempotencyEntries;

    private final AtomicBoolean closed = new AtomicBoolean();

    private A2AService(Builder builder) {
        publicCard = Objects.requireNonNull(builder.publicCard, "publicCard");
        extendedCard = builder.extendedCard;
        executor = Objects.requireNonNull(builder.executor, "executor");
        taskStore = Objects.requireNonNull(builder.taskStore, "taskStore");
        pushStore = builder.pushStore;
        eventBroker = new A2AEventBroker(builder.maxEventChannels, builder.maxBufferedEvents);
        clock = Objects.requireNonNull(builder.clock, "clock");
        maxIdempotencyEntries = HostingA2AValidation.positive(builder.maxIdempotencyEntries, "maxIdempotencyEntries");
        if (publicCard.capabilities().pushNotifications() != (pushStore != null)) {
            throw new com.microsoft.agents.core.ValidationException(
                    "AgentCard pushNotifications must match configured push store.");
        }
        if (!publicCard.capabilities().extendedAgentCard() && extendedCard != null) {
            throw new com.microsoft.agents.core.ValidationException(
                    "An extended card cannot be configured unless the public card advertises it.");
        }
    }

    /**
     * Creates a service builder.
     *
     * @param publicCard public card
     * @param executor framework execution adapter
     * @return builder
     */
    public static Builder builder(AgentCard publicCard, A2AExecutor executor) {
        return new Builder(publicCard, executor);
    }

    /** Returns the public agent card. */
    public AgentCard publicCard() {
        return publicCard;
    }

    /**
     * Returns the authenticated extended card.
     *
     * @param request request
     * @return extended card
     */
    public CompletionStage<AgentCard> getExtendedAgentCardAsync(A2ARequests.GetExtendedAgentCard request) {
        Objects.requireNonNull(request, "request");
        if (extendedCard == null) {
            if (!publicCard.capabilities().extendedAgentCard()) {
                return CompletableFuture.failedFuture(new A2AProtocolException(
                        A2AErrorCode.UNSUPPORTED_OPERATION, "Extended agent card operation is not supported."));
            }
            return CompletableFuture.failedFuture(new A2AProtocolException(
                    A2AErrorCode.EXTENDED_AGENT_CARD_NOT_CONFIGURED, "Extended agent card is not configured."));
        }
        return CompletableFuture.completedFuture(extendedCard);
    }

    /**
     * Sends one finite message.
     *
     * @param principal authenticated principal
     * @param request request
     * @return message-or-task result
     */
    public CompletionStage<SendMessageResult> sendMessageAsync(A2APrincipal principal, SendMessageRequest request) {
        return submit(() -> {
            PreparedTask prepared = prepare(principal, request);
            if (prepared.duplicate()) {
                return projectForSend(
                        prepared.task(), prepared.request().configuration().historyLength());
            }
            initialize(prepared);
            CompletionStage<Void> execution = execute(prepared, false);
            if (request.configuration().returnImmediately()) {
                observeExecutionFailure(execution);
                return projectForSend(
                        prepared.task(), prepared.request().configuration().historyLength());
            }
            join(execution);
            return projectForSend(
                    requireTask(principal, prepared.task().id()),
                    prepared.request().configuration().historyLength());
        });
    }

    /**
     * Sends one streaming message.
     *
     * @param principal authenticated principal
     * @param request request
     * @param cancellation caller-owned cancellation
     * @return task stream
     */
    public Flow.Publisher<A2AStreamEvent> sendMessageStreaming(
            A2APrincipal principal, SendMessageRequest request, RunCancellation cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        if (!publicCard.capabilities().streaming()) {
            return failedPublisher(new A2AProtocolException(
                    A2AErrorCode.UNSUPPORTED_OPERATION, "Streaming message operation is not supported."));
        }
        PreparedTask prepared = runControl(() -> prepare(principal, request));
        if (!prepared.duplicate()) {
            runControl(() -> {
                initialize(prepared);
                return null;
            });
        } else {
            eventBroker.register(
                    principal,
                    projectForSend(
                            prepared.task(), prepared.request().configuration().historyLength()));
        }
        Flow.Publisher<A2AStreamEvent> stream =
                eventBroker.subscribe(principal, prepared.task().id());
        if (prepared.duplicate()) {
            return stream;
        }
        return startOnSubscribe(stream, () -> observeExecutionFailure(execute(prepared, true)));
    }

    /** Gets one principal-visible task. */
    public CompletionStage<Task> getTaskAsync(A2APrincipal principal, A2ARequests.GetTask request) {
        return submit(() -> {
            Task task = requireTask(principal, request.taskId());
            if (request.historyLength() == null) {
                return task;
            }
            int from = Math.max(0, task.history().size() - request.historyLength());
            return new Task(
                    task.id(),
                    task.contextId(),
                    task.status(),
                    task.artifacts(),
                    task.history().subList(from, task.history().size()),
                    task.metadata());
        });
    }

    /** Lists one principal-visible task page. */
    public CompletionStage<A2ACursorPage<Task>> listTasksAsync(A2APrincipal principal, A2ARequests.ListTasks request) {
        ensureOpen();
        return taskStore.listAsync(
                Objects.requireNonNull(principal, "principal"), Objects.requireNonNull(request, "request"));
    }

    /** Cancels one principal-visible task. */
    public CompletionStage<Task> cancelTaskAsync(A2APrincipal principal, A2ARequests.CancelTask request) {
        return submit(() -> {
            Task task = requireTask(principal, request.taskId());
            if (task.status().state().isTerminal()) {
                throw new A2AProtocolException(
                        A2AErrorCode.TASK_NOT_CANCELABLE, "Task is already terminal and cannot be canceled.");
            }
            ExecutionKey key = key(principal, task.id());
            ActiveExecution execution = active.get(key);
            if (execution != null) {
                execution.cancellation().cancel();
                join(executor.cancelAsync(execution.context()));
            }
            TaskSink sink = new TaskSink(principal, task);
            try {
                return join(sink.updateStatusAsync(TaskState.TASK_STATE_CANCELED, null));
            } catch (A2AException race) {
                Task current = requireTask(principal, task.id());
                if (current.status().state().isTerminal()) {
                    return current;
                }
                throw race;
            }
        });
    }

    /**
     * Resubscribes to one task.
     *
     * <p>The current Task is always the first event; older events are not replayed and Last-Event-ID
     * is not advertised.
     */
    public Flow.Publisher<A2AStreamEvent> subscribeToTask(A2APrincipal principal, A2ARequests.SubscribeToTask request) {
        if (!publicCard.capabilities().streaming()) {
            return failedPublisher(new A2AProtocolException(
                    A2AErrorCode.UNSUPPORTED_OPERATION, "Task subscription is not supported."));
        }
        Task task = runControl(() -> requireTask(principal, request.taskId()));
        if (task.status().state().isTerminal()) {
            return failedPublisher(new A2AProtocolException(
                    A2AErrorCode.UNSUPPORTED_OPERATION, "Terminal tasks cannot be subscribed."));
        }
        eventBroker.register(principal, task);
        return eventBroker.subscribe(principal, task.id());
    }

    /** Creates or replaces one push configuration. */
    public CompletionStage<PushNotificationConfig> createPushNotificationConfigAsync(
            A2APrincipal principal, PushNotificationConfig config) {
        return submit(() -> {
            requirePushSupport();
            if (config.taskId() == null) {
                throw new A2AProtocolException(
                        A2AErrorCode.INVALID_PARAMS, "Stored push configuration requires taskId.");
            }
            requireTask(principal, config.taskId());
            return join(pushStore.putAsync(principal, config));
        });
    }

    /** Gets one push configuration. */
    public CompletionStage<PushNotificationConfig> getPushNotificationConfigAsync(
            A2APrincipal principal, A2ARequests.GetPushConfig request) {
        return submit(() -> {
            requirePushSupport();
            requireTask(principal, request.taskId());
            return join(pushStore.getAsync(principal, request))
                    .orElseThrow(() ->
                            new A2AProtocolException(A2AErrorCode.TASK_NOT_FOUND, "Push configuration was not found."));
        });
    }

    /** Lists push configurations for one visible task. */
    public CompletionStage<A2ACursorPage<PushNotificationConfig>> listPushNotificationConfigsAsync(
            A2APrincipal principal, A2ARequests.ListPushConfigs request) {
        return submit(() -> {
            requirePushSupport();
            requireTask(principal, request.taskId());
            return join(pushStore.listAsync(principal, request));
        });
    }

    /** Deletes one push configuration idempotently. */
    public CompletionStage<Boolean> deletePushNotificationConfigAsync(
            A2APrincipal principal, A2ARequests.DeletePushConfig request) {
        return submit(() -> {
            requirePushSupport();
            requireTask(principal, request.taskId());
            return join(pushStore.deleteAsync(principal, request));
        });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        active.values().forEach(execution -> execution.cancellation().cancel());
        active.clear();
        controlExecutor.close();
    }

    private PreparedTask prepare(A2APrincipal principal, SendMessageRequest request) {
        ensureOpen();
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(request, "request");
        request = negotiateModesAndValidateExtensions(request);
        MessageKey messageKey = new MessageKey(
                principal.principalId(),
                principal.isolationKey(),
                request.message().messageId());
        CompletableFuture<PreparedTask> owned = new CompletableFuture<>();
        CompletableFuture<PreparedTask> inFlight = preparations.putIfAbsent(messageKey, owned);
        if (inFlight != null) {
            PreparedTask original = join(inFlight);
            return duplicate(principal, request, original.task().id(), original.cancellation());
        }
        try {
            PreparedTask prepared = prepareOwned(principal, request, messageKey);
            owned.complete(prepared);
            return prepared;
        } catch (RuntimeException | Error failure) {
            owned.completeExceptionally(failure);
            throw failure;
        } finally {
            preparations.remove(messageKey, owned);
        }
    }

    private PreparedTask prepareOwned(A2APrincipal principal, SendMessageRequest request, MessageKey messageKey) {
        String existingId;
        synchronized (idempotency) {
            existingId = idempotency.get(messageKey);
        }
        if (existingId != null) {
            return duplicate(principal, request, existingId, new DefaultRunCancellation());
        }

        reserveIdempotency(messageKey);
        boolean reserved = true;
        try {
            PreparedTask prepared = request.message().taskId() == null
                    ? prepareNew(principal, request)
                    : prepareContinuation(principal, request);
            String previous;
            synchronized (idempotency) {
                if (!idempotencyReservations.remove(messageKey)) {
                    throw new IllegalStateException("A2A idempotency reservation was lost.");
                }
                reserved = false;
                previous = idempotency.putIfAbsent(messageKey, prepared.task().id());
            }
            if (previous != null) {
                return duplicate(principal, request, previous, prepared.cancellation());
            }
            return prepared;
        } finally {
            if (reserved) {
                synchronized (idempotency) {
                    idempotencyReservations.remove(messageKey);
                }
            }
        }
    }

    private void initialize(PreparedTask prepared) {
        try {
            eventBroker.register(
                    prepared.principal(),
                    projectForSend(
                            prepared.task(), prepared.request().configuration().historyLength()));
            storeInlinePush(prepared.principal(), prepared.request(), prepared.task());
        } catch (RuntimeException failure) {
            synchronized (idempotency) {
                idempotency.remove(
                        new MessageKey(
                                prepared.principal().principalId(),
                                prepared.principal().isolationKey(),
                                prepared.request().message().messageId()),
                        prepared.task().id());
            }
            eventBroker.remove(prepared.principal(), prepared.task().id());
            if (!prepared.continuation()) {
                join(taskStore.deleteAsync(prepared.principal(), prepared.task().id()));
            }
            throw failure;
        }
    }

    private void reserveIdempotency(MessageKey messageKey) {
        while (true) {
            synchronized (idempotency) {
                if (idempotency.size() + idempotencyReservations.size() < maxIdempotencyEntries) {
                    idempotencyReservations.add(messageKey);
                    return;
                }
            }
            if (expireTerminalIdempotency() == 0) {
                throw new A2AException("A2A idempotency capacity is exhausted.");
            }
        }
    }

    private int expireTerminalIdempotency() {
        List<Map.Entry<MessageKey, String>> entries;
        synchronized (idempotency) {
            entries = idempotency.entrySet().stream()
                    .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                    .toList();
        }
        int removed = 0;
        for (Map.Entry<MessageKey, String> entry : entries) {
            MessageKey key = entry.getKey();
            Optional<Task> task =
                    join(taskStore.getAsync(new A2APrincipal(key.principalId(), key.isolationKey()), entry.getValue()));
            if (task.isEmpty() || task.orElseThrow().status().state().isTerminal()) {
                synchronized (idempotency) {
                    if (idempotency.remove(key, entry.getValue())) {
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    private PreparedTask duplicate(
            A2APrincipal principal, SendMessageRequest request, String taskId, RunCancellation cancellation) {
        return new PreparedTask(principal, request, requireTask(principal, taskId), true, false, cancellation);
    }

    private PreparedTask prepareNew(A2APrincipal principal, SendMessageRequest request) {
        String contextId = request.message().contextId();
        if (!request.message().referenceTaskIds().isEmpty()) {
            Task firstReference = null;
            for (String referenceId : request.message().referenceTaskIds()) {
                Task referenced = requireTask(principal, referenceId);
                if (firstReference == null) {
                    firstReference = referenced;
                } else if (!firstReference.contextId().equals(referenced.contextId())) {
                    throw new A2AProtocolException(
                            A2AErrorCode.INVALID_PARAMS, "Referenced tasks must share one context.");
                }
            }
            if (contextId == null) {
                contextId = Objects.requireNonNull(firstReference).contextId();
            } else if (!contextId.equals(Objects.requireNonNull(firstReference).contextId())) {
                throw new A2AProtocolException(
                        A2AErrorCode.INVALID_PARAMS, "Message contextId does not match referenced tasks.");
            }
        }
        if (contextId == null) {
            contextId = UUID.randomUUID().toString();
        }
        String taskId = UUID.randomUUID().toString();
        Message correlated = correlate(request.message(), taskId, contextId);
        Task task = new Task(
                taskId,
                contextId,
                new TaskStatus(TaskState.TASK_STATE_SUBMITTED, clock.instant()),
                List.of(),
                List.of(correlated),
                request.metadata());
        join(taskStore.createAsync(principal, task));
        return new PreparedTask(
                principal,
                new SendMessageRequest(correlated, request.configuration(), request.metadata(), request.tenant()),
                task,
                false,
                false,
                new DefaultRunCancellation());
    }

    private PreparedTask prepareContinuation(A2APrincipal principal, SendMessageRequest request) {
        Task current = requireTask(principal, request.message().taskId());
        if (request.message().contextId() != null
                && !current.contextId().equals(request.message().contextId())) {
            throw new A2AProtocolException(
                    A2AErrorCode.INVALID_PARAMS, "Message taskId and contextId do not correlate.");
        }
        if (current.status().state().isTerminal()) {
            throw new A2AProtocolException(
                    A2AErrorCode.UNSUPPORTED_OPERATION, "Terminal tasks cannot accept continuation messages.");
        }
        if (!current.status().state().isInterrupted()) {
            throw new A2AProtocolException(
                    A2AErrorCode.INVALID_PARAMS,
                    "Only input-required or auth-required tasks accept continuation messages.");
        }
        Message correlated = correlate(request.message(), current.id(), current.contextId());
        ArrayList<Message> history = new ArrayList<>(current.history());
        history.add(correlated);
        Task resumed = new Task(
                current.id(),
                current.contextId(),
                new TaskStatus(TaskState.TASK_STATE_SUBMITTED, clock.instant()),
                current.artifacts(),
                history,
                merge(current.metadata(), request.metadata()));
        join(taskStore.updateAsync(principal, resumed, current.status().state()));
        return new PreparedTask(
                principal,
                new SendMessageRequest(correlated, request.configuration(), request.metadata(), request.tenant()),
                resumed,
                false,
                true,
                new DefaultRunCancellation());
    }

    private CompletionStage<Void> execute(PreparedTask prepared, boolean streaming) {
        TaskSink sink = new TaskSink(prepared.principal(), prepared.task());
        A2AExecutionContext context = new A2AExecutionContext(
                prepared.principal(), prepared.request(), prepared.task(), streaming, prepared.continuation());
        ExecutionKey key = key(prepared.principal(), prepared.task().id());
        ActiveExecution execution = new ActiveExecution(prepared.cancellation(), context);
        ActiveExecution previous = active.putIfAbsent(key, execution);
        if (previous != null) {
            return CompletableFuture.failedFuture(new A2AException("Task already has active execution."));
        }
        CompletionStage<Void> stage;
        try {
            stage = executor.executeAsync(context, sink, prepared.cancellation());
        } catch (RuntimeException failure) {
            stage = CompletableFuture.failedFuture(failure);
        }
        return stage.handle((ignored, failure) -> {
                    active.remove(key, execution);
                    if (failure == null) {
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    Throwable cause = unwrap(failure);
                    TaskState state = cause instanceof RunCancelledException
                            ? TaskState.TASK_STATE_CANCELED
                            : TaskState.TASK_STATE_FAILED;
                    Message error = statusMessage(
                            state == TaskState.TASK_STATE_CANCELED ? "Task was canceled." : "Agent execution failed.",
                            state == TaskState.TASK_STATE_CANCELED ? "canceled" : "execution_failed");
                    try {
                        join(sink.updateStatusAsync(state, error));
                    } catch (A2AException ignoredRace) {
                        // A concurrent terminal transition already won.
                    }
                    return CompletableFuture.<Void>completedFuture(null);
                })
                .thenCompose(Function.identity());
    }

    private void storeInlinePush(A2APrincipal principal, SendMessageRequest request, Task task) {
        PushNotificationConfig config = request.configuration().taskPushNotificationConfig();
        if (config == null) {
            return;
        }
        requirePushSupport();
        join(pushStore.putAsync(principal, config.forTask(task.id())));
    }

    private void requirePushSupport() {
        if (pushStore == null) {
            throw new A2AProtocolException(
                    A2AErrorCode.PUSH_NOTIFICATION_NOT_SUPPORTED, "Push notification configuration is not supported.");
        }
    }

    private SendMessageRequest negotiateModesAndValidateExtensions(SendMessageRequest request) {
        for (com.microsoft.agents.protocols.a2a.Part part : request.message().parts()) {
            if (!supports(publicCard.defaultInputModes(), part.mediaType())) {
                throw new A2AProtocolException(
                        A2AErrorCode.CONTENT_TYPE_NOT_SUPPORTED,
                        "Input media type '" + part.mediaType() + "' is not supported.");
            }
        }
        List<String> requested = request.configuration().acceptedOutputModes();
        LinkedHashSet<String> negotiated = new LinkedHashSet<>();
        if (requested.isEmpty()) {
            negotiated.addAll(publicCard.defaultOutputModes());
        } else {
            for (String clientMode : requested) {
                for (String serverMode : publicCard.defaultOutputModes()) {
                    String intersection = intersection(clientMode, serverMode);
                    if (intersection != null) {
                        negotiated.add(intersection);
                    }
                }
            }
            if (negotiated.isEmpty()) {
                throw new A2AProtocolException(
                        A2AErrorCode.CONTENT_TYPE_NOT_SUPPORTED,
                        "Requested output modes do not intersect the agent's output modes.");
            }
        }
        Set<URI> declared = publicCard.capabilities().extensions().stream()
                .map(com.microsoft.agents.protocols.a2a.AgentExtension::uri)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!declared.containsAll(request.message().extensions())) {
            throw new A2AProtocolException(
                    A2AErrorCode.EXTENSION_SUPPORT_REQUIRED,
                    "Message requires an extension not declared by this agent.");
        }
        Set<URI> used = Set.copyOf(request.message().extensions());
        boolean missingRequired = publicCard.capabilities().extensions().stream()
                .anyMatch(extension -> extension.required() && !used.contains(extension.uri()));
        if (missingRequired) {
            throw new A2AProtocolException(
                    A2AErrorCode.EXTENSION_SUPPORT_REQUIRED,
                    "Message does not declare every required agent extension.");
        }
        return new SendMessageRequest(
                request.message(),
                new com.microsoft.agents.protocols.a2a.SendMessageConfiguration(
                        List.copyOf(negotiated),
                        request.configuration().historyLength(),
                        request.configuration().taskPushNotificationConfig(),
                        request.configuration().returnImmediately()),
                request.metadata(),
                request.tenant());
    }

    private static boolean supports(List<String> patterns, String mediaType) {
        return patterns.stream().anyMatch(pattern -> {
            if ("*/*".equals(pattern) || pattern.equalsIgnoreCase(mediaType)) {
                return true;
            }
            int slash = pattern.indexOf('/');
            return pattern.endsWith("/*") && slash > 0 && mediaType.regionMatches(true, 0, pattern, 0, slash + 1);
        });
    }

    private static String intersection(String clientMode, String serverMode) {
        if (clientMode.equalsIgnoreCase(serverMode)) {
            return clientMode;
        }
        if ("*/*".equals(clientMode)) {
            return serverMode;
        }
        if ("*/*".equals(serverMode)) {
            return clientMode;
        }
        int clientSlash = clientMode.indexOf('/');
        int serverSlash = serverMode.indexOf('/');
        if (clientSlash <= 0 || serverSlash <= 0) {
            return null;
        }
        String clientType = clientMode.substring(0, clientSlash);
        String serverType = serverMode.substring(0, serverSlash);
        if (!clientType.equalsIgnoreCase(serverType)) {
            return null;
        }
        if (clientMode.endsWith("/*")) {
            return serverMode;
        }
        if (serverMode.endsWith("/*")) {
            return clientMode;
        }
        return null;
    }

    private Task requireTask(A2APrincipal principal, String taskId) {
        return join(taskStore.getAsync(principal, taskId))
                .orElseThrow(() -> new A2AProtocolException(A2AErrorCode.TASK_NOT_FOUND, "Task was not found."));
    }

    private <T> CompletionStage<T> submit(ThrowingSupplier<T> supplier) {
        ensureOpen();
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return supplier.get();
                    } catch (RuntimeException failure) {
                        throw failure;
                    } catch (Exception failure) {
                        throw new A2AException("A2A service operation failed.", failure);
                    }
                },
                controlExecutor);
    }

    private <T> T runControl(ThrowingSupplier<T> supplier) {
        return join(submit(supplier));
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("A2A service is closed.");
        }
    }

    private static Message correlate(Message source, String taskId, String contextId) {
        return new Message(
                source.role(),
                source.parts(),
                source.messageId(),
                contextId,
                taskId,
                source.referenceTaskIds(),
                source.metadata(),
                source.extensions());
    }

    private static Map<String, StateValue> merge(Map<String, StateValue> first, Map<String, StateValue> second) {
        LinkedHashMap<String, StateValue> merged = new LinkedHashMap<>(first);
        merged.putAll(second);
        return Map.copyOf(merged);
    }

    private static Task projectForSend(Task task, Integer historyLength) {
        if (historyLength == null) {
            return task;
        }
        int from = Math.max(0, task.history().size() - historyLength);
        return new Task(
                task.id(),
                task.contextId(),
                task.status(),
                task.artifacts(),
                task.history().subList(from, task.history().size()),
                task.metadata());
    }

    private static Message statusMessage(String text, String code) {
        return Message.builder(com.microsoft.agents.protocols.a2a.Role.ROLE_AGENT)
                .parts(List.of(new TextPart(text)))
                .metadata(Map.of("code", StateValue.string(code)))
                .build();
    }

    private static void observeExecutionFailure(CompletionStage<Void> execution) {
        execution.exceptionally(ignored -> null);
    }

    private static ExecutionKey key(A2APrincipal principal, String taskId) {
        return new ExecutionKey(principal.principalId(), principal.isolationKey(), taskId);
    }

    private static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException failure) {
            Throwable cause = unwrap(failure);
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new A2AException("Asynchronous A2A operation failed.", cause);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        return (failure instanceof java.util.concurrent.CompletionException
                                || failure instanceof java.util.concurrent.ExecutionException)
                        && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }

    private static Flow.Publisher<A2AStreamEvent> startOnSubscribe(
            Flow.Publisher<A2AStreamEvent> source, Runnable starter) {
        return subscriber -> source.subscribe(new Flow.Subscriber<>() {
            private boolean started;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscriber.onSubscribe(subscription);
            }

            @Override
            public void onNext(A2AStreamEvent item) {
                subscriber.onNext(item);
                if (!started) {
                    started = true;
                    starter.run();
                }
            }

            @Override
            public void onError(Throwable failure) {
                subscriber.onError(failure);
            }

            @Override
            public void onComplete() {
                subscriber.onComplete();
            }
        });
    }

    private static Flow.Publisher<A2AStreamEvent> failedPublisher(Throwable failure) {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {}

                @Override
                public void cancel() {}
            });
            subscriber.onError(failure);
        };
    }

    /** Builds immutable {@link A2AService}. */
    public static final class Builder {
        private final AgentCard publicCard;

        private final A2AExecutor executor;

        private AgentCard extendedCard;

        private A2ATaskStore taskStore = new InMemoryA2ATaskStore();

        private A2APushNotificationConfigStore pushStore;

        private int maxEventChannels = 10_000;

        private int maxBufferedEvents = 256;

        private int maxIdempotencyEntries = 10_000;

        private Clock clock = Clock.systemUTC();

        private Builder(AgentCard publicCard, A2AExecutor executor) {
            this.publicCard = publicCard;
            this.executor = executor;
        }

        /** Sets the authenticated extended card. */
        public Builder extendedCard(AgentCard value) {
            extendedCard = value;
            return this;
        }

        /** Sets principal-isolated task storage. */
        public Builder taskStore(A2ATaskStore value) {
            taskStore = value;
            return this;
        }

        /**
         * Enables push-configuration storage.
         *
         * <p>No outbound dispatcher is installed by this option.
         */
        public Builder pushStore(A2APushNotificationConfigStore value) {
            pushStore = value;
            return this;
        }

        /** Sets the maximum retained task event channels. */
        public Builder maxEventChannels(int value) {
            maxEventChannels = value;
            return this;
        }

        /** Sets each subscriber's event buffer. */
        public Builder maxBufferedEvents(int value) {
            maxBufferedEvents = value;
            return this;
        }

        /** Sets the idempotent message index bound. */
        public Builder maxIdempotencyEntries(int value) {
            maxIdempotencyEntries = value;
            return this;
        }

        /** Sets the lifecycle clock. */
        public Builder clock(Clock value) {
            clock = value;
            return this;
        }

        /** Creates the service. */
        public A2AService build() {
            return new A2AService(this);
        }
    }

    private final class TaskSink implements A2AEventSink {
        private final A2APrincipal principal;

        private final AtomicReference<Task> current;

        private TaskSink(A2APrincipal principal, Task current) {
            this.principal = principal;
            this.current = new AtomicReference<>(current);
        }

        @Override
        public Task current() {
            return current.get();
        }

        @Override
        public synchronized CompletionStage<Task> updateStatusAsync(TaskState state, Message message) {
            Task before = current.get();
            requireTransition(before.status().state(), state);
            Message correlated = message == null ? null : correlate(message, before.id(), before.contextId());
            Task after = new Task(
                    before.id(),
                    before.contextId(),
                    new TaskStatus(state, correlated, clock.instant()),
                    before.artifacts(),
                    before.history(),
                    before.metadata());
            Task stored =
                    join(taskStore.updateAsync(principal, after, before.status().state()));
            current.set(stored);
            eventBroker.publish(
                    principal,
                    stored,
                    new TaskStatusUpdateEvent(stored.id(), stored.contextId(), stored.status(), Map.of()));
            return CompletableFuture.completedFuture(stored);
        }

        @Override
        public synchronized CompletionStage<Task> addArtifactAsync(
                Artifact artifact, boolean append, boolean lastChunk, Map<String, StateValue> metadata) {
            Task before = current.get();
            if (before.status().state().isTerminal()) {
                return CompletableFuture.failedFuture(new A2AException("Cannot add an artifact to a terminal task."));
            }
            ArrayList<Artifact> artifacts = new ArrayList<>(before.artifacts());
            int index = -1;
            for (int currentIndex = 0; currentIndex < artifacts.size(); currentIndex++) {
                if (artifacts.get(currentIndex).artifactId().equals(artifact.artifactId())) {
                    index = currentIndex;
                    break;
                }
            }
            if (append && index < 0) {
                return CompletableFuture.failedFuture(
                        new A2AException("Artifact append requires an existing artifact."));
            }
            if (!append && index >= 0) {
                return CompletableFuture.failedFuture(new A2AException("Initial artifact identifier already exists."));
            }
            if (append) {
                Artifact previous = artifacts.get(index);
                ArrayList<com.microsoft.agents.protocols.a2a.Part> parts = new ArrayList<>(previous.parts());
                parts.addAll(artifact.parts());
                artifacts.set(
                        index,
                        new Artifact(
                                previous.artifactId(),
                                previous.name(),
                                previous.description(),
                                parts,
                                merge(previous.metadata(), artifact.metadata()),
                                previous.extensions()));
            } else {
                artifacts.add(artifact);
            }
            Task after = new Task(
                    before.id(), before.contextId(), before.status(), artifacts, before.history(), before.metadata());
            Task stored =
                    join(taskStore.updateAsync(principal, after, before.status().state()));
            current.set(stored);
            eventBroker.publish(
                    principal,
                    stored,
                    new TaskArtifactUpdateEvent(
                            stored.id(), stored.contextId(), artifact, append, lastChunk, metadata));
            return CompletableFuture.completedFuture(stored);
        }
    }

    private static void requireTransition(TaskState previous, TaskState next) {
        boolean valid = previous == next
                || switch (previous) {
                    case TASK_STATE_SUBMITTED ->
                        next == TaskState.TASK_STATE_WORKING
                                || next == TaskState.TASK_STATE_CANCELED
                                || next == TaskState.TASK_STATE_FAILED
                                || next == TaskState.TASK_STATE_REJECTED;
                    case TASK_STATE_WORKING -> next.isTerminal() || next.isInterrupted();
                    case TASK_STATE_INPUT_REQUIRED, TASK_STATE_AUTH_REQUIRED ->
                        next == TaskState.TASK_STATE_SUBMITTED || next == TaskState.TASK_STATE_CANCELED;
                    case TASK_STATE_UNSPECIFIED -> next != TaskState.TASK_STATE_UNSPECIFIED;
                    case TASK_STATE_COMPLETED, TASK_STATE_FAILED, TASK_STATE_CANCELED, TASK_STATE_REJECTED -> false;
                };
        if (!valid) {
            throw new A2AException("Invalid task lifecycle transition " + previous + " -> " + next + ".");
        }
    }

    private record ExecutionKey(String principalId, String isolationKey, String taskId) {}

    private record MessageKey(String principalId, String isolationKey, String messageId) {}

    private record ActiveExecution(RunCancellation cancellation, A2AExecutionContext context) {}

    private record PreparedTask(
            A2APrincipal principal,
            SendMessageRequest request,
            Task task,
            boolean duplicate,
            boolean continuation,
            RunCancellation cancellation) {
        private PreparedTask withCancellation(RunCancellation value) {
            return new PreparedTask(principal, request, task, duplicate, continuation, value);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
