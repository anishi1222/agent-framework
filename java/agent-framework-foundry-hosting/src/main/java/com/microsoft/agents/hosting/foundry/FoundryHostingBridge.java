// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.foundry;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StorageConflictException;
import com.microsoft.agents.hosting.HostingRegistry;
import com.microsoft.agents.hosting.HostingRequestContext;
import com.microsoft.agents.providers.azureaipersistent.AzureAIPersistentAgent;
import com.microsoft.agents.providers.azureaipersistent.PersistentContinuationKind;
import com.microsoft.agents.providers.azureaipersistent.PersistentRunContinuation;
import com.microsoft.agents.providers.azureaipersistent.PersistentToolOutput;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Registers Foundry agents in the generic hosting runtime and owns only explicit process-local
 * session and continuation references.
 *
 * <p>Authentication and route authorization remain the generic host's responsibility. Principal
 * and isolation identifiers propagated by {@code HostingDispatcher} form the storage partition;
 * caller-provided thread, run, and conversation identifiers never establish authorization.
 */
public final class FoundryHostingBridge implements AutoCloseable {
    /** Request metadata key selecting an authorized conversation partition. */
    public static final String CONVERSATION_ID_METADATA = "foundry.conversationId";
    /** Response metadata key carrying an opaque one-time process-local resume handle. */
    public static final String RESUME_HANDLE_METADATA = "foundry.resumeHandle";

    private final HostingRegistry registry;
    private final FoundryHostedSessionStore sessions;
    private final AutoCloseable ownedSessions;
    private final FoundryHostingOptions options;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Object continuationLock = new Object();
    private final ConcurrentHashMap<String, ContinuationBinding> continuations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HostedPersistentAgent> persistentRoutes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<FoundryHostedSessionKey, CompletableFuture<Void>> persistentOperations =
            new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a bridge with a bounded process-local session store.
     *
     * @param registry generic hosting registry
     * @param options bounded hosting options
     */
    public FoundryHostingBridge(HostingRegistry registry, FoundryHostingOptions options) {
        this(
                registry,
                new InMemoryFoundryHostedSessionStore(
                        options.maximumSessions(), options.sessionTimeToLive(), options.maximumSubmittedMessageIds()),
                options,
                true,
                Clock.systemUTC());
    }

    /**
     * Creates a bridge with a caller-owned session store.
     *
     * @param registry generic hosting registry
     * @param sessions caller-owned store
     * @param options bounded hosting options
     */
    public FoundryHostingBridge(
            HostingRegistry registry, FoundryHostedSessionStore sessions, FoundryHostingOptions options) {
        this(registry, sessions, options, false, Clock.systemUTC());
    }

    FoundryHostingBridge(
            HostingRegistry registry,
            FoundryHostedSessionStore sessions,
            FoundryHostingOptions options,
            boolean ownSessions,
            Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.options = Objects.requireNonNull(options, "options");
        this.clock = Objects.requireNonNull(clock, "clock");
        ownedSessions = ownSessions && sessions instanceof AutoCloseable closeable ? closeable : null;
    }

    /**
     * Registers an existing Foundry Responses agent without adding another execution engine.
     *
     * @param routeId route identifier
     * @param agent existing agent
     */
    public void registerResponsesAgent(String routeId, Agent<?> agent) {
        requireOpen();
        registry.registerAgent(
                routeId,
                Objects.requireNonNull(agent, "agent"),
                true,
                false,
                Map.of(
                        "provider", StateValue.string("foundry"),
                        "surface", StateValue.string("responses")));
    }

    /**
     * Registers an Azure AI Persistent agent with principal-isolated hosted session references.
     *
     * @param routeId route identifier
     * @param agent persistent agent
     */
    public void registerPersistentAgent(String routeId, AzureAIPersistentAgent agent) {
        requireOpen();
        HostedPersistentAgent hosted = new HostedPersistentAgent(routeId, Objects.requireNonNull(agent, "agent"));
        if (persistentRoutes.putIfAbsent(routeId, hosted) != null) {
            throw new IllegalStateException("Persistent Foundry route is already registered.");
        }
        try {
            registry.registerAgent(
                    routeId,
                    hosted,
                    false,
                    true,
                    Map.of(
                            "provider", StateValue.string("foundry"),
                            "surface", StateValue.string("persistent"),
                            "continuations", StateValue.string("process-local")));
        } catch (RuntimeException failure) {
            persistentRoutes.remove(routeId, hosted);
            throw failure;
        }
    }

    /**
     * Consumes an opaque one-time persistent requires-action handle.
     *
     * @param context authenticated hosting context
     * @param routeId route identifier
     * @param resumeHandle opaque process-local handle
     * @param outputs caller-reviewed tool outputs
     * @param approved approval decision
     * @return mapped response stage
     */
    public CompletionStage<AgentResponse<Void>> continuePersistentAsync(
            HostingRequestContext context,
            String routeId,
            String resumeHandle,
            List<PersistentToolOutput> outputs,
            boolean approved) {
        try {
            return continuePersistentCore(context, routeId, resumeHandle, outputs, approved);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletionStage<AgentResponse<Void>> continuePersistentCore(
            HostingRequestContext context,
            String routeId,
            String resumeHandle,
            List<PersistentToolOutput> outputs,
            boolean approved) {
        requireOpen();
        purgeExpiredContinuations();
        String validatedHandle = nonBlank(resumeHandle, "resumeHandle");
        ContinuationBinding binding = continuations.get(validatedHandle);
        if (binding == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Foundry continuation is missing, expired, or already consumed."));
        }
        if (!binding.routeId().equals(routeId)
                || !binding.key().principalId().equals(context.principalId())
                || !binding.key().isolationId().equals(context.isolationId())) {
            return CompletableFuture.failedFuture(new SecurityException("Foundry continuation authorization failed."));
        }
        if (!continuations.remove(validatedHandle, binding)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Foundry continuation is missing, expired, or already consumed."));
        }
        HostedPersistentAgent hosted = persistentRoutes.get(routeId);
        if (hosted == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Persistent Foundry route is unavailable."));
        }
        PersistentRunContinuation continuation = new PersistentRunContinuation(
                binding.threadId(), binding.runId(), PersistentContinuationKind.APPROVAL, outputs, approved, null);
        return hosted.delegate
                .continueRunAsync(continuation, context.cancellation())
                .thenCompose(response ->
                        hosted.decorateAndSave(binding.key(), binding.revision(), response, context.cancellation()));
    }

    /**
     * Deletes one authorized session reference and optionally its remote thread.
     *
     * @param context authenticated hosting context
     * @param routeId route identifier
     * @param conversationId authorized conversation identifier
     * @param deleteRemoteThread whether to explicitly delete the service thread
     * @return whether a session reference existed
     */
    public CompletionStage<Boolean> deletePersistentSessionAsync(
            HostingRequestContext context, String routeId, String conversationId, boolean deleteRemoteThread) {
        requireOpen();
        FoundryHostedSessionKey key =
                new FoundryHostedSessionKey(routeId, context.principalId(), context.isolationId(), conversationId);
        return sessions.loadAsync(key).thenCompose(existing -> {
            HostedPersistentAgent hosted = persistentRoutes.get(routeId);
            if (deleteRemoteThread && existing.isPresent() && hosted == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Persistent Foundry route is unavailable."));
            }
            CompletionStage<Void> remote = !deleteRemoteThread || existing.isEmpty()
                    ? CompletableFuture.completedStage(null)
                    : hosted.delegate.deleteServiceThreadAsync(
                            existing.orElseThrow().threadId(), context.cancellation());
            return remote.thenCompose(ignored -> sessions.deleteAsync(key));
        });
    }

    /** Returns the current process-local continuation count. */
    public int continuationCount() {
        synchronized (continuationLock) {
            purgeExpiredContinuationsLocked();
            return continuations.size();
        }
    }

    /** Clears process-local continuations and only a store created by this bridge. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (continuationLock) {
            continuations.clear();
        }
        persistentRoutes.clear();
        persistentOperations.clear();
        if (ownedSessions != null) {
            try {
                ownedSessions.close();
            } catch (Exception failure) {
                throw new IllegalStateException("Foundry session store close failed.", failure);
            }
        }
    }

    private String issueContinuation(
            FoundryHostedSessionKey key, long revision, String routeId, String threadId, String runId) {
        synchronized (continuationLock) {
            requireOpen();
            purgeExpiredContinuationsLocked();
            if (continuations.size() >= options.maximumContinuations()) {
                throw new IllegalStateException("Foundry process-local continuation capacity is exhausted.");
            }
            byte[] bytes = new byte[32];
            String handle;
            do {
                random.nextBytes(bytes);
                handle = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            } while (continuations.containsKey(handle));
            continuations.put(
                    handle,
                    new ContinuationBinding(
                            routeId,
                            key,
                            threadId,
                            runId,
                            revision,
                            clock.instant().plus(options.continuationTimeToLive())));
            return handle;
        }
    }

    private void purgeExpiredContinuations() {
        synchronized (continuationLock) {
            purgeExpiredContinuationsLocked();
        }
    }

    private void purgeExpiredContinuationsLocked() {
        Instant now = clock.instant();
        continuations.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("FoundryHostingBridge is closed.");
        }
    }

    private final class HostedPersistentAgent implements Agent<Void> {
        private final String routeId;
        private final AzureAIPersistentAgent delegate;

        private HostedPersistentAgent(String routeId, AzureAIPersistentAgent delegate) {
            this.routeId = nonBlank(routeId, "routeId");
            this.delegate = delegate;
        }

        @Override
        public AgentMetadata metadata() {
            return delegate.metadata();
        }

        @Override
        public RunHandle<AgentResponse<Void>> startRun(
                List<Message> messages, RunOptions runOptions, RunCancellation cancellation) {
            RunHandleSource<AgentResponse<Void>> source = new RunHandleSource<>(cancellation);
            if (closed.get()) {
                source.tryFail(new IllegalStateException("FoundryHostingBridge is closed."));
                return source.handle();
            }
            FoundryHostedSessionKey key;
            try {
                key = sessionKey(routeId, runOptions);
            } catch (RuntimeException failure) {
                source.tryFail(failure);
                return source.handle();
            }
            enqueuePersistentRun(key, messages, runOptions, source);
            return source.handle();
        }

        private void enqueuePersistentRun(
                FoundryHostedSessionKey key,
                List<Message> messages,
                RunOptions runOptions,
                RunHandleSource<AgentResponse<Void>> source) {
            AtomicReference<CompletableFuture<Void>> scheduled = new AtomicReference<>();
            persistentOperations.compute(key, (ignored, previous) -> {
                CompletableFuture<Void> ready = previous == null
                        ? CompletableFuture.completedFuture(null)
                        : previous.handle((result, failure) -> (Void) null);
                CompletableFuture<Void> next = ready.thenCompose(ignoredResult -> {
                    if (source.isTerminal()) {
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    return sessions.loadAsync(key)
                            .thenCompose(existing -> openSession(key, existing, source.cancellation()))
                            .thenCompose(session -> reserveMessages(key, session, messages, source.cancellation(), 0))
                            .thenCompose(reservation -> delegate.runOnThreadAsync(
                                            reservation.session().threadId(),
                                            messages,
                                            reservation.previouslySubmitted(),
                                            runOptions,
                                            source.cancellation())
                                    .thenCompose(
                                            response -> decorateAndSave(key, null, response, source.cancellation())))
                            .handle((response, failure) -> {
                                if (failure != null) {
                                    source.tryFail(unwrap(failure));
                                } else {
                                    source.tryComplete(response);
                                }
                                return (Void) null;
                            })
                            .toCompletableFuture();
                });
                scheduled.set(next);
                return next;
            });
            CompletableFuture<Void> next = scheduled.get();
            next.whenComplete((ignored, failure) -> persistentOperations.remove(key, next));
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> messages, RunOptions runOptions, RunCancellation cancellation) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean done;

                @Override
                public void request(long count) {
                    if (!done) {
                        done = true;
                        subscriber.onError(new UnsupportedOperationException(
                                "Foundry hosted persistent streaming requires a durable streaming "
                                        + "session contract and is not exposed by this bridge."));
                    }
                }

                @Override
                public void cancel() {
                    done = true;
                    cancellation.cancel();
                }
            });
        }

        private CompletionStage<FoundryHostedSession> openSession(
                FoundryHostedSessionKey key, Optional<FoundryHostedSession> existing, RunCancellation cancellation) {
            if (existing.isPresent()) {
                return CompletableFuture.completedStage(existing.orElseThrow());
            }
            return delegate.createServiceThreadAsync(
                            Map.of(
                                    "af_route", key.routeId(),
                                    "af_isolation", key.isolationId()),
                            cancellation)
                    .thenCompose(thread -> {
                        FoundryHostedSession created = FoundryHostedSession.create(key, thread.id(), clock.instant());
                        return sessions.saveAsync(created, FoundryHostedSession.CREATE_ONLY)
                                .exceptionallyCompose(failure -> {
                                    Throwable cause = unwrap(failure);
                                    if (cause instanceof StorageConflictException) {
                                        deleteThreadBestEffort(thread.id());
                                        return sessions.loadAsync(key)
                                                .thenCompose(concurrent -> concurrent
                                                        .<CompletionStage<FoundryHostedSession>>map(
                                                                CompletableFuture::completedStage)
                                                        .orElseGet(() -> CompletableFuture.failedFuture(cause)));
                                    }
                                    return CompletableFuture.failedFuture(cause);
                                });
                    });
        }

        private void deleteThreadBestEffort(String threadId) {
            try {
                delegate.deleteServiceThreadAsync(threadId, new DefaultRunCancellation());
            } catch (RuntimeException ignored) {
                // A concurrent winner remains usable; cleanup of the losing thread is best effort.
            }
        }

        private CompletionStage<MessageReservation> reserveMessages(
                FoundryHostedSessionKey key,
                FoundryHostedSession session,
                List<Message> messages,
                RunCancellation cancellation,
                int attempt) {
            if (cancellation.isCancellationRequested()) {
                return CompletableFuture.failedFuture(new RunCancelledException());
            }
            LinkedHashSet<String> previouslySubmitted = new LinkedHashSet<>(session.submittedMessageIds());
            LinkedHashSet<String> desired = new LinkedHashSet<>(previouslySubmitted);
            messages.stream()
                    .filter(message -> Role.USER.equals(message.role()) || Role.ASSISTANT.equals(message.role()))
                    .map(Message::messageId)
                    .filter(Objects::nonNull)
                    .forEach(desired::add);
            if (desired.size() > options.maximumSubmittedMessageIds()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Foundry hosted submitted-message capacity is exhausted."));
            }
            if (desired.equals(previouslySubmitted)) {
                return CompletableFuture.completedStage(
                        new MessageReservation(session, Set.copyOf(previouslySubmitted)));
            }
            FoundryHostedSession replacement = new FoundryHostedSession(
                    session.key(),
                    session.threadId(),
                    session.runId(),
                    List.copyOf(desired),
                    session.revision(),
                    session.createdAt(),
                    clock.instant());
            return sessions.saveAsync(replacement, session.revision())
                    .thenApply(stored -> new MessageReservation(stored, Set.copyOf(previouslySubmitted)))
                    .exceptionallyCompose(failure -> {
                        Throwable cause = unwrap(failure);
                        if (cause instanceof StorageConflictException && attempt + 1 < options.maxStoreRetries()) {
                            return sessions.loadAsync(key)
                                    .thenCompose(current -> current.map(value ->
                                                    reserveMessages(key, value, messages, cancellation, attempt + 1))
                                            .orElseGet(() -> CompletableFuture.failedFuture(new IllegalStateException(
                                                    "Hosted session disappeared during message reservation."))));
                        }
                        return CompletableFuture.failedFuture(cause);
                    });
        }

        private CompletionStage<AgentResponse<Void>> decorateAndSave(
                FoundryHostedSessionKey key,
                Long requiredRevision,
                AgentResponse<Void> response,
                RunCancellation cancellation) {
            String runId = stateString(response.metadata().get("azureAiPersistent.runId"), "runId");
            String threadId = stateString(response.metadata().get("azureAiPersistent.threadId"), "threadId");
            return updateSession(key, requiredRevision, threadId, runId, cancellation, 0)
                    .thenApply(stored -> decorateContinuation(response, stored));
        }

        private CompletionStage<FoundryHostedSession> updateSession(
                FoundryHostedSessionKey key,
                Long requiredRevision,
                String threadId,
                String runId,
                RunCancellation cancellation,
                int attempt) {
            if (cancellation.isCancellationRequested()) {
                return CompletableFuture.failedFuture(new RunCancelledException());
            }
            return sessions.loadAsync(key).thenCompose(current -> {
                FoundryHostedSession loaded = current.orElseThrow(
                        () -> new IllegalStateException("Hosted session disappeared during execution."));
                if (requiredRevision != null && loaded.revision() != requiredRevision) {
                    return CompletableFuture.failedFuture(
                            new StorageConflictException("Foundry hosted session changed during execution."));
                }
                FoundryHostedSession replacement = new FoundryHostedSession(
                        key,
                        threadId,
                        runId,
                        loaded.submittedMessageIds(),
                        loaded.revision(),
                        loaded.createdAt(),
                        clock.instant());
                return sessions.saveAsync(replacement, loaded.revision()).exceptionallyCompose(failure -> {
                    Throwable cause = unwrap(failure);
                    if (cause instanceof StorageConflictException && attempt + 1 < options.maxStoreRetries()) {
                        return updateSession(key, requiredRevision, threadId, runId, cancellation, attempt + 1);
                    }
                    return CompletableFuture.failedFuture(cause);
                });
            });
        }

        private AgentResponse<Void> decorateContinuation(AgentResponse<Void> response, FoundryHostedSession stored) {
            if (response.continuationToken() == null) {
                return response;
            }
            String handle =
                    issueContinuation(stored.key(), stored.revision(), routeId, stored.threadId(), stored.runId());
            LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(response.metadata());
            metadata.put(RESUME_HANDLE_METADATA, StateValue.string(handle));
            metadata.put("foundry.resumeProcessLocal", StateValue.bool(true));
            metadata.put("foundry.resumeOneTime", StateValue.bool(true));
            return new AgentResponse<>(
                    response.messages(),
                    response.responseId(),
                    response.agentId(),
                    response.createdAt(),
                    response.finishReason(),
                    response.usage(),
                    response.value(),
                    response.continuationToken(),
                    metadata,
                    response.updateSequences());
        }
    }

    private static FoundryHostedSessionKey sessionKey(String routeId, RunOptions options) {
        String principal = stateString(options.metadata().get("hosting.principalId"), "hosting.principalId");
        String isolation = stateString(options.metadata().get("hosting.isolationId"), "hosting.isolationId");
        StateValue conversationValue = options.metadata().get(CONVERSATION_ID_METADATA);
        String conversation = conversationValue == null
                ? stateString(options.metadata().get("hosting.requestId"), "hosting.requestId")
                : stateString(conversationValue, CONVERSATION_ID_METADATA);
        return new FoundryHostedSessionKey(routeId, principal, isolation, conversation);
    }

    private static String stateString(StateValue value, String name) {
        if (!(value instanceof StateValue.StringValue string) || string.value().isBlank()) {
            throw new IllegalArgumentException(name + " must be trusted non-blank string metadata.");
        }
        return string.value();
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

    private record ContinuationBinding(
            String routeId,
            FoundryHostedSessionKey key,
            String threadId,
            String runId,
            long revision,
            Instant expiresAt) {}

    private record MessageReservation(FoundryHostedSession session, Set<String> previouslySubmitted) {}
}
