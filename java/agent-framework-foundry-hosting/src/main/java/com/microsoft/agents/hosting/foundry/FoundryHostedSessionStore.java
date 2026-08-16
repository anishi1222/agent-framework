// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.foundry;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Stores hosted session references under principal and isolation partitions. */
public interface FoundryHostedSessionStore {
    /**
     * Loads a detached session.
     *
     * @param key authenticated partition
     * @return optional session stage
     */
    CompletionStage<Optional<FoundryHostedSession>> loadAsync(FoundryHostedSessionKey key);

    /**
     * Saves using optimistic concurrency.
     *
     * @param session replacement session
     * @param expectedRevision expected revision
     * @return stored session with a new revision
     */
    CompletionStage<FoundryHostedSession> saveAsync(FoundryHostedSession session, long expectedRevision);

    /**
     * Deletes a session reference.
     *
     * @param key authenticated partition
     * @return whether a reference was removed
     */
    CompletionStage<Boolean> deleteAsync(FoundryHostedSessionKey key);
}
