// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConformanceManifestCoverageTest {
    private static final Pattern CASE_ID = Pattern.compile("JCF-[A-Z]+(?:-[A-Z]+)*-[0-9]{3}");

    private static final String INITIAL_SCOPE = "`initial-scope`";

    private final ConformanceFixtureCatalog catalog = new ConformanceFixtureLoader().loadDefault();

    @Test
    void initialScopeMatrixRows_shouldReferenceOnlyRegisteredManifestCases() throws IOException {
        // Arrange
        Path matrixPath = Path.of(System.getProperty("conformance.matrix.path"));
        Map<String, Set<String>> matrixCasesByArea = readInitialScopeCases(matrixPath);
        Map<String, Set<String>> manifestCasesByArea = manifestCasesByArea();

        // Act and assert
        assertThat(matrixCasesByArea).hasSize(33);
        assertThat(matrixCasesByArea).isEqualTo(manifestCasesByArea);
    }

    @Test
    void fixtureDirectory_shouldMatchManifestRegistrationsExactly() throws IOException {
        // Arrange
        Path resourceRoot = Path.of(System.getProperty("conformance.fixture.source.dir"));
        Path fixtureRoot = resourceRoot.resolve("conformance/v1");
        Set<String> registered = catalog.manifest().cases().stream()
                .map(ManifestCase::fixture)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Act
        Set<String> files;
        try (var paths = Files.walk(fixtureRoot)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .map(resourceRoot::relativize)
                    .map(path -> path.toString().replace(path.getFileSystem().getSeparator(), "/"))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        // Assert
        assertThat(files).containsExactlyInAnyOrderElementsOf(registered);
    }

    @Test
    void stableSuiteIds_shouldBeIndexedForInitialImplementationAreas() {
        // Act
        Set<String> suiteIds =
                catalog.manifest().cases().stream().map(ManifestCase::suiteId).collect(Collectors.toSet());

        // Assert
        assertThat(suiteIds)
                .contains(
                        "JCF-CORE",
                        "JCF-TOOLS",
                        "JCF-AGENTS",
                        "JCF-SESSIONS",
                        "JCF-WORKFLOWS",
                        "JCF-ORCHESTRATIONS",
                        "JCF-PROTOCOLS",
                        "JCF-HOSTING",
                        "JCF-PROVIDERS");
    }

    @Test
    void initialScopeMatrixRows_shouldRejectDuplicateAreaLabels(@TempDir Path temporaryDirectory) throws IOException {
        // Arrange
        Path matrix = temporaryDirectory.resolve("matrix.md");
        Files.writeString(matrix, """
                ## 1. Core Abstractions
                | Area / Group | .NET | Python | Java | Contract | Status |
                |---|---|---|---|---|---|
                | Duplicate area | a | b | c | `JCF-CORE-001` | `initial-scope` |
                | Duplicate area | a | b | c | `JCF-CORE-002` | `initial-scope` |
                ## SDK Classification Audit
                """);

        // Act and assert
        assertThatThrownBy(() -> readInitialScopeCases(matrix))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("duplicate initial-scope matrix area 'Duplicate area'");
    }

    static Map<String, Set<String>> readInitialScopeCases(Path matrixPath) throws IOException {
        LinkedHashMap<String, Set<String>> result = new LinkedHashMap<>();
        boolean inFeatureTables = false;
        for (String line : Files.readAllLines(matrixPath)) {
            if (line.startsWith("## 1. Core Abstractions")) {
                inFeatureTables = true;
            } else if (line.startsWith("## SDK Classification Audit")) {
                break;
            }
            if (!inFeatureTables) {
                continue;
            }
            if (!line.startsWith("|") || !line.contains(INITIAL_SCOPE)) {
                continue;
            }
            String[] columns = line.split("\\|", -1);
            assertThat(columns).as("matrix row columns: %s", line).hasSizeGreaterThan(6);
            String area = columns[1].trim();
            LinkedHashSet<String> caseIds = new LinkedHashSet<>();
            Matcher matcher = CASE_ID.matcher(line);
            while (matcher.find()) {
                caseIds.add(matcher.group());
            }
            assertThat(caseIds)
                    .as("initial-scope matrix area '%s' must name at least one concrete JCF case", area)
                    .isNotEmpty();
            if (result.containsKey(area)) {
                throw new AssertionError("duplicate initial-scope matrix area '" + area + "'");
            }
            result.put(area, Set.copyOf(caseIds));
        }
        return result;
    }

    private Map<String, Set<String>> manifestCasesByArea() {
        LinkedHashMap<String, List<String>> mutable = new LinkedHashMap<>();
        catalog.manifest().cases().stream()
                .filter(manifestCase -> "initial-scope".equals(manifestCase.matrixStatus()))
                .forEach(manifestCase -> manifestCase.matrixAreas().forEach(area -> mutable.computeIfAbsent(
                                area, ignored -> new ArrayList<>())
                        .add(manifestCase.caseId())));
        LinkedHashMap<String, Set<String>> result = new LinkedHashMap<>();
        mutable.forEach((area, cases) -> result.put(area, Set.copyOf(cases)));
        return result;
    }
}
