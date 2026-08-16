// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class ValkeyContractFixtureTest {
    private static final String RESOURCE = "conformance/v1/sessions/jcf-sessions-005-valkey-history.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConformanceFixtureLoader loader = new ConformanceFixtureLoader();

    @Test
    void manifest_shouldBindTheImplementedValkeyHistoryContract() {
        ConformanceFixtureCatalog catalog = loader.loadDefault();

        assertThat(catalog.requireCase("JCF-SESSIONS-005")).isInstanceOfSatisfying(BehaviorFixture.class, fixture -> {
            assertThat(fixture.kind()).isEqualTo(FixtureKind.CONTRACT);
            assertThat(fixture.input().values()).containsKey("sdk");
            assertThat(fixture.expected().values()).containsKey("appendAtomic");
        });
        assertThat(catalog.manifest().cases())
                .filteredOn(manifestCase -> manifestCase.caseId().equals("JCF-SESSIONS-005"))
                .singleElement()
                .satisfies(manifestCase -> {
                    assertThat(manifestCase.fixture()).isEqualTo(RESOURCE);
                    assertThat(manifestCase.matrixAreas()).containsExactly("Redis / Valkey history");
                    assertThat(manifestCase.sourceReferences())
                            .containsExactly(
                                    "dotnet/tests/Microsoft.Agents.AI.Valkey.UnitTests/"
                                            + "ValkeyChatHistoryProviderTests.cs",
                                    "python/packages/redis/agent_framework_redis/_history_provider.py",
                                    "io.valkey:valkey-glide:2.5.1");
                });
    }

    @Test
    void schema_shouldRejectSdkOrAtomicityDrift() throws Exception {
        ObjectNode wrongSdk = resource();
        ((ObjectNode) wrongSdk.path("input")).put("sdk", "io.valkey:valkey-glide:2.5.0");
        ObjectNode nonAtomic = resource();
        ((ObjectNode) nonAtomic.path("expected")).put("appendAtomic", false);

        assertThatThrownBy(() ->
                        loader.loadFixture(new ByteArrayInputStream(MAPPER.writeValueAsBytes(wrongSdk)), "wrong-sdk"))
                .isInstanceOf(ConformanceValidationException.class)
                .hasMessageContaining("2.5.1");
        assertThatThrownBy(() ->
                        loader.loadFixture(new ByteArrayInputStream(MAPPER.writeValueAsBytes(nonAtomic)), "non-atomic"))
                .isInstanceOf(ConformanceValidationException.class)
                .hasMessageContaining("appendAtomic")
                .hasMessageContaining("true");
    }

    private static ObjectNode resource() throws Exception {
        try (var input = ValkeyContractFixtureTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            return (ObjectNode) MAPPER.readTree(java.util.Objects.requireNonNull(input));
        }
    }
}
