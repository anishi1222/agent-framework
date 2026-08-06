// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import java.util.Objects;

/**
 * Represents one immutable task in a Magentic plan.
 *
 * @param id stable task identifier
 * @param description non-blank task instruction
 * @param participantId registered assigned participant
 * @param status task status
 */
public record MagenticTask(String id, String description, String participantId, MagenticTaskStatus status) {
    /** Creates a validated immutable task. */
    public MagenticTask {
        id = OrchestrationValidation.requireId(id, "id");
        description = OrchestrationValidation.requireText(description, "description");
        participantId = OrchestrationValidation.requireId(participantId, "participantId");
        status = Objects.requireNonNull(status, "status");
    }

    /**
     * Creates a pending task.
     *
     * @param id task identifier
     * @param description task instruction
     * @param participantId assigned participant
     * @return pending task
     */
    public static MagenticTask pending(String id, String description, String participantId) {
        return new MagenticTask(id, description, participantId, MagenticTaskStatus.PENDING);
    }

    /**
     * Returns a copy with a replacement status.
     *
     * @param replacement replacement status
     * @return updated immutable task
     */
    public MagenticTask withStatus(MagenticTaskStatus replacement) {
        return new MagenticTask(id, description, participantId, replacement);
    }
}
