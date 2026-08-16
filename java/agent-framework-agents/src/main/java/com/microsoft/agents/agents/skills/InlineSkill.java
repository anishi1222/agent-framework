// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.RunCancellation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Implements a skill defined entirely in Java code. */
public final class InlineSkill implements Skill {
    private final SkillFrontmatter frontmatter;
    private final String instructions;
    private final List<SkillResource> resources;
    private final List<SkillScript> scripts;
    private volatile String cachedContent;

    private InlineSkill(Builder builder) {
        frontmatter = Objects.requireNonNull(builder.frontmatter, "frontmatter");
        instructions = requireNonBlank(builder.instructions, "instructions");
        resources = SkillCollections.resources(builder.resources);
        scripts = SkillCollections.scripts(builder.scripts);
    }

    /**
     * Creates a new inline-skill builder.
     *
     * @param frontmatter skill metadata
     * @param instructions full skill instructions
     * @return builder
     */
    public static Builder builder(SkillFrontmatter frontmatter, String instructions) {
        return new Builder(frontmatter, instructions);
    }

    @Override
    public SkillFrontmatter frontmatter() {
        return frontmatter;
    }

    @Override
    public CompletionStage<String> contentAsync(RunCancellation cancellation) {
        SkillValidation.requireActive(cancellation);
        String content = cachedContent;
        if (content == null) {
            synchronized (this) {
                content = cachedContent;
                if (content == null) {
                    content = SkillRendering.codeDefinedContent(frontmatter, instructions, resources, scripts);
                    cachedContent = content;
                }
            }
        }
        return CompletableFuture.completedFuture(content);
    }

    @Override
    public CompletionStage<SkillResource> resourceAsync(String name, RunCancellation cancellation) {
        return SkillCollections.resource(resources, name, cancellation);
    }

    @Override
    public CompletionStage<SkillScript> scriptAsync(String name, RunCancellation cancellation) {
        return SkillCollections.script(scripts, name, cancellation);
    }

    /** Builds an immutable inline skill. */
    public static final class Builder {
        private final SkillFrontmatter frontmatter;
        private final String instructions;
        private final List<SkillResource> resources = new ArrayList<>();
        private final List<SkillScript> scripts = new ArrayList<>();

        private Builder(SkillFrontmatter frontmatter, String instructions) {
            this.frontmatter = Objects.requireNonNull(frontmatter, "frontmatter");
            this.instructions = requireNonBlank(instructions, "instructions");
        }

        /**
         * Adds one resource.
         *
         * @param resource resource
         * @return this builder
         */
        public Builder resource(SkillResource resource) {
            resources.add(Objects.requireNonNull(resource, "resource"));
            return this;
        }

        /**
         * Adds one script.
         *
         * @param script script
         * @return this builder
         */
        public Builder script(SkillScript script) {
            scripts.add(Objects.requireNonNull(script, "script"));
            return this;
        }

        /**
         * Builds the skill.
         *
         * @return inline skill
         */
        public InlineSkill build() {
            return new InlineSkill(this);
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
