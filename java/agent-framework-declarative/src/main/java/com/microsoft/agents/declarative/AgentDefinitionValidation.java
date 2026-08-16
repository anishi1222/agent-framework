// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.declarative;

final class AgentDefinitionValidation {
    private AgentDefinitionValidation() {}

    static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new DeclarativeAgentValidationException(name + " must not be blank.");
        }
        return value;
    }

    static String optionalNonBlank(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new DeclarativeAgentValidationException(name + " must not be blank when present.");
        }
        return value;
    }
}
