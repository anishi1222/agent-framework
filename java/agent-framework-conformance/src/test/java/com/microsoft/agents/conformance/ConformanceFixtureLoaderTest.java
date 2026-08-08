// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

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
        assertThat(catalog.cases()).hasSize(35);
        assertThat(toolCases).hasSize(13);
        assertThat(workflowCases).hasSize(4);
        assertThat(catalog.byKind(FixtureKind.WORKFLOW_CHECKPOINT)).hasSize(1);
        assertThat(catalog.requireCase("JCF-SESSIONS-001")).isInstanceOf(SnapshotFixture.class);
        assertThat(catalog.requireCase("JCF-WORKFLOWS-005")).isInstanceOf(WorkflowCheckpointFixture.class);
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

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/conformance/v1/core/example.json",
                "C:\\conformance\\v1\\core\\example.json",
                "conformance\\v1\\core\\example.json",
                "conformance/v1/./core/example.json",
                "conformance/v1/core/../secret.json",
                "conformance/v1/core//example.json",
                "fixtures/v1/core/example.json"
            })
    void load_shouldRejectUnsafeFixturePathsBeforeResolverAccess(String fixturePath) {
        // Arrange
        AtomicInteger resolverCalls = new AtomicInteger();
        ByteArrayInputStream manifest =
                new ByteArrayInputStream(manifestJson(fixturePath).getBytes(StandardCharsets.UTF_8));

        // Act and assert
        assertThatThrownBy(() -> new ConformanceFixtureLoader().load(manifest, ignored -> {
                    resolverCalls.incrementAndGet();
                    return null;
                }))
                .isInstanceOf(ConformanceValidationException.class)
                .hasMessageContaining("Invalid fixture resource path");
        assertThat(resolverCalls).hasValue(0);
    }

    @Test
    void load_shouldRejectDuplicateFixturePathsBeforeResolverAccess() {
        // Arrange
        AtomicInteger resolverCalls = new AtomicInteger();
        String first = manifestCaseJson("JCF-CORE-001", "conformance/v1/core/example.json");
        String second = manifestCaseJson("JCF-CORE-002", "conformance/v1/core/example.json");
        ByteArrayInputStream manifest = new ByteArrayInputStream(
                ("{\"schemaVersion\":1,\"cases\":[" + first + "," + second + "]}").getBytes(StandardCharsets.UTF_8));

        // Act and assert
        assertThatThrownBy(() -> new ConformanceFixtureLoader().load(manifest, ignored -> {
                    resolverCalls.incrementAndGet();
                    return null;
                }))
                .isInstanceOf(ConformanceValidationException.class)
                .hasMessageContaining("Duplicate fixture registration");
        assertThat(resolverCalls).hasValue(0);
    }

    @Test
    void load_shouldAllowValidCustomResolverWithinFixtureRoot() {
        // Arrange
        String fixturePath = "conformance/v1/core/jcf-core-001-message-content.json";
        AtomicInteger resolverCalls = new AtomicInteger();
        ByteArrayInputStream manifest =
                new ByteArrayInputStream(manifestJson(fixturePath).getBytes(StandardCharsets.UTF_8));

        // Act
        ConformanceFixtureCatalog catalog = new ConformanceFixtureLoader().load(manifest, resource -> {
            resolverCalls.incrementAndGet();
            assertThat(resource).isEqualTo(fixturePath);
            return ConformanceFixtureLoaderTest.class.getClassLoader().getResourceAsStream(resource);
        });

        // Assert
        assertThat(resolverCalls).hasValue(1);
        assertThat(catalog.requireCase("JCF-CORE-001")).isInstanceOf(BehaviorFixture.class);
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

    private static String manifestJson(String fixturePath) {
        return "{\"schemaVersion\":1,\"cases\":[" + manifestCaseJson("JCF-CORE-001", fixturePath) + "]}";
    }

    private static String manifestCaseJson(String caseId, String fixturePath) {
        String escapedPath = fixturePath.replace("\\", "\\\\").replace("\"", "\\\"");
        return """
                {"caseId":"%s","suiteId":"JCF-CORE","matrixStatus":"initial-scope",
                 "matrixAreas":["Chat message / content"],"fixture":"%s",
                 "kind":"message-content","sourceReferences":["example"]}
                """.formatted(caseId, escapedPath);
    }

    private static final class ShadowManifestClassLoader extends ClassLoader {
        private ShadowManifestClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (ConformanceFixtureLoader.DEFAULT_MANIFEST_RESOURCE.equals(name)) {
                return new ByteArrayInputStream("{\"schemaVersion\":2,\"cases\":[]}".getBytes(StandardCharsets.UTF_8));
            }
            return super.getResourceAsStream(name);
        }
    }
}
