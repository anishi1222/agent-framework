// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.RunCancellation;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Implements a code-defined skill resource. */
public final class InlineSkillResource implements SkillResource {
    private final String name;
    private final String description;
    private final Function<RunCancellation, CompletionStage<SkillResourceContent>> reader;

    /**
     * Creates an asynchronous code-defined resource.
     *
     * @param name resource name
     * @param description optional description
     * @param reader asynchronous reader
     */
    public InlineSkillResource(
            String name, String description, Function<RunCancellation, CompletionStage<SkillResourceContent>> reader) {
        this.name = SkillValidation.requireMemberName(name, "resource");
        this.description = SkillValidation.optionalNonBlank(description, "description");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    /**
     * Creates a static text resource.
     *
     * @param name resource name
     * @param description optional description
     * @param content resource text
     * @return resource
     */
    public static InlineSkillResource text(String name, String description, String content) {
        SkillResourceContent.Text text = new SkillResourceContent.Text(content);
        return new InlineSkillResource(name, description, ignored -> CompletableFuture.completedFuture(text));
    }

    /**
     * Creates a static binary resource.
     *
     * @param name resource name
     * @param description optional description
     * @param content resource bytes
     * @return resource
     */
    public static InlineSkillResource binary(String name, String description, byte[] content) {
        SkillResourceContent.Binary binary = new SkillResourceContent.Binary(content);
        return new InlineSkillResource(name, description, ignored -> CompletableFuture.completedFuture(binary));
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
    public CompletionStage<SkillResourceContent> readAsync(RunCancellation cancellation) {
        SkillValidation.requireActive(cancellation);
        CompletionStage<SkillResourceContent> stage =
                Objects.requireNonNull(reader.apply(cancellation), "reader result");
        return stage.thenApply(content -> Objects.requireNonNull(content, "resource content"));
    }
}
