// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import com.microsoft.agents.agents.AgentSessionSnapshot;
import com.microsoft.agents.agents.SessionKey;
import com.microsoft.agents.agents.SessionStore;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.VersionedSnapshot;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Performs explicit compare-and-set replacement of persisted session history.
 *
 * <p>The operation loads one detached snapshot, compacts it without mutation, and performs exactly
 * one save using the loaded opaque revision. It never retries a conflict or falls back to
 * last-writer-wins. A strategy failure, cancellation, or storage conflict leaves the original
 * persisted snapshot unchanged. Required-content limit overflow is also never partially persisted.
 */
public final class PersistedHistoryCompactor {
    private PersistedHistoryCompactor() {}

    /**
     * Compacts persisted history using the heuristic estimator and framework-owned cancellation.
     *
     * @param store session store
     * @param key session key
     * @param strategy compaction strategy
     * @return persisted result stage
     */
    public static CompletionStage<PersistedCompactionResult> compactAsync(
            SessionStore store, SessionKey key, CompactionStrategy strategy) {
        return compactAsync(store, key, strategy, TokenEstimator.heuristic(), new DefaultRunCancellation());
    }

    /**
     * Compacts persisted history with explicit dependencies.
     *
     * @param store session store
     * @param key session key
     * @param strategy compaction strategy
     * @param estimator token estimator
     * @param cancellation cancellation signal
     * @return persisted result stage
     */
    public static CompletionStage<PersistedCompactionResult> compactAsync(
            SessionStore store,
            SessionKey key,
            CompactionStrategy strategy,
            TokenEstimator estimator,
            RunCancellation cancellation) {
        if (store == null) {
            throw new NullPointerException("store");
        }
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (strategy == null) {
            throw new NullPointerException("strategy");
        }
        if (estimator == null) {
            throw new NullPointerException("estimator");
        }
        if (cancellation == null) {
            throw new NullPointerException("cancellation");
        }
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        CompletionStage<java.util.Optional<VersionedSnapshot<AgentSessionSnapshot>>> loaded = store.loadAsync(key);
        if (loaded == null) {
            return CompletableFuture.failedFuture(new CompactionException("SessionStore.loadAsync returned null."));
        }
        return loaded.thenCompose(optional -> {
            VersionedSnapshot<AgentSessionSnapshot> current = optional.orElseThrow(
                    () -> new CompactionException("Session '" + key.value() + "' does not exist."));
            AgentSessionSnapshot snapshot = current.snapshot();
            return Compactions.compactAsync(strategy, snapshot.messages(), estimator, cancellation)
                    .thenCompose(result -> saveIfChanged(store, key, current, result, cancellation));
        });
    }

    private static CompletionStage<PersistedCompactionResult> saveIfChanged(
            SessionStore store,
            SessionKey key,
            VersionedSnapshot<AgentSessionSnapshot> current,
            CompactionResult result,
            RunCancellation cancellation) {
        if (!result.audit().changed()
                || result.audit().limitStatus() == CompactionLimitStatus.REQUIRED_CONTENT_EXCEEDS_LIMIT) {
            return CompletableFuture.completedFuture(new PersistedCompactionResult(result, current, false));
        }
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        AgentSessionSnapshot source = current.snapshot();
        AgentSessionSnapshot replacement =
                new AgentSessionSnapshot(source.sessionId(), result.messages(), source.state(), source.pendingRun());
        CompletionStage<VersionedSnapshot<AgentSessionSnapshot>> saved =
                store.saveAsync(key, replacement, current.revision());
        if (saved == null) {
            return CompletableFuture.failedFuture(new CompactionException("SessionStore.saveAsync returned null."));
        }
        return saved.thenApply(stored -> new PersistedCompactionResult(result, stored, true));
    }
}
