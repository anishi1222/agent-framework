// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows.declarative;

final class WorkflowDefinitionValidation {
    private WorkflowDefinitionValidation() {}

    static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new DeclarativeWorkflowValidationException(name + " must not be blank.");
        }
        return value;
    }
}
