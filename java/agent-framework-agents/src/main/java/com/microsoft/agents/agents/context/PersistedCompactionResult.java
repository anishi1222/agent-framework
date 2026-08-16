// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import com.microsoft.agents.agents.AgentSessionSnapshot;
import com.microsoft.agents.core.VersionedSnapshot;

/**
 * Reports one explicit persisted-history compaction.
 *
 * @param compaction immutable projected history and audit
 * @param storedSnapshot current or newly stored versioned snapshot
 * @param replaced whether a compare-and-set replacement was written
 */
public record PersistedCompactionResult(
        CompactionResult compaction, VersionedSnapshot<AgentSessionSnapshot> storedSnapshot, boolean replaced) {
    /** Creates a validated persisted result. */
    public PersistedCompactionResult {
        if (compaction == null) {
            throw new NullPointerException("compaction");
        }
        if (storedSnapshot == null) {
            throw new NullPointerException("storedSnapshot");
        }
    }
}
