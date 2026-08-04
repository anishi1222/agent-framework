// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConformanceFixtureLoaderTest {
    @Test
    void loadDefault_shouldIgnoreShadowManifestFromContextClassLoader() {
        // Arrange
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader shadowLoader = new ShadowManifestClassLoader(original);
        Thread.currentThread().setContextClassLoader(shadowLoader);

        try {
            // Act
            ConformanceFixtureCatalog catalog = new ConformanceFixtureLoader().loadDefault();

            // Assert
            assertThat(catalog.requireCase("JCF-CORE-001")).isInstanceOf(BehaviorFixture.class);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void loadDefault_shouldIndexCasesBySuiteAndKind() {
        // Arrange
        ConformanceFixtureCatalog catalog = new ConformanceFixtureLoader().loadDefault();

        // Act
        List<ConformanceFixture> toolCases = catalog.bySuite("JCF-TOOLS");
        List<ConformanceFixture> workflowCases = catalog.byKind(FixtureKind.WORKFLOW_TRACE);

        // Assert
        assertThat(catalog.cases()).hasSize(26);
        assertThat(toolCases).hasSize(8);
        assertThat(workflowCases).hasSize(4);
        assertThat(catalog.requireCase("JCF-SESSIONS-001")).isInstanceOf(SnapshotFixture.class);
    }

    @Test
    void requireCase_shouldRejectUnknownCaseId() {
        // Arrange
        ConformanceFixtureCatalog catalog = new ConformanceFixtureLoader().loadDefault();

        // Act and assert
        assertThatThrownBy(() -> catalog.requireCase("JCF-CORE-999"))
                .isInstanceOf(ConformanceValidationException.class)
                .hasMessageContaining("is not registered");
    }

    @Test
    void loadDefault_shouldProduceDeterministicCatalogOrderAndValues() {
        // Arrange
        ConformanceFixtureLoader loader = new ConformanceFixtureLoader();

        // Act
        ConformanceFixtureCatalog first = loader.loadDefault();
        ConformanceFixtureCatalog second = loader.loadDefault();

        // Assert
        assertThat(first.manifest()).isEqualTo(second.manifest());
        assertThat(first.cases()).containsExactlyEntriesOf(second.cases());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidManifests")
    void load_shouldRejectUnknownManifestSchemaOrKind(String name, String json, String expectedMessage) {
        // Arrange
        ByteArrayInputStream manifest = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));

        // Act and assert
        assertThatThrownBy(() -> new ConformanceFixtureLoader().load(manifest, ignored -> null))
                .isInstanceOf(ConformanceValidationException.class)
                .hasMessageContaining(expectedMessage);
    }

    @Test
    void manifest_shouldRejectDuplicateCaseIds() {
        // Arrange
        ManifestCase manifestCase = new ManifestCase(
                "JCF-CORE-001",
                "JCF-CORE",
                "initial-scope",
                List.of("Chat message / content"),
                "conformance/v1/core/example.json",
                FixtureKind.CONTRACT,
                List.of("example"));

        // Act and assert
        assertThatThrownBy(() -> new ConformanceManifest(1, List.of(manifestCase, manifestCase)))
                .isInstanceOf(ConformanceValidationException.class)
                .hasMessageContaining("Duplicate manifest caseId");
    }

    private static Stream<Arguments> invalidManifests() {
        String caseTemplate = """
                {"caseId":"JCF-CORE-001","suiteId":"JCF-CORE","matrixStatus":"initial-scope",
                 "matrixAreas":["Chat message / content"],"fixture":"conformance/v1/core/example.json",
                 "kind":"%s","sourceReferences":["example"]}
                """;
        return Stream.of(
                Arguments.of(
                        "manifest-version",
                        "{\"schemaVersion\":2,\"cases\":[" + caseTemplate.formatted("contract") + "]}",
                        "Unsupported manifest schemaVersion"),
                Arguments.of(
                        "manifest-kind",
                        "{\"schemaVersion\":1,\"cases\":[" + caseTemplate.formatted("javaClass") + "]}",
                        "Unknown fixture kind"));
    }

    private static final class ShadowManifestClassLoader extends ClassLoader {
        private ShadowManifestClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (ConformanceFixtureLoader.DEFAULT_MANIFEST_RESOURCE.equals(name)) {
                return new ByteArrayInputStream(
                        "{\"schemaVersion\":2,\"cases\":[]}".getBytes(StandardCharsets.UTF_8));
            }
            return super.getResourceAsStream(name);
        }
    }
}
