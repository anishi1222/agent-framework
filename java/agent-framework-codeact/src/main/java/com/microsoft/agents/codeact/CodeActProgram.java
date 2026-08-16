// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Represents one immutable ordered CodeAct program.
 *
 * @param steps non-empty ordered steps with unique identifiers
 */
public record CodeActProgram(List<CodeActStep> steps) {
    /** Creates a validated immutable program. */
    public CodeActProgram {
        steps = CodeActValidation.copyList(steps, "steps");
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("steps must not be empty.");
        }
        Set<String> identifiers = new HashSet<>();
        for (CodeActStep step : steps) {
            if (!identifiers.add(step.id())) {
                throw new IllegalArgumentException("Duplicate CodeAct step id '" + step.id() + "'.");
            }
        }
    }

    /**
     * Creates a program from command text using deterministic generated step identifiers.
     *
     * @param commands ordered command text
     * @return immutable CodeAct program
     */
    public static CodeActProgram ofCommands(String... commands) {
        Objects.requireNonNull(commands, "commands");
        ArrayList<CodeActStep> steps = new ArrayList<>(commands.length);
        for (int index = 0; index < commands.length; index++) {
            steps.add(new CodeActStep("step-" + (index + 1), commands[index]));
        }
        return new CodeActProgram(steps);
    }
}
