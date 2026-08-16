// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.protocols.agui.AGUIMessage;
import java.time.Instant;
import java.util.List;

/**
 * Represents one immutable principal-scoped AG-UI thread snapshot.
 *
 * @param messages synchronized transcript
 * @param state synchronized shared state
 * @param activeClientRunId active AG-UI run, or {@code null}
 * @param pendingContinuation pending process-local continuation, or {@code null}
 * @param updatedAt last successful compare-and-set time
 */
public record AGUIThreadState(
        List<AGUIMessage> messages,
        StateValue state,
        String activeClientRunId,
        AGUIPendingContinuation pendingContinuation,
        Instant updatedAt) {
    /** Creates an immutable thread state. */
    public AGUIThreadState {
        messages = List.copyOf(java.util.Objects.requireNonNull(messages, "messages"));
        state = java.util.Objects.requireNonNull(state, "state");
        if (activeClientRunId != null && activeClientRunId.isBlank()) {
            throw new IllegalArgumentException("activeClientRunId must not be blank.");
        }
        java.util.Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * Returns an initial inactive state.
     *
     * @param messages request transcript
     * @param state request state
     * @param now creation instant
     * @return initial state
     */
    public static AGUIThreadState initial(List<AGUIMessage> messages, StateValue state, Instant now) {
        return new AGUIThreadState(messages, state, null, null, now);
    }
}
