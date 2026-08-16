// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Applies Purview protection scopes, processContent, and contentActivities behavior to framework
 * messages.
 */
public final class PurviewPolicyEvaluator implements AutoCloseable {
    private final PurviewClient client;
    private final PurviewSettings settings;
    private final ConcurrentHashMap<ScopeKey, CachedScopes> cache = new ConcurrentHashMap<>();
    private final ExecutorService backgroundExecutor;
    private final ExecutorService ownedExecutor;
    private final Semaphore concurrentJobs;
    private final AtomicInteger pendingJobs = new AtomicInteger();
    private final Set<Future<?>> backgroundJobs = ConcurrentHashMap.newKeySet();
    private final Object backgroundLifecycleLock = new Object();
    private final Clock clock;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Creates an evaluator using a client and settings. */
    public PurviewPolicyEvaluator(PurviewClient client, PurviewSettings settings) {
        this(client, settings, Clock.systemUTC());
    }

    PurviewPolicyEvaluator(PurviewClient client, PurviewSettings settings, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (settings.backgroundExecutor() == null) {
            ownedExecutor = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("agent-framework-purview-job-", 0).factory());
            backgroundExecutor = ownedExecutor;
        } else {
            ownedExecutor = null;
            backgroundExecutor = settings.backgroundExecutor();
        }
        concurrentJobs = new Semaphore(settings.maximumConcurrentJobs());
    }

    /**
     * Evaluates ordered messages using one stable conversation identity.
     *
     * @param messages ordered messages
     * @param activity protected activity
     * @param sessionId stable conversation identifier
     * @param explicitUserId optional caller-authorized user identifier
     * @param cancellation cancellation signal
     * @return decision and resolved user
     */
    public CompletionStage<PurviewEvaluationOutcome> evaluateAsync(
            List<Message> messages,
            PurviewActivity activity,
            String sessionId,
            String explicitUserId,
            RunCancellation cancellation) {
        requireOpen();
        List<Message> safeMessages = List.copyOf(messages);
        if (safeMessages.isEmpty()) {
            return CompletableFuture.completedStage(new PurviewEvaluationOutcome(
                    PurviewDecision.allow(), requireGuid(explicitUserId, "explicitUserId")));
        }
        String correlation =
                sessionId == null || sessionId.isBlank() ? UUID.randomUUID().toString() : sessionId;
        return client.resolveIdentityAsync(cancellation).thenCompose(identity -> {
            String userId = firstNonBlank(identity.userId(), explicitUserId, userFromMessages(safeMessages));
            userId = requireGuid(userId, "userId");
            String tenantId = requireGuid(firstNonBlank(identity.tenantId(), settings.tenantId()), "tenantId");
            PurviewAppLocation location = settings.appLocation();
            if (location == null) {
                String clientId = requireGuid(identity.clientId(), "clientId");
                location = new PurviewAppLocation(PurviewLocationType.APPLICATION, clientId);
            }
            CompletionStage<PurviewDecision> decision = CompletableFuture.completedStage(PurviewDecision.allow());
            for (int index = 0; index < safeMessages.size(); index++) {
                Message message = safeMessages.get(index);
                PurviewContentRequest request = new PurviewContentRequest(
                        userId,
                        tenantId,
                        correlation + "@AF",
                        message.messageId() == null ? UUID.randomUUID().toString() : message.messageId(),
                        index,
                        activity,
                        message.text(),
                        clock.instant(),
                        location,
                        settings.appName(),
                        settings.appVersion());
                decision = decision.thenCompose(current -> current.blocked()
                        ? CompletableFuture.completedStage(current)
                        : evaluateOneAsync(request, cancellation));
            }
            String resolvedUser = userId;
            return decision.thenApply(result -> new PurviewEvaluationOutcome(result, resolvedUser));
        });
    }

    /** Invalidates all cached protection scopes. */
    public void clearCache() {
        cache.clear();
    }

    /** Returns the bounded cache entry count. */
    public int cacheSize() {
        purgeExpired();
        return cache.size();
    }

    /** Cancels background work and closes only a framework-created executor. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<Future<?>> jobs;
        synchronized (backgroundLifecycleLock) {
            jobs = List.copyOf(backgroundJobs);
            backgroundJobs.clear();
        }
        jobs.forEach(job -> job.cancel(true));
        cache.clear();
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
            try {
                if (!ownedExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Purview background executor did not terminate.");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Purview evaluator close was interrupted.", failure);
            }
        }
    }

    private CompletionStage<PurviewDecision> evaluateOneAsync(
            PurviewContentRequest request, RunCancellation cancellation) {
        ScopeKey key = new ScopeKey(request.userId(), request.tenantId(), request.activity(), request.location());
        return scopesAsync(key, request, cancellation).thenCompose(scopes -> {
            Applicable applicable = applicable(scopes, request);
            if (!applicable.shouldProcess()) {
                return submitBackground(() -> client.recordContentActivityAsync(request, cancellation))
                        .thenApply(ignored -> PurviewDecision.allow());
            }
            if (applicable.mode() == PurviewExecutionMode.UNKNOWN) {
                return CompletableFuture.failedFuture(new PurviewException(
                        "Purview returned an unknown execution mode.",
                        null,
                        PurviewException.Kind.PROTOCOL,
                        null,
                        scopes.requestId(),
                        "unknown_execution_mode",
                        null));
            }
            if (applicable.mode() == PurviewExecutionMode.EVALUATE_OFFLINE) {
                PurviewDecision local = new PurviewDecision(
                        applicable.actions().stream().anyMatch(PurviewPolicyAction::blocksAccess),
                        false,
                        applicable.actions(),
                        scopes.requestId());
                return submitBackground(() -> client.processContentAsync(request, scopes, false, cancellation)
                                .thenApply(decision -> {
                                    if (decision.protectionScopeModified()) {
                                        cache.remove(key);
                                    }
                                    return decision;
                                }))
                        .thenApply(ignored -> local);
            }
            return client.processContentAsync(request, scopes, true, cancellation)
                    .thenApply(decision -> {
                        if (decision.protectionScopeModified()) {
                            cache.remove(key);
                        }
                        return merge(decision, applicable.actions());
                    });
        });
    }

    private CompletionStage<PurviewProtectionScopes> scopesAsync(
            ScopeKey key, PurviewContentRequest request, RunCancellation cancellation) {
        purgeExpired();
        CachedScopes cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(clock.instant())) {
            return CompletableFuture.completedStage(cached.value());
        }
        return client.computeProtectionScopesAsync(request, cancellation).thenApply(scopes -> {
            putCache(key, scopes);
            return scopes;
        });
    }

    private CompletionStage<Void> submitBackground(
            java.util.function.Supplier<? extends CompletionStage<?>> operation) {
        if (pendingJobs.incrementAndGet() > settings.maximumPendingJobs()) {
            pendingJobs.decrementAndGet();
            return backgroundAdmissionFailure();
        }
        CompletableFuture<Void> admitted = new CompletableFuture<>();
        synchronized (backgroundLifecycleLock) {
            if (closed.get()) {
                pendingJobs.decrementAndGet();
                return backgroundClosedFailure();
            }
            try {
                AtomicReferenceFuture holder = new AtomicReferenceFuture();
                Future<?> future = backgroundExecutor.submit(() -> {
                    boolean permit = false;
                    try {
                        concurrentJobs.acquire();
                        permit = true;
                        operation.get().toCompletableFuture().join();
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException failure) {
                        // Offline Purview work cannot retroactively alter an admitted interaction.
                    } finally {
                        if (permit) {
                            concurrentJobs.release();
                        }
                        pendingJobs.decrementAndGet();
                        Future<?> current = holder.value;
                        if (current != null) {
                            backgroundJobs.remove(current);
                        }
                    }
                });
                holder.value = future;
                backgroundJobs.add(future);
                if (future.isDone()) {
                    backgroundJobs.remove(future);
                }
                admitted.complete(null);
            } catch (RejectedExecutionException failure) {
                pendingJobs.decrementAndGet();
                admitted.completeExceptionally(failure);
            }
        }
        if (settings.failureMode() == PurviewFailureMode.FAIL_OPEN) {
            return admitted.handle((ignored, failure) -> null);
        }
        return admitted.minimalCompletionStage();
    }

    private CompletionStage<Void> backgroundClosedFailure() {
        if (settings.failureMode() == PurviewFailureMode.FAIL_OPEN) {
            return CompletableFuture.completedStage(null);
        }
        return CompletableFuture.failedFuture(new PurviewException(
                "Purview background work cannot be admitted after close.",
                null,
                PurviewException.Kind.CONFIGURATION,
                null,
                null,
                "background_closed",
                null));
    }

    private CompletionStage<Void> backgroundAdmissionFailure() {
        if (settings.failureMode() == PurviewFailureMode.FAIL_OPEN) {
            return CompletableFuture.completedStage(null);
        }
        return CompletableFuture.failedFuture(new PurviewException(
                "Purview background job capacity is exhausted.",
                null,
                PurviewException.Kind.CONFIGURATION,
                null,
                null,
                "background_capacity",
                null));
    }

    private Applicable applicable(PurviewProtectionScopes scopes, PurviewContentRequest request) {
        boolean shouldProcess = false;
        PurviewExecutionMode mode = PurviewExecutionMode.EVALUATE_OFFLINE;
        LinkedHashMap<String, PurviewPolicyAction> actions = new LinkedHashMap<>();
        for (PurviewProtectionScope scope : scopes.scopes()) {
            boolean activity = scope.activities().contains(request.activity());
            boolean location = scope.locations().stream()
                    .anyMatch(item -> item.type() == request.location().type()
                            && item.value().equalsIgnoreCase(request.location().value()));
            if (!activity || !location) {
                continue;
            }
            shouldProcess = true;
            if (scope.executionMode() == PurviewExecutionMode.UNKNOWN) {
                mode = PurviewExecutionMode.UNKNOWN;
            } else if (mode != PurviewExecutionMode.UNKNOWN
                    && scope.executionMode() == PurviewExecutionMode.EVALUATE_INLINE) {
                mode = PurviewExecutionMode.EVALUATE_INLINE;
            }
            scope.policyActions()
                    .forEach(action -> actions.putIfAbsent(action.action() + ":" + action.restrictionAction(), action));
        }
        return new Applicable(shouldProcess, mode, List.copyOf(actions.values()));
    }

    private static PurviewDecision merge(PurviewDecision service, List<PurviewPolicyAction> localActions) {
        LinkedHashMap<String, PurviewPolicyAction> actions = new LinkedHashMap<>();
        StreamSupport.concat(service.actions(), localActions)
                .forEach(action -> actions.putIfAbsent(action.action() + ":" + action.restrictionAction(), action));
        List<PurviewPolicyAction> combined = List.copyOf(actions.values());
        return new PurviewDecision(
                service.blocked() || combined.stream().anyMatch(PurviewPolicyAction::blocksAccess),
                service.protectionScopeModified(),
                combined,
                service.requestId());
    }

    private void putCache(ScopeKey key, PurviewProtectionScopes scopes) {
        cache.put(key, new CachedScopes(scopes, clock.instant().plus(settings.cacheTimeToLive())));
        if (cache.size() <= settings.maximumCacheEntries()) {
            return;
        }
        cache.entrySet().stream()
                .min(Comparator.comparing(entry -> entry.getValue().expiresAt()))
                .map(Map.Entry::getKey)
                .ifPresent(cache::remove);
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("PurviewPolicyEvaluator is closed.");
        }
    }

    private static String userFromMessages(List<Message> messages) {
        for (Message message : messages) {
            String value = metadataString(message.metadata(), "userId");
            if (value == null) {
                value = metadataString(message.metadata(), "user_id");
            }
            if (value != null) {
                return value;
            }
            if (message.authorName() != null && isGuid(message.authorName())) {
                return message.authorName();
            }
        }
        return null;
    }

    private static String metadataString(Map<String, StateValue> metadata, String key) {
        StateValue value = metadata.get(key);
        return value instanceof StateValue.StringValue string ? string.value() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String requireGuid(String value, String name) {
        if (!isGuid(value)) {
            throw new PurviewException(
                    name + " must be a valid Entra GUID.",
                    null,
                    PurviewException.Kind.CONFIGURATION,
                    null,
                    null,
                    "invalid_" + name,
                    null);
        }
        return value;
    }

    private static boolean isGuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException failure) {
            return false;
        }
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

    private record ScopeKey(String userId, String tenantId, PurviewActivity activity, PurviewAppLocation location) {}

    private record CachedScopes(PurviewProtectionScopes value, Instant expiresAt) {}

    private record Applicable(boolean shouldProcess, PurviewExecutionMode mode, List<PurviewPolicyAction> actions) {}

    private static final class AtomicReferenceFuture {
        private volatile Future<?> value;
    }

    private static final class StreamSupport {
        private StreamSupport() {}

        private static java.util.stream.Stream<PurviewPolicyAction> concat(
                List<PurviewPolicyAction> first, List<PurviewPolicyAction> second) {
            return java.util.stream.Stream.concat(first.stream(), second.stream());
        }
    }
}
