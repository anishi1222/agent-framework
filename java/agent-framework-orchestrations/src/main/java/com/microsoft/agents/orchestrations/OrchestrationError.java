// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/**
 * Represents one immutable sanitized orchestration error.
 *
 * @param participantId optional participant associated with the error
 * @param errorType stable exception class name
 * @param message sanitized non-blank message
 */
public record OrchestrationError(String participantId, String errorType, String message) {
    /** Creates a validated error value. */
    public OrchestrationError {
        participantId = OrchestrationValidation.optionalId(participantId, "participantId");
        errorType = OrchestrationValidation.requireText(errorType, "errorType");
        message = OrchestrationValidation.requireText(message, "message");
    }

    static OrchestrationError from(String participantId, Throwable failure) {
        Throwable cause = com.microsoft.agents.core.RunHandles.unwrap(failure);
        String safeMessage = cause.getMessage();
        if (safeMessage == null || safeMessage.isBlank()) {
            safeMessage = cause.getClass().getSimpleName();
        }
        return new OrchestrationError(participantId, cause.getClass().getName(), safeMessage);
    }
}
