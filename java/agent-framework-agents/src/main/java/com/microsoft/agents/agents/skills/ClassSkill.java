// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.RunCancellation;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Provides a reusable subclass-oriented skill definition.
 *
 * <p>Subclasses override {@link #instructions()}, {@link #resources()}, and {@link #scripts()}.
 * Their first resolved values are cached for the skill lifetime.
 */
public abstract class ClassSkill implements Skill {
    private final SkillFrontmatter frontmatter;
    private volatile Snapshot snapshot;

    /**
     * Creates a class-defined skill.
     *
     * @param frontmatter skill metadata
     */
    protected ClassSkill(SkillFrontmatter frontmatter) {
        this.frontmatter = Objects.requireNonNull(frontmatter, "frontmatter");
    }

    @Override
    public final SkillFrontmatter frontmatter() {
        return frontmatter;
    }

    /**
     * Returns the skill instructions.
     *
     * @return non-blank instructions
     */
    protected abstract String instructions();

    /**
     * Returns class-defined resources.
     *
     * @return immutable or caller-owned resource list
     */
    protected List<? extends SkillResource> resources() {
        return List.of();
    }

    /**
     * Returns class-defined scripts.
     *
     * @return immutable or caller-owned script list
     */
    protected List<? extends SkillScript> scripts() {
        return List.of();
    }

    @Override
    public final CompletionStage<String> contentAsync(RunCancellation cancellation) {
        SkillValidation.requireActive(cancellation);
        return CompletableFuture.completedFuture(snapshot().content());
    }

    @Override
    public final CompletionStage<SkillResource> resourceAsync(String name, RunCancellation cancellation) {
        return SkillCollections.resource(snapshot().resources(), name, cancellation);
    }

    @Override
    public final CompletionStage<SkillScript> scriptAsync(String name, RunCancellation cancellation) {
        return SkillCollections.script(snapshot().scripts(), name, cancellation);
    }

    private Snapshot snapshot() {
        Snapshot value = snapshot;
        if (value == null) {
            synchronized (this) {
                value = snapshot;
                if (value == null) {
                    String skillInstructions = Objects.requireNonNull(instructions(), "instructions");
                    if (skillInstructions.isBlank()) {
                        throw new IllegalStateException("instructions must not be blank.");
                    }
                    List<SkillResource> skillResources = SkillCollections.resources(List.copyOf(resources()));
                    List<SkillScript> skillScripts = SkillCollections.scripts(List.copyOf(scripts()));
                    value = new Snapshot(
                            skillResources,
                            skillScripts,
                            SkillRendering.codeDefinedContent(
                                    frontmatter, skillInstructions, skillResources, skillScripts));
                    snapshot = value;
                }
            }
        }
        return value;
    }

    private record Snapshot(List<SkillResource> resources, List<SkillScript> scripts, String content) {}
}
