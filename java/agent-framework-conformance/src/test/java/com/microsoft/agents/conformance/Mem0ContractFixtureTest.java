// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class Mem0ContractFixtureTest {
    private static final String RESOURCE = "conformance/v1/integrations/jcf-integrations-005-mem0.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConformanceFixtureLoader loader = new ConformanceFixtureLoader();

    @Test
    void fixture_shouldBindVerifiedPlatformPathsAndSafetyClaims() {
        ConformanceFixture fixture = loader.loadDefault().requireCase("JCF-INTEGRATIONS-005");

        assertThat(fixture).isInstanceOf(BehaviorFixture.class);
        BehaviorFixture behavior = (BehaviorFixture) fixture;
        assertThat(behavior.kind()).isEqualTo(FixtureKind.CONTRACT);
        assertThat(behavior.input().values())
                .containsEntry("addPath", new ConformanceValue.StringValue("POST /v3/memories/add/"))
                .containsEntry("eventPath", new ConformanceValue.StringValue("GET /v1/event/{event_id}/"));
        assertThat(behavior.expected().values())
                .containsEntry("batchedAdd", new ConformanceValue.BooleanValue(true))
                .containsEntry("sideEffectingAddRetried", new ConformanceValue.BooleanValue(false))
                .containsEntry("partitionedUserAgentScopes", new ConformanceValue.BooleanValue(true))
                .containsEntry("unscopedItemOperationsExposed", new ConformanceValue.BooleanValue(false))
                .containsEntry("historyEndpointExposed", new ConformanceValue.BooleanValue(false));
    }

    @Test
    void fixtureSchema_shouldRejectApiOrSafetyDrift() throws Exception {
        ObjectNode root;
        try (var input = Mem0ContractFixtureTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            root = (ObjectNode) MAPPER.readTree(java.util.Objects.requireNonNull(input));
        }
        ((ObjectNode) root.path("input")).put("searchPath", "POST /v1/memories/search/");
        ((ObjectNode) root.path("expected")).put("sideEffectingAddRetried", true);
        byte[] bytes = MAPPER.writeValueAsBytes(root);

        assertThatThrownBy(() -> loader.loadFixture(new ByteArrayInputStream(bytes), "mem0-drift"))
                .isInstanceOf(ConformanceValidationException.class)
                .hasMessageContaining("POST /v3/memories/search/");
    }
}
