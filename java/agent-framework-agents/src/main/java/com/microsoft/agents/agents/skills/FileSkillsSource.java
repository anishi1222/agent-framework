// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.RunCancellation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Discovers file-backed skills from one or more directory trees. */
public final class FileSkillsSource implements SkillsSource {
    /** Required skill definition file name. */
    public static final String SKILL_FILE_NAME = "SKILL.md";

    /** Fixed maximum depth used when discovering skill roots. */
    public static final int MAX_SKILL_DISCOVERY_DEPTH = 2;

    private final List<Path> roots;
    private final FileSkillsSourceOptions options;

    /**
     * Creates a source for one directory with default options.
     *
     * @param root skill directory or parent directory
     */
    public FileSkillsSource(Path root) {
        this(List.of(root), FileSkillsSourceOptions.defaults());
    }

    /**
     * Creates a configured file source.
     *
     * @param roots skill directories or parent directories
     * @param options discovery options
     */
    public FileSkillsSource(List<Path> roots, FileSkillsSourceOptions options) {
        Objects.requireNonNull(roots, "roots");
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("roots must not be empty.");
        }
        this.roots = roots.stream()
                .map(root ->
                        Objects.requireNonNull(root, "root").toAbsolutePath().normalize())
                .toList();
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public CompletionStage<List<Skill>> getSkillsAsync(SkillsSourceContext context, RunCancellation cancellation) {
        Objects.requireNonNull(context, "context");
        SkillValidation.requireActive(cancellation);
        CompletableFuture<List<Skill>> result = new CompletableFuture<>();
        Thread.ofVirtual().name("agent-framework-file-skills").start(() -> {
            try {
                result.complete(discover(cancellation));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result.minimalCompletionStage();
    }

    private List<Skill> discover(RunCancellation cancellation) {
        ArrayList<Path> directories = new ArrayList<>();
        for (Path root : roots) {
            SkillValidation.requireActive(cancellation);
            discoverSkillDirectories(root, 0, directories);
        }
        directories.sort(Comparator.comparing(Path::toString));
        LinkedHashMap<String, Skill> byName = new LinkedHashMap<>();
        for (Path directory : directories) {
            SkillValidation.requireActive(cancellation);
            try {
                FileSkill skill = loadSkill(directory);
                byName.putIfAbsent(SkillValidation.caseKey(skill.frontmatter().name()), skill);
            } catch (IllegalArgumentException | IOException ignored) {
                // Invalid or unreadable skill roots are omitted without making unrelated skills unavailable.
            }
        }
        return List.copyOf(byName.values());
    }

    private void discoverSkillDirectories(Path directory, int depth, List<Path> result) {
        if (depth > MAX_SKILL_DISCOVERY_DEPTH
                || Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path skillFile = findSkillFile(directory);
        if (skillFile != null) {
            result.add(directory);
            return;
        }
        if (depth == MAX_SKILL_DISCOVERY_DEPTH) {
            return;
        }
        for (Path child : children(directory)) {
            if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                discoverSkillDirectories(child, depth + 1, result);
            }
        }
    }

    private FileSkill loadSkill(Path directory) throws IOException {
        Path skillFile = findSkillFile(directory);
        if (skillFile == null) {
            throw new IllegalArgumentException("Skill directory does not contain SKILL.md.");
        }
        String raw = Files.readString(skillFile, StandardCharsets.UTF_8);
        SkillFrontmatter frontmatter = SkillFrontmatterParser.parse(raw);
        if (!frontmatter.name().equals(directory.getFileName().toString())) {
            throw new IllegalArgumentException("Skill name must match its directory name.");
        }
        ArrayList<SkillResource> resources = new ArrayList<>();
        ArrayList<SkillScript> scripts = new ArrayList<>();
        scanSkillFiles(directory, directory, 1, frontmatter.name(), resources, scripts);
        resources.sort(Comparator.comparing(SkillResource::name));
        scripts.sort(Comparator.comparing(SkillScript::name));
        return new FileSkill(frontmatter, raw, directory, resources, scripts);
    }

    private void scanSkillFiles(
            Path skillRoot,
            Path directory,
            int depth,
            String skillName,
            List<SkillResource> resources,
            List<SkillScript> scripts) {
        if (depth > options.searchDepth()
                || Files.isSymbolicLink(directory)
                || !directory.normalize().startsWith(skillRoot)) {
            return;
        }
        ArrayList<Path> subdirectories = new ArrayList<>();
        for (Path entry : children(directory)) {
            if (Files.isSymbolicLink(entry)) {
                continue;
            }
            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                subdirectories.add(entry);
                continue;
            }
            if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                    || entry.getFileName().toString().equalsIgnoreCase(SKILL_FILE_NAME)) {
                continue;
            }
            String relative = skillRoot.relativize(entry).toString().replace('\\', '/');
            String extension = extension(entry);
            if (options.resourceExtensions().contains(extension)
                    && (options.resourceFilter() == null
                            || options.resourceFilter().test(skillName, relative))) {
                resources.add(new FileSkillResource(relative, skillRoot, entry));
            }
            if (options.scriptExtensions().contains(extension)
                    && (options.scriptFilter() == null || options.scriptFilter().test(skillName, relative))) {
                scripts.add(new FileSkillScript(relative, entry.toAbsolutePath().normalize(), options.scriptRunner()));
            }
        }
        if (depth < options.searchDepth()) {
            subdirectories.sort(Comparator.comparing(Path::toString));
            subdirectories.forEach(
                    subdirectory -> scanSkillFiles(skillRoot, subdirectory, depth + 1, skillName, resources, scripts));
        }
    }

    private static Path findSkillFile(Path directory) {
        for (Path child : children(directory)) {
            if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)
                    && child.getFileName().toString().equalsIgnoreCase(SKILL_FILE_NAME)) {
                return child;
            }
        }
        return null;
    }

    private static List<Path> children(Path directory) {
        ArrayList<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            stream.forEach(children::add);
        } catch (IOException ignored) {
            return List.of();
        }
        children.sort(Comparator.comparing(Path::toString));
        return List.copyOf(children);
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int separator = name.lastIndexOf('.');
        return separator < 0 ? "" : name.substring(separator).toLowerCase(Locale.ROOT);
    }
}
