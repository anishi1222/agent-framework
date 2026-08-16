// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.StateValue;
import java.util.Objects;

/**
 * Describes one pending external-information request from a functional workflow.
 *
 * @param requestId stable request identifier used when resuming
 * @param sourceId workflow or step that requested the information
 * @param data immutable JSON-shaped request data
 * @param responseTypeId expected response codec identifier
 * @param responseVersion expected response codec version
 */
public record FunctionalInputRequest(
        String requestId, String sourceId, StateValue data, String responseTypeId, int responseVersion) {
    /** Creates a validated immutable request. */
    public FunctionalInputRequest {
        requestId = WorkflowValidation.requireNonBlank(requestId, "requestId");
        sourceId = WorkflowValidation.requireNonBlank(sourceId, "sourceId");
        Objects.requireNonNull(data, "data");
        responseTypeId = WorkflowValidation.requireNonBlank(responseTypeId, "responseTypeId");
        if (responseVersion <= 0) {
            throw new WorkflowValidationException("responseVersion must be greater than zero.");
        }
    }
}
