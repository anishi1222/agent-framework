// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AzureFoundryContractFixtureTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConformanceFixtureLoader loader = new ConformanceFixtureLoader();

    @Test
    void manifest_shouldBindEveryAzureFoundryContractToItsExactFixture() {
        ConformanceFixtureCatalog catalog = loader.loadDefault();

        assertThat(List.of(
                        catalog.requireCase("JCF-PROVIDERS-009"),
                        catalog.requireCase("JCF-HOSTING-004"),
                        catalog.requireCase("JCF-INTEGRATIONS-001"),
                        catalog.requireCase("JCF-INTEGRATIONS-002"),
                        catalog.requireCase("JCF-INTEGRATIONS-003"),
                        catalog.requireCase("JCF-INTEGRATIONS-004"),
                        catalog.requireCase("JCF-INTEGRATIONS-005"),
                        catalog.requireCase("JCF-INTEGRATIONS-006")))
                .allSatisfy(fixture -> {
                    assertThat(fixture).isInstanceOf(BehaviorFixture.class);
                    assertThat(fixture.kind()).isEqualTo(FixtureKind.CONTRACT);
                    BehaviorFixture behavior = (BehaviorFixture) fixture;
                    assertThat(behavior.input().values()).isNotEmpty();
                    assertThat(behavior.expected().values()).isNotEmpty();
                });
        assertThat(catalog.manifest().cases())
                .filteredOn(manifestCase -> manifestCase.caseId().equals("JCF-PROVIDERS-009"))
                .singleElement()
                .extracting(ManifestCase::fixture)
                .isEqualTo("conformance/v1/providers/jcf-providers-009-azure-ai-persistent.json");
        assertThat(catalog.manifest().cases())
                .filteredOn(manifestCase -> manifestCase.caseId().startsWith("JCF-INTEGRATIONS-"))
                .extracting(ManifestCase::fixture)
                .containsExactly(
                        "conformance/v1/integrations/jcf-integrations-001-content-understanding.json",
                        "conformance/v1/integrations/jcf-integrations-002-purview.json",
                        "conformance/v1/integrations/jcf-integrations-003-foundry-evaluations.json",
                        "conformance/v1/integrations/jcf-integrations-004-cosmos-memory.json",
                        "conformance/v1/integrations/jcf-integrations-005-mem0.json",
                        "conformance/v1/integrations/jcf-integrations-006-azure-ai-search.json");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidContracts")
    void contractSchema_shouldRejectDriftFromVerifiedAzureFoundryValues(
            String name, String resource, java.util.function.Consumer<ObjectNode> mutation, String expectedMessage)
            throws Exception {
        ObjectNode root;
        try (var input = AzureFoundryContractFixtureTest.class.getClassLoader().getResourceAsStream(resource)) {
            root = (ObjectNode) MAPPER.readTree(java.util.Objects.requireNonNull(input));
        }
        mutation.accept(root);
        byte[] bytes = MAPPER.writeValueAsBytes(root);

        assertThatThrownBy(() -> loader.loadFixture(new ByteArrayInputStream(bytes), name))
                .isInstanceOf(ConformanceValidationException.class)
                .hasMessageContaining(expectedMessage);
    }

    private static Stream<Arguments> invalidContracts() {
        return Stream.of(
                Arguments.of(
                        "persistent-version",
                        "conformance/v1/providers/jcf-providers-009-azure-ai-persistent.json",
                        mutation("input", "serviceApiVersion", "2025-05-01"),
                        "2025-05-15-preview"),
                Arguments.of(
                        "foundry-hosting-principal-binding",
                        "conformance/v1/hosting/jcf-hosting-004-foundry.json",
                        booleanMutation("expected", "continuationsPrincipalBound", false),
                        "must be true"),
                Arguments.of(
                        "content-understanding-version",
                        "conformance/v1/integrations/jcf-integrations-001-content-understanding.json",
                        mutation("input", "serviceApiVersion", "2026-06-01-preview"),
                        "2025-11-01"),
                Arguments.of(
                        "purview-telemetry-privacy",
                        "conformance/v1/integrations/jcf-integrations-002-purview.json",
                        booleanMutation("expected", "telemetryContainsContentOrIdentity", true),
                        "must be false"),
                Arguments.of(
                        "foundry-evaluations-sdk",
                        "conformance/v1/integrations/jcf-integrations-003-foundry-evaluations.json",
                        mutation("input", "sdk", "com.azure:azure-ai-projects:2.2.0"),
                        "com.azure:azure-ai-projects:2.3.0"),
                Arguments.of(
                        "cosmos-memory-vector-path",
                        "conformance/v1/integrations/jcf-integrations-004-cosmos-memory.json",
                        mutation("input", "vectorPath", "/embedding"),
                        "/vector"));
    }

    private static java.util.function.Consumer<ObjectNode> mutation(String objectName, String fieldName, String value) {
        return root -> ((ObjectNode) root.path(objectName)).put(fieldName, value);
    }

    private static java.util.function.Consumer<ObjectNode> booleanMutation(
            String objectName, String fieldName, boolean value) {
        return root -> ((ObjectNode) root.path(objectName)).put(fieldName, value);
    }
}
