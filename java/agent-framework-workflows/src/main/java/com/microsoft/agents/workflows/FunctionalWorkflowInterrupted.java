// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

final class FunctionalWorkflowInterrupted extends Error {
    private static final long serialVersionUID = 1L;

    private final transient FunctionalInputRequest request;

    FunctionalWorkflowInterrupted(FunctionalInputRequest request) {
        super("Functional workflow requires input for request '" + request.requestId() + "'.", null, false, false);
        this.request = request;
    }

    FunctionalInputRequest request() {
        return request;
    }
}
