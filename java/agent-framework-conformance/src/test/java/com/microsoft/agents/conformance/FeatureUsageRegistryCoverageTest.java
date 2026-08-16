// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class FeatureUsageRegistryCoverageTest {
    private static final Pattern REGISTRY_ROW = Pattern.compile("^\\| (\\d+) \\| `([^`]+)` \\|", Pattern.MULTILINE);

    private static final Pattern DECLARATION = Pattern.compile(
            "static\\s+final\\s+FeatureUsageIndex\\s+([A-Z0-9_]+)\\s*=\\s*"
                    + "new\\s+FeatureUsageIndex\\(\\s*(\\d+)\\s*,\\s*\"([^\"]+)\"\\s*\\)",
            Pattern.MULTILINE);

    @Test
    void javaFeatureIndexes_shouldExactlyMatchPublishedRegistryAndBeReferenced() throws IOException {
        // Arrange
        Path matrixPath = Path.of(System.getProperty("conformance.matrix.path"));
        Path repositoryRoot = matrixPath.getParent().getParent().getParent();
        Path registryPath = repositoryRoot.resolve("docs/specs/feature-usage-bit-registry.md");
        Set<RegistryEntry> documented = documentedJavaEntries(registryPath);

        // Act
        LinkedHashMap<Integer, Declaration> declarationsByIndex = new LinkedHashMap<>();
        try (var paths = Files.walk(repositoryRoot.resolve("java"))) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(FeatureUsageRegistryCoverageTest::isMainJavaSource)
                    .forEach(path -> collectDeclarations(path, declarationsByIndex));
        }
        Set<RegistryEntry> declared = declarationsByIndex.values().stream()
                .map(Declaration::entry)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        // Assert
        assertThat(documented).isNotEmpty();
        assertThat(declared).containsExactlyInAnyOrderElementsOf(documented);
        assertThat(declarationsByIndex.keySet()).allMatch(index -> index >= 0 && index < 128);
    }

    private static Set<RegistryEntry> documentedJavaEntries(Path registryPath) throws IOException {
        String registry = Files.readString(registryPath);
        String javaSection = registry.split("## Index table — Java", 2)[1].split("## Opt-out", 2)[0];
        LinkedHashSet<RegistryEntry> entries = new LinkedHashSet<>();
        LinkedHashMap<Integer, String> idsByIndex = new LinkedHashMap<>();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Matcher matcher = REGISTRY_ROW.matcher(javaSection);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            String id = matcher.group(2);
            String previous = idsByIndex.putIfAbsent(index, id);
            assertThat(previous)
                    .as("duplicate Java registry index %s for %s and %s", index, previous, id)
                    .isNull();
            assertThat(entries.add(new RegistryEntry(index, id)))
                    .as("duplicate Java registry entry %s:%s", index, id)
                    .isTrue();
            assertThat(ids.add(id)).as("duplicate Java registry id %s", id).isTrue();
        }
        return entries;
    }

    private static void collectDeclarations(Path path, Map<Integer, Declaration> declarationsByIndex) {
        String source;
        try {
            source = Files.readString(path);
        } catch (IOException failure) {
            throw new AssertionError("Unable to read " + path, failure);
        }
        Matcher matcher = DECLARATION.matcher(source);
        while (matcher.find()) {
            String constant = matcher.group(1);
            int index = Integer.parseInt(matcher.group(2));
            String id = matcher.group(3);
            long references = countOwningPackageReferences(path, constant);
            assertThat(references)
                    .as("%s:%s must be referenced by its owning package", path, constant)
                    .isGreaterThan(1);
            Declaration declaration = new Declaration(new RegistryEntry(index, id), path, constant);
            Declaration previous = declarationsByIndex.putIfAbsent(index, declaration);
            assertThat(previous)
                    .as("feature index %s overlaps between %s and %s", index, previous, declaration)
                    .isNull();
        }
    }

    private static long countOwningPackageReferences(Path declarationPath, String constant) {
        Path sourceDirectory = declarationPath;
        while (sourceDirectory != null
                && !"src".equals(sourceDirectory.getFileName().toString())) {
            sourceDirectory = sourceDirectory.getParent();
        }
        if (sourceDirectory == null) {
            throw new AssertionError("Unable to locate source root for " + declarationPath);
        }
        Pattern reference = Pattern.compile("\\b" + Pattern.quote(constant) + "\\b");
        try (var paths = Files.walk(sourceDirectory.resolve("main/java"))) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .mapToLong(path -> {
                        try {
                            return reference
                                    .matcher(Files.readString(path))
                                    .results()
                                    .count();
                        } catch (IOException failure) {
                            throw new AssertionError("Unable to read " + path, failure);
                        }
                    })
                    .sum();
        } catch (IOException failure) {
            throw new AssertionError("Unable to inspect " + sourceDirectory, failure);
        }
    }

    private static boolean isMainJavaSource(Path path) {
        for (int index = 0; index + 2 < path.getNameCount(); index++) {
            if ("src".equals(path.getName(index).toString())
                    && "main".equals(path.getName(index + 1).toString())
                    && "java".equals(path.getName(index + 2).toString())) {
                return true;
            }
        }
        return false;
    }

    private record RegistryEntry(int index, String id) {}

    private record Declaration(RegistryEntry entry, Path path, String constant) {}
}
