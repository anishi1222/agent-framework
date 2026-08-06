// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.Message;
import java.util.List;
import java.util.Objects;

/**
 * Supplies immutable state to a sequential output-to-input transform.
 *
 * @param stepIndex zero-based next participant index
 * @param previousParticipant participant that produced the prior response
 * @param nextParticipant participant about to run
 * @param initialInput immutable original input
 * @param transcript immutable canonical transcript
 * @param previousResponse prior successful response
 */
public record SequentialTransformContext(
        int stepIndex,
        OrchestrationParticipant previousParticipant,
        OrchestrationParticipant nextParticipant,
        List<Message> initialInput,
        List<Message> transcript,
        AgentResponse<?> previousResponse) {
    /** Creates a validated immutable transform context. */
    public SequentialTransformContext {
        if (stepIndex <= 0) {
            throw new IllegalArgumentException("stepIndex must be greater than zero.");
        }
        previousParticipant = Objects.requireNonNull(previousParticipant, "previousParticipant");
        nextParticipant = Objects.requireNonNull(nextParticipant, "nextParticipant");
        initialInput = OrchestrationValidation.copyMessages(initialInput);
        transcript = OrchestrationValidation.copyMessages(transcript);
        previousResponse = Objects.requireNonNull(previousResponse, "previousResponse");
    }
}
