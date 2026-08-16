// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import com.microsoft.agents.core.Message;
import java.util.List;

/**
 * Directs the next autonomous-loop transition.
 *
 * @param shouldContinue whether another agent invocation is required
 * @param feedback optional user-role feedback for the next invocation
 * @param messages optional explicit next messages
 */
public record LoopEvaluation(boolean shouldContinue, String feedback, List<Message> messages) {
    /** Creates a validated immutable evaluation. */
    public LoopEvaluation {
        messages = List.copyOf(messages == null ? List.of() : messages);
        if (!shouldContinue && (feedback != null || !messages.isEmpty())) {
            throw new IllegalArgumentException("A stop evaluation cannot carry feedback or messages.");
        }
        if (feedback != null && feedback.isBlank()) {
            throw new IllegalArgumentException("feedback must not be blank when present.");
        }
        if (feedback != null && !messages.isEmpty()) {
            throw new IllegalArgumentException("A continuation must use feedback or messages, not both.");
        }
    }

    /** Stops the loop. */
    public static LoopEvaluation stop() {
        return new LoopEvaluation(false, null, List.of());
    }

    /**
     * Continues with optional synthesized user feedback.
     *
     * @param feedback feedback, or {@code null} for the default nudge
     * @return continuation evaluation
     */
    public static LoopEvaluation continueWithFeedback(String feedback) {
        return new LoopEvaluation(true, feedback, List.of());
    }

    /**
     * Continues with explicit next messages.
     *
     * @param messages non-empty ordered messages
     * @return continuation evaluation
     */
    public static LoopEvaluation continueWithMessages(List<Message> messages) {
        List<Message> safe = List.copyOf(messages);
        if (safe.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty.");
        }
        return new LoopEvaluation(true, null, safe);
    }
}
