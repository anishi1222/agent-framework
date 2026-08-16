// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.StateValue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSkillsSourceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void getSkillsAsync_shouldDiscoverValidatedResourcesScriptsAndBlockScalars() throws IOException {
        Path skillDirectory = Files.createDirectories(temporaryDirectory.resolve("document-review"));
        Files.writeString(skillDirectory.resolve("SKILL.md"), """
                ---
                name: document-review
                description: >
                  Reviews documents
                  for correctness.
                compatibility: |
                  Java 25
                  POSIX
                metadata:
                  owner: framework
                ---
                # Document review
                Follow the checklist.
                """);
        Files.writeString(skillDirectory.resolve("guide.md"), "guide");
        Path scripts = Files.createDirectories(skillDirectory.resolve("scripts"));
        Files.writeString(scripts.resolve("review.py"), "print('ok')");
        Files.writeString(scripts.resolve("ignored.sh"), "echo ignored");
        FileSkillsSourceOptions options = FileSkillsSourceOptions.builder()
                .scriptRunner((skill, script, arguments, cancellation) ->
                        CompletableFuture.completedFuture(StateValue.string(script.name())))
                .build();
        FileSkillsSource source = new FileSkillsSource(List.of(temporaryDirectory), options);

        List<Skill> skills = source.getSkillsAsync(SkillsTestContexts.context("files"), new DefaultRunCancellation())
                .toCompletableFuture()
                .join();
        FileSkill skill = (FileSkill) skills.getFirst();
        SkillResource resource = skill.resourceAsync("GUIDE.MD", new DefaultRunCancellation())
                .toCompletableFuture()
                .join();
        SkillScript script = skill.scriptAsync("SCRIPTS/REVIEW.PY", new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        assertThat(skills).hasSize(1);
        assertThat(skill.frontmatter().description()).isEqualTo("Reviews documents for correctness.");
        assertThat(skill.frontmatter().compatibility()).isEqualTo("Java 25\nPOSIX\n");
        assertThat(skill.frontmatter().metadata()).containsEntry("owner", "framework");
        assertThat(resource.readAsync(new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isEqualTo(new SkillResourceContent.Text("guide"));
        assertThat(script.runAsync(skill, StateValue.array(List.of()), new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isEqualTo(StateValue.string("scripts/review.py"));
        assertThat(skill.contentAsync(new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .contains("<resource name=\"guide.md\"/>")
                .contains("<script name=\"scripts/review.py\">");
    }

    @Test
    void getSkillsAsync_shouldSkipNameMismatchNestedSkillsAndSymlinkResources() throws IOException {
        Path outer = Files.createDirectories(temporaryDirectory.resolve("outer"));
        Files.writeString(outer.resolve("SKILL.md"), """
                ---
                name: outer
                description: Outer skill.
                ---
                Outer.
                """);
        Path nested = Files.createDirectories(outer.resolve("nested"));
        Files.writeString(nested.resolve("SKILL.md"), """
                ---
                name: nested
                description: Nested definition belongs to outer.
                ---
                Nested.
                """);
        Path mismatch = Files.createDirectories(temporaryDirectory.resolve("wrong-directory"));
        Files.writeString(mismatch.resolve("SKILL.md"), """
                ---
                name: another-name
                description: Invalid directory binding.
                ---
                Invalid.
                """);
        Path outside = Files.writeString(temporaryDirectory.resolve("outside.txt"), "secret");
        boolean symlinkCreated;
        try {
            Files.createSymbolicLink(outer.resolve("linked.txt"), outside);
            symlinkCreated = true;
        } catch (UnsupportedOperationException | IOException exception) {
            symlinkCreated = false;
        }
        FileSkillsSource source = new FileSkillsSource(temporaryDirectory);

        List<Skill> skills = source.getSkillsAsync(SkillsTestContexts.context("files"), new DefaultRunCancellation())
                .toCompletableFuture()
                .join();
        FileSkill skill = (FileSkill) skills.getFirst();

        assertThat(skills).extracting(value -> value.frontmatter().name()).containsExactly("outer");
        assertThat(skill.resourceAsync("nested/SKILL.md", new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isNull();
        if (symlinkCreated) {
            assertThat(skill.resourceAsync("linked.txt", new DefaultRunCancellation())
                            .toCompletableFuture()
                            .join())
                    .isNull();
        }
    }
}
