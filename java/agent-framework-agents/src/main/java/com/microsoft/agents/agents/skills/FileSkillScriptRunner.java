// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import java.util.concurrent.CompletionStage;

/** Executes scripts discovered from file-based skills. */
@FunctionalInterface
public interface FileSkillScriptRunner {
    /**
     * Runs a resolved file script.
     *
     * @param skill owning file skill
     * @param script resolved script
     * @param arguments object, string-array, or JSON null arguments
     * @param cancellation cancellation signal
     * @return script result stage
     */
    CompletionStage<StateValue> runAsync(
            FileSkill skill, FileSkillScript script, StateValue arguments, RunCancellation cancellation);
}
