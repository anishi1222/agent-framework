// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import java.util.concurrent.CompletionStage;

/** Handles one code-defined skill script invocation. */
@FunctionalInterface
public interface SkillScriptHandler {
    /**
     * Runs a script with named arguments.
     *
     * @param arguments immutable named arguments
     * @param cancellation cancellation signal
     * @return script result stage
     */
    CompletionStage<StateValue> runAsync(StateValue.ObjectValue arguments, RunCancellation cancellation);
}
