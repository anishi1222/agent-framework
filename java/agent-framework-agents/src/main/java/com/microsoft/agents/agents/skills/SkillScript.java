// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import java.util.concurrent.CompletionStage;

/** Runs one named script owned by a skill. */
public interface SkillScript {
    /**
     * Returns the case-insensitive lookup name.
     *
     * @return non-blank script name
     */
    String name();

    /**
     * Returns the optional model-facing description.
     *
     * @return description, or {@code null}
     */
    String description();

    /**
     * Returns the optional JSON Schema for script arguments.
     *
     * @return argument schema, or {@code null}
     */
    StateValue.ObjectValue parametersSchema();

    /**
     * Runs the script.
     *
     * @param skill owning skill
     * @param arguments object, string-array, or JSON null arguments
     * @param cancellation cancellation signal
     * @return script result stage
     */
    CompletionStage<StateValue> runAsync(Skill skill, StateValue arguments, RunCancellation cancellation);
}
