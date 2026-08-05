// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.StateValue;
import java.util.Objects;

/**
 * Represents one immutable correlated tool invocation result.
 *
 * @param invocationId stable invocation identifier
 * @param callId provider call correlation identifier
 * @param outcome terminal outcome
 * @param value JSON-shaped result or {@link StateValue.NullValue}
 * @param error optional sanitized error description
 */
public record ToolInvocationResult(
        InvocationId invocationId, String callId, ToolInvocationOutcome outcome, StateValue value, String error) {
    /** Creates a validated immutable invocation result. */
    public ToolInvocationResult {
        Objects.requireNonNull(invocationId, "invocationId");
        callId = ToolValidation.requireNonBlank(callId, "callId");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(value, "value");
        error = ToolValidation.optionalNonBlank(error, "error");
        if (outcome == ToolInvocationOutcome.FAILED && error == null) {
            throw new IllegalArgumentException("A failed invocation result requires an error.");
        }
    }

    /**
     * Creates a successful invocation result.
     *
     * @param invocationId invocation identifier
     * @param callId call identifier
     * @param value JSON-shaped result
     * @return successful result
     */
    public static ToolInvocationResult succeeded(InvocationId invocationId, String callId, StateValue value) {
        return new ToolInvocationResult(invocationId, callId, ToolInvocationOutcome.SUCCEEDED, value, null);
    }

    /**
     * Creates a sanitized failed invocation result.
     *
     * @param invocationId invocation identifier
     * @param callId call identifier
     * @param error sanitized error
     * @return failed result
     */
    public static ToolInvocationResult failed(InvocationId invocationId, String callId, String error) {
        return new ToolInvocationResult(
                invocationId, callId, ToolInvocationOutcome.FAILED, StateValue.string(error), error);
    }
}
