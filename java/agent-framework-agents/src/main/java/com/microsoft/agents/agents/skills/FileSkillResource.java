// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.RunCancellation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class FileSkillResource implements SkillResource {
    private final String name;
    private final Path skillRoot;
    private final Path fullPath;

    FileSkillResource(String name, Path skillRoot, Path fullPath) {
        this.name = SkillValidation.requireRelativeName(name, "resource");
        this.skillRoot = skillRoot.toAbsolutePath().normalize();
        this.fullPath = fullPath.toAbsolutePath().normalize();
        validate();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return null;
    }

    @Override
    public CompletionStage<SkillResourceContent> readAsync(RunCancellation cancellation) {
        SkillValidation.requireActive(cancellation);
        CompletableFuture<SkillResourceContent> result = new CompletableFuture<>();
        Thread.ofVirtual().name("agent-framework-skill-resource").start(() -> {
            try {
                SkillValidation.requireActive(cancellation);
                validate();
                result.complete(new SkillResourceContent.Text(Files.readString(fullPath, StandardCharsets.UTF_8)));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result.minimalCompletionStage();
    }

    private void validate() {
        if (!fullPath.startsWith(skillRoot)) {
            throw new IllegalArgumentException("Resource resolves outside the skill directory.");
        }
        if (!Files.isRegularFile(fullPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Resource file does not exist.");
        }
        Path current = skillRoot;
        for (Path segment : skillRoot.relativize(fullPath)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Resource path must not contain symbolic links.");
            }
        }
    }
}
