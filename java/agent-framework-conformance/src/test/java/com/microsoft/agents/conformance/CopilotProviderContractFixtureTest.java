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

class CopilotProviderContractFixtureTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConformanceFixtureLoader loader = new ConformanceFixtureLoader();

    @Test
    void manifest_shouldBindBothCopilotProviderContracts() {
        ConformanceFixtureCatalog catalog = loader.loadDefault();

        assertThat(List.of(catalog.requireCase("JCF-PROVIDERS-010"), catalog.requireCase("JCF-PROVIDERS-011")))
                .allSatisfy(fixture -> {
                    assertThat(fixture).isInstanceOf(BehaviorFixture.class);
                    assertThat(fixture.kind()).isEqualTo(FixtureKind.CONTRACT);
                });
        assertThat(catalog.manifest().cases())
                .filteredOn(manifestCase -> manifestCase.caseId().matches("JCF-PROVIDERS-01[01]"))
                .extracting(ManifestCase::fixture)
                .containsExactly(
                        "conformance/v1/providers/jcf-providers-010-github-copilot.json",
                        "conformance/v1/providers/jcf-providers-011-copilotstudio.json");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidContracts")
    void contractSchema_shouldRejectProtocolAndSecurityDrift(
            String name, String resource, java.util.function.Consumer<ObjectNode> mutation, String expectedMessage)
            throws Exception {
        ObjectNode root;
        try (var input =
                CopilotProviderContractFixtureTest.class.getClassLoader().getResourceAsStream(resource)) {
            root = (ObjectNode) MAPPER.readTree(java.util.Objects.requireNonNull(input));
        }
        mutation.accept(root);

        assertThatThrownBy(() -> loader.loadFixture(new ByteArrayInputStream(MAPPER.writeValueAsBytes(root)), name))
                .isInstanceOf(ConformanceValidationException.class)
                .hasMessageContaining(expectedMessage);
    }

    private static Stream<Arguments> invalidContracts() {
        return Stream.of(
                Arguments.of(
                        "github-protocol-source",
                        "conformance/v1/providers/jcf-providers-010-github-copilot.json",
                        textMutation("input", "protocolVersionSource", "framework.Protocol"),
                        "com.github.copilot.SdkProtocolVersion"),
                Arguments.of(
                        "github-sdk-leak",
                        "conformance/v1/providers/jcf-providers-010-github-copilot.json",
                        booleanMutation("expected", "officialSdkTypesPublic", true),
                        "must be false"),
                Arguments.of(
                        "github-classic-pat",
                        "conformance/v1/providers/jcf-providers-010-github-copilot.json",
                        booleanMutation("expected", "classicPatSupported", true),
                        "must be false"),
                Arguments.of(
                        "studio-api-version",
                        "conformance/v1/providers/jcf-providers-011-copilotstudio.json",
                        textMutation("input", "serviceApiVersion", "2026-01-01"),
                        "2022-03-01-preview"),
                Arguments.of(
                        "studio-watermark",
                        "conformance/v1/providers/jcf-providers-011-copilotstudio.json",
                        booleanMutation("expected", "directLineWatermarkUsed", true),
                        "must be false"),
                Arguments.of(
                        "studio-auto-oauth",
                        "conformance/v1/providers/jcf-providers-011-copilotstudio.json",
                        booleanMutation("expected", "oauthActionsAutoExecuted", true),
                        "must be false"));
    }

    private static java.util.function.Consumer<ObjectNode> booleanMutation(
            String objectName, String fieldName, boolean value) {
        return root -> ((ObjectNode) root.path(objectName)).put(fieldName, value);
    }

    private static java.util.function.Consumer<ObjectNode> integerMutation(
            String objectName, String fieldName, int value) {
        return root -> ((ObjectNode) root.path(objectName)).put(fieldName, value);
    }

    private static java.util.function.Consumer<ObjectNode> textMutation(
            String objectName, String fieldName, String value) {
        return root -> ((ObjectNode) root.path(objectName)).put(fieldName, value);
    }
}
