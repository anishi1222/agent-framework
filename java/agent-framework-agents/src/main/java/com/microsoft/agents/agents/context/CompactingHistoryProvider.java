// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.agents.ContextProviderCompletion;
import com.microsoft.agents.agents.ContextProviderRequest;
import com.microsoft.agents.agents.HistoryProvider;
import com.microsoft.agents.core.Message;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Decorates a history provider with request-only compaction.
 *
 * <p>Loaded history is projected only for the current request. Appends are forwarded unchanged, so
 * caller and session history are never unexpectedly replaced. Use {@link PersistedHistoryCompactor}
 * for an explicit compare-and-set persisted replacement.
 */
public final class CompactingHistoryProvider implements HistoryProvider {
    private final String id;

    private final HistoryProvider delegate;

    private final CompactionStrategy strategy;

    private final TokenEstimator estimator;

    /**
     * Creates a request-only decorator using the heuristic estimator.
     *
     * @param id stable provider identifier
     * @param delegate history provider
     * @param strategy immutable compaction strategy
     */
    public CompactingHistoryProvider(String id, HistoryProvider delegate, CompactionStrategy strategy) {
        this(id, delegate, strategy, TokenEstimator.heuristic());
    }

    /**
     * Creates a request-only decorator.
     *
     * @param id stable provider identifier
     * @param delegate history provider
     * @param strategy immutable compaction strategy
     * @param estimator provider override or heuristic estimator
     */
    public CompactingHistoryProvider(
            String id, HistoryProvider delegate, CompactionStrategy strategy, TokenEstimator estimator) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank.");
        }
        this.id = id;
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
        this.strategy = java.util.Objects.requireNonNull(strategy, "strategy");
        this.estimator = java.util.Objects.requireNonNull(estimator, "estimator");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public CompletionStage<List<Message>> loadMessagesAsync(ContextProviderRequest request) {
        return compactLoadedAsync(request).thenApply(CompactionResult::messages);
    }

    @Override
    public CompletionStage<ContextContribution> provideAsync(ContextProviderRequest request) {
        if (request == null) {
            throw new NullPointerException("request");
        }
        CompletionStage<ContextContribution> provided = delegate.provideAsync(request);
        if (provided == null) {
            return CompletableFuture.failedFuture(
                    new CompactionException("Decorated HistoryProvider.provideAsync returned null."));
        }
        return provided.thenCompose(contribution -> {
            ContextContribution safe = java.util.Objects.requireNonNull(contribution, "history contribution");
            return Compactions.compactAsync(
                            strategy,
                            safe.messages(),
                            estimator,
                            request.runContext().cancellation())
                    .thenApply(result -> {
                        LinkedHashMap<String, com.microsoft.agents.core.StateValue> metadata =
                                new LinkedHashMap<>(safe.metadata());
                        metadata.put(
                                "agentFramework.compaction." + id,
                                result.audit().toStateValue());
                        return new ContextContribution(safe.instructions(), result.messages(), metadata, safe.tools());
                    });
        });
    }

    @Override
    public CompletionStage<Void> appendMessagesAsync(ContextProviderRequest request, List<Message> messages) {
        CompletionStage<Void> stage = delegate.appendMessagesAsync(request, List.copyOf(messages));
        return stage == null
                ? CompletableFuture.failedFuture(
                        new CompactionException("Decorated HistoryProvider.appendMessagesAsync returned null."))
                : stage;
    }

    @Override
    public CompletionStage<Void> completedAsync(ContextProviderCompletion completion) {
        if (completion == null) {
            throw new NullPointerException("completion");
        }
        CompletionStage<Void> stage = delegate.completedAsync(completion);
        return stage == null
                ? CompletableFuture.failedFuture(
                        new CompactionException("Decorated HistoryProvider.completedAsync returned null."))
                : stage;
    }

    private CompletionStage<CompactionResult> compactLoadedAsync(ContextProviderRequest request) {
        if (request == null) {
            throw new NullPointerException("request");
        }
        CompletionStage<List<Message>> loaded = delegate.loadMessagesAsync(request);
        if (loaded == null) {
            return CompletableFuture.failedFuture(
                    new CompactionException("Decorated HistoryProvider.loadMessagesAsync returned null."));
        }
        return loaded.thenCompose(messages -> Compactions.compactAsync(
                strategy,
                List.copyOf(java.util.Objects.requireNonNull(messages, "loaded messages")),
                estimator,
                request.runContext().cancellation()));
    }
}
