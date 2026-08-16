// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.StateValue;

/** Converts raw model script arguments to named arguments for an inline script. */
@FunctionalInterface
public interface SkillScriptArgumentParser {
    /**
     * Parses raw script arguments.
     *
     * @param arguments object, string-array, or JSON null arguments
     * @return named arguments
     */
    StateValue.ObjectValue parse(StateValue arguments);
}
