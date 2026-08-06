// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.tools.FunctionContinuation;
import java.util.List;

record PendingAgentRunState(
        AgentContinuation continuation,
        FunctionContinuation functionContinuation,
        List<Message> inputMessages,
        RunOptions options,
        int initialMessageCount) {
    PendingAgentRunState {
        continuation = AgentValidation.requireNonNull(continuation, "continuation");
        functionContinuation = AgentValidation.requireNonNull(functionContinuation, "functionContinuation");
        inputMessages = AgentValidation.copyMessages(inputMessages);
        options = AgentValidation.requireNonNull(options, "options");
        if (initialMessageCount < inputMessages.size()) {
            throw new com.microsoft.agents.core.ValidationException(
                    "initialMessageCount must include all caller input messages.");
        }
        if (!continuation.logicalRunId().equals(functionContinuation.logicalRunId())) {
            throw new com.microsoft.agents.core.ValidationException("Continuation logical run identifiers must match.");
        }
    }
}
