// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Implements an in-process code-defined skill script. */
public final class InlineSkillScript implements SkillScript {
    private final String name;
    private final String description;
    private final StateValue.ObjectValue parametersSchema;
    private final SkillScriptArgumentParser argumentParser;
    private final SkillScriptHandler handler;

    /**
     * Creates a code-defined script.
     *
     * @param name script name
     * @param description optional description
     * @param parametersSchema optional named-argument JSON Schema
     * @param argumentParser optional raw-argument parser
     * @param handler script handler
     */
    public InlineSkillScript(
            String name,
            String description,
            StateValue.ObjectValue parametersSchema,
            SkillScriptArgumentParser argumentParser,
            SkillScriptHandler handler) {
        this.name = SkillValidation.requireMemberName(name, "script");
        this.description = SkillValidation.optionalNonBlank(description, "description");
        this.parametersSchema = parametersSchema;
        this.argumentParser = argumentParser;
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    /**
     * Creates a named-argument script without a custom parser.
     *
     * @param name script name
     * @param description optional description
     * @param parametersSchema optional argument schema
     * @param handler script handler
     */
    public InlineSkillScript(
            String name, String description, StateValue.ObjectValue parametersSchema, SkillScriptHandler handler) {
        this(name, description, parametersSchema, null, handler);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public StateValue.ObjectValue parametersSchema() {
        return parametersSchema;
    }

    @Override
    public CompletionStage<StateValue> runAsync(Skill skill, StateValue arguments, RunCancellation cancellation) {
        Objects.requireNonNull(skill, "skill");
        SkillValidation.requireActive(cancellation);
        StateValue.ObjectValue parsed;
        if (argumentParser != null) {
            parsed = Objects.requireNonNull(argumentParser.parse(arguments), "parsed arguments");
        } else if (arguments instanceof StateValue.ObjectValue object) {
            parsed = object;
        } else if (arguments instanceof StateValue.NullValue) {
            parsed = StateValue.object(Map.of());
        } else {
            throw new IllegalArgumentException(
                    "Inline skill script '" + name + "' requires object arguments; configure an argument parser.");
        }
        return Objects.requireNonNull(handler.runAsync(parsed, cancellation), "handler result")
                .thenApply(result -> Objects.requireNonNull(result, "script result"));
    }
}
