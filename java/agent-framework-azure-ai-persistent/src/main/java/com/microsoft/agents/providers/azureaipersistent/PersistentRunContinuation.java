// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

import java.util.List;

/**
 * Describes an explicit continuation for a requires-action run.
 *
 * @param threadId owning thread
 * @param runId owning run
 * @param kind continuation kind
 * @param toolOutputs caller-reviewed tool outputs
 * @param approved optional approval decision
 * @param input optional input text
 */
public record PersistentRunContinuation(
        String threadId,
        String runId,
        PersistentContinuationKind kind,
        List<PersistentToolOutput> toolOutputs,
        Boolean approved,
        String input) {
    /** Creates and validates a continuation. */
    public PersistentRunContinuation {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be blank.");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank.");
        }
        kind = java.util.Objects.requireNonNull(kind, "kind");
        toolOutputs = toolOutputs == null ? List.of() : List.copyOf(toolOutputs);
        if (input != null && input.isBlank()) {
            throw new IllegalArgumentException("input must not be blank.");
        }
    }
}
