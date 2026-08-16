// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class AzureAISearchContractFixtureTest {
    private static final String RESOURCE = "conformance/v1/integrations/jcf-integrations-006-azure-ai-search.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConformanceFixtureLoader loader = new ConformanceFixtureLoader();

    @Test
    void fixture_shouldBindOfficialSdkScopedFiltersAndReadOnlyContextClaims() {
        ConformanceFixture fixture = loader.loadDefault().requireCase("JCF-INTEGRATIONS-006");

        assertThat(fixture).isInstanceOf(BehaviorFixture.class);
        BehaviorFixture behavior = (BehaviorFixture) fixture;
        assertThat(behavior.kind()).isEqualTo(FixtureKind.CONTRACT);
        assertThat(behavior.input().values())
                .containsEntry("sdk", new ConformanceValue.StringValue("com.azure:azure-search-documents:12.0.1"))
                .containsEntry("serviceApiVersion", new ConformanceValue.StringValue("2026-04-01"))
                .containsEntry(
                        "filterModel", new ConformanceValue.StringValue("mandatory-tenant-and-scope-pre-filter"));
        assertThat(behavior.expected().values())
                .containsEntry(
                        "semanticHybridMinimumCandidates",
                        new ConformanceValue.NumberValue(java.math.BigDecimal.valueOf(50)))
                .containsEntry("sourceFilterAddOnEverySource", new ConformanceValue.BooleanValue(true))
                .containsEntry("sideEffectingDocumentApisExposed", new ConformanceValue.BooleanValue(false));
    }

    @Test
    void fixtureSchema_shouldRejectSdkOrIsolationDrift() throws Exception {
        ObjectNode root;
        try (var input = AzureAISearchContractFixtureTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            root = (ObjectNode) MAPPER.readTree(java.util.Objects.requireNonNull(input));
        }
        ((ObjectNode) root.path("input")).put("sdk", "com.azure:azure-search-documents:12.1.0-beta.1");
        ((ObjectNode) root.path("expected")).put("vectorPreFilter", false);

        assertThatThrownBy(() -> loader.loadFixture(
                        new ByteArrayInputStream(MAPPER.writeValueAsBytes(root)), "azure-search-drift"))
                .isInstanceOf(ConformanceValidationException.class)
                .hasMessageContaining("com.azure:azure-search-documents:12.0.1");
    }
}
