// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.RunCancellation;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Represents a skill discovered from a directory containing {@code SKILL.md}. */
public final class FileSkill implements Skill {
    private final SkillFrontmatter frontmatter;
    private final String rawContent;
    private final Path path;
    private final List<SkillResource> resources;
    private final List<SkillScript> scripts;
    private volatile String cachedContent;

    /**
     * Creates a validated file-backed skill.
     *
     * @param frontmatter parsed skill metadata
     * @param rawContent full raw {@code SKILL.md} content
     * @param path absolute skill directory
     * @param resources discovered resources
     * @param scripts discovered scripts
     */
    public FileSkill(
            SkillFrontmatter frontmatter,
            String rawContent,
            Path path,
            List<? extends SkillResource> resources,
            List<? extends SkillScript> scripts) {
        this.frontmatter = Objects.requireNonNull(frontmatter, "frontmatter");
        this.rawContent = Objects.requireNonNull(rawContent, "rawContent");
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.resources = SkillCollections.resources(List.copyOf(resources));
        this.scripts = SkillCollections.scripts(List.copyOf(scripts));
    }

    @Override
    public SkillFrontmatter frontmatter() {
        return frontmatter;
    }

    /**
     * Returns the absolute skill directory.
     *
     * @return skill directory
     */
    public Path path() {
        return path;
    }

    @Override
    public CompletionStage<String> contentAsync(RunCancellation cancellation) {
        SkillValidation.requireActive(cancellation);
        String content = cachedContent;
        if (content == null) {
            synchronized (this) {
                content = cachedContent;
                if (content == null) {
                    content = SkillRendering.fileContent(rawContent, resources, scripts);
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
}
