// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import com.microsoft.agents.core.Message;
import java.util.List;

/**
 * Represents an immutable projected history and its audit metadata.
 *
 * @param messages deterministic chronological result
 * @param audit compaction audit
 */
public record CompactionResult(List<Message> messages, CompactionAudit audit) {
    /** Creates and defensively copies a result. */
    public CompactionResult {
        messages = List.copyOf(messages);
        if (audit == null) {
            throw new NullPointerException("audit");
        }
    }
}
