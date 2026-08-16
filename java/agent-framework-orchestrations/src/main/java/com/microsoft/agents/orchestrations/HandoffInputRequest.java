// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/**
 * Requests additional human input before handoff execution can continue.
 *
 * @param prompt non-blank human-facing prompt
 */
public record HandoffInputRequest(String prompt) implements HandoffDirective {
    /** Creates a validated input request. */
    public HandoffInputRequest {
        prompt = OrchestrationValidation.requireText(prompt, "prompt");
    }
}
