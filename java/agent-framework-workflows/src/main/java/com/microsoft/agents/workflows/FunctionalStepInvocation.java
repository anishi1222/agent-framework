// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

record FunctionalStepInvocation(String stepName, int callIndex) implements Comparable<FunctionalStepInvocation> {
    FunctionalStepInvocation {
        stepName = WorkflowValidation.requireNonBlank(stepName, "stepName");
        if (callIndex < 0) {
            throw new WorkflowValidationException("callIndex must not be negative.");
        }
    }

    String correlationId() {
        return stepName + "::" + callIndex;
    }

    @Override
    public int compareTo(FunctionalStepInvocation other) {
        int nameComparison = stepName.compareTo(other.stepName);
        return nameComparison == 0 ? Integer.compare(callIndex, other.callIndex) : nameComparison;
    }
}
