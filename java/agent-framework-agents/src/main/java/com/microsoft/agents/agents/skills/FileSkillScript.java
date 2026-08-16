// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Represents one executable file discovered inside a file-based skill. */
public final class FileSkillScript implements SkillScript {
    private static final StateValue.ObjectValue ARRAY_SCHEMA = StateValue.object(Map.of(
            "type",
            StateValue.string("array"),
            "items",
            StateValue.object(Map.of("type", StateValue.string("string")))));

    private final String name;
    private final Path fullPath;
    private final FileSkillScriptRunner runner;

    /**
     * Creates a file script.
     *
     * @param name relative script name
     * @param fullPath absolute validated file path
     * @param runner configured runner
     */
    public FileSkillScript(String name, Path fullPath, FileSkillScriptRunner runner) {
        this.name = SkillValidation.requireRelativeName(name, "script");
        this.fullPath =
                Objects.requireNonNull(fullPath, "fullPath").toAbsolutePath().normalize();
        if (!this.fullPath.isAbsolute()) {
            throw new IllegalArgumentException("fullPath must be absolute.");
        }
        this.runner = runner;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return null;
    }

    /**
     * Returns the absolute script path.
     *
     * @return script path
     */
    public Path fullPath() {
        return fullPath;
    }

    @Override
    public StateValue.ObjectValue parametersSchema() {
        return ARRAY_SCHEMA;
    }

    @Override
    public CompletionStage<StateValue> runAsync(Skill skill, StateValue arguments, RunCancellation cancellation) {
        SkillValidation.requireActive(cancellation);
        if (!(skill instanceof FileSkill fileSkill)) {
            throw new IllegalArgumentException("FileSkillScript requires a FileSkill owner.");
        }
        if (!(arguments instanceof StateValue.ObjectValue
                || arguments instanceof StateValue.ArrayValue
                || arguments instanceof StateValue.NullValue)) {
            throw new IllegalArgumentException("File script arguments must be an object, string array, or null.");
        }
        if (arguments instanceof StateValue.ArrayValue array
                && array.values().stream().anyMatch(value -> !(value instanceof StateValue.StringValue))) {
            throw new IllegalArgumentException("File script array arguments must contain only strings.");
        }
        if (runner == null) {
            throw new IllegalStateException("No runner is configured for file skill script '" + name + "'.");
        }
        return Objects.requireNonNull(runner.runAsync(fileSkill, this, arguments, cancellation), "runner result")
                .thenApply(result -> Objects.requireNonNull(result, "script result"));
    }
}
