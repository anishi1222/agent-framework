// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import com.microsoft.agents.core.VersionedSnapshot;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Stores principal-scoped AG-UI thread snapshots with optimistic compare-and-set semantics. */
public interface AGUIThreadStore extends AutoCloseable {
    /** Expected revision used only when no state exists. */
    long CREATE_ONLY = 0L;

    /**
     * Loads a detached current snapshot.
     *
     * @param key principal-scoped key
     * @return optional versioned state
     */
    CompletionStage<Optional<VersionedSnapshot<AGUIThreadState>>> loadAsync(AGUIThreadKey key);

    /**
     * Stores state only when the current revision matches.
     *
     * @param key principal-scoped key
     * @param state replacement state
     * @param expectedRevision zero for create or the current positive revision
     * @return stored state and new revision
     */
    CompletionStage<VersionedSnapshot<AGUIThreadState>> compareAndSetAsync(
            AGUIThreadKey key, AGUIThreadState state, long expectedRevision);

    /**
     * Deletes state only at an exact positive revision.
     *
     * @param key principal-scoped key
     * @param expectedRevision current positive revision
     * @return completion
     */
    CompletionStage<Void> deleteAsync(AGUIThreadKey key, long expectedRevision);

    /** Releases store resources. */
    @Override
    void close();
}
