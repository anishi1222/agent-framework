// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.memory;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.VersionedSnapshot;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Stores and searches tenant-isolated memory using framework-owned asynchronous contracts. */
public interface MemoryStore {
    /** Expected revision used for a create-only write. */
    long CREATE_ONLY = -1L;

    /**
     * Creates a memory only when its key is absent.
     *
     * @param record replacement record
     * @param cancellation caller-owned cancellation
     * @return stored record and assigned revision
     */
    CompletionStage<VersionedSnapshot<MemoryRecord>> putAsync(MemoryRecord record, RunCancellation cancellation);

    /**
     * Creates or replaces a memory using an exact optimistic revision.
     *
     * @param record replacement record
     * @param expectedRevision {@link #CREATE_ONLY} for create, otherwise the current revision
     * @param cancellation caller-owned cancellation
     * @return stored record and assigned revision
     */
    CompletionStage<VersionedSnapshot<MemoryRecord>> upsertAsync(
            MemoryRecord record, long expectedRevision, RunCancellation cancellation);

    /**
     * Loads one detached memory.
     *
     * @param key scoped key
     * @param cancellation caller-owned cancellation
     * @return optional versioned record
     */
    CompletionStage<Optional<VersionedSnapshot<MemoryRecord>>> getAsync(MemoryKey key, RunCancellation cancellation);

    /**
     * Deletes one memory using an exact optimistic revision.
     *
     * @param key scoped key
     * @param expectedRevision positive current revision
     * @param cancellation caller-owned cancellation
     * @return completion stage
     */
    CompletionStage<Void> deleteAsync(MemoryKey key, long expectedRevision, RunCancellation cancellation);

    /**
     * Lists a bounded page inside exactly one scope.
     *
     * @param request scoped list request
     * @param cancellation caller-owned cancellation
     * @return detached record page
     */
    CompletionStage<MemoryPage<VersionedSnapshot<MemoryRecord>>> listAsync(
            MemoryListRequest request, RunCancellation cancellation);

    /**
     * Searches inside exactly one scope.
     *
     * <p>Providers define whether continuation cursors are supported. Provider rank order is
     * represented by {@link MemorySearchResult#rank()}.
     *
     * @param query scoped search
     * @param cancellation caller-owned cancellation
     * @return bounded result page
     */
    CompletionStage<MemoryPage<MemorySearchResult>> searchAsync(MemoryQuery query, RunCancellation cancellation);

    /**
     * Creates a memory with an independent cancellation token.
     *
     * @param record record
     * @return stored record and revision
     */
    default CompletionStage<VersionedSnapshot<MemoryRecord>> putAsync(MemoryRecord record) {
        return putAsync(record, new DefaultRunCancellation());
    }

    /**
     * Loads a memory with an independent cancellation token.
     *
     * @param key key
     * @return optional record
     */
    default CompletionStage<Optional<VersionedSnapshot<MemoryRecord>>> getAsync(MemoryKey key) {
        return getAsync(key, new DefaultRunCancellation());
    }
}
