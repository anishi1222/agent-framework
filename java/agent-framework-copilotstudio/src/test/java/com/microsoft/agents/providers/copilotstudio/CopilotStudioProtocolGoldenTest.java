// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.agents.core.StateValue;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CopilotStudioProtocolGoldenTest {
    @Test
    void golden_shouldMatchOfficialPythonAndDotNetDirectToEngineShapes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode golden;
        try (var input = getClass().getResourceAsStream("/protocol/copilotstudio-d2e-golden.json")) {
            golden = mapper.readTree(java.util.Objects.requireNonNull(input));
        }
        CopilotStudioWireCodec codec = new CopilotStudioWireCodec(CopilotStudioLimits.defaults());
        CopilotStudioActivity activity = CopilotStudioActivity.message("activity-1", "conversation-1", "hello");

        JsonNode start = mapper.readTree(codec.startRequest("en-US"));
        JsonNode execute = mapper.readTree(codec.activityRequest("conversation-1", activity));
        CopilotStudioActivity parsed =
                codec.parseActivity(mapper.writeValueAsBytes(golden.path("sse").path("activity")));

        assertThat(golden.path("apiVersion").asText()).isEqualTo(CopilotStudioProtocol.API_VERSION);
        assertThat(start).isEqualTo(golden.path("startRequest"));
        assertThat(execute).isEqualTo(golden.path("executeRequest"));
        assertThat(parsed.id()).isEqualTo("reply-1");
        assertThat(parsed.conversationId()).isEqualTo("conversation-1");
        assertThat(parsed.raw().values())
                .containsEntry("conversation", StateValue.object(Map.of("id", StateValue.string("conversation-1"))));
    }
}
