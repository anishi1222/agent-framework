// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class FoundryChatClientOptionsTest {
    private static final String ENDPOINT = "https://resource.services.ai.azure.com/api/projects/project-one";

    @Test
    void options_shouldRepresentModelAndVersionedAgentSurfaces() {
        TokenCredential credential = context ->
                Mono.just(new AccessToken("token", OffsetDateTime.now().plusHours(1)));

        FoundryChatClientOptions model = FoundryChatClientOptions.builder()
                .projectEndpoint(ENDPOINT)
                .model("deployment")
                .tokenCredential(credential)
                .build();
        FoundryChatClientOptions agent = FoundryChatClientOptions.builder()
                .projectEndpoint(ENDPOINT)
                .agentName("weather-agent")
                .agentVersion("3")
                .defaultConversationId("conversation-1")
                .tokenCredential(credential)
                .build();

        assertThat(model.surface()).isEqualTo(FoundrySurface.MODEL);
        assertThat(model.model()).contains("deployment");
        assertThat(agent.surface()).isEqualTo(FoundrySurface.AGENT);
        assertThat(agent.agentName()).contains("weather-agent");
        assertThat(agent.agentVersion()).contains("3");
        assertThat(agent.toString()).contains("credential=[REDACTED]").doesNotContain("token");
    }

    @Test
    void options_shouldValidateProjectIdentitySurfaceAndCredential() {
        TokenCredential credential = context ->
                Mono.just(new AccessToken("token", OffsetDateTime.now().plusHours(1)));

        assertThatThrownBy(() -> FoundryChatClientOptions.builder()
                        .projectEndpoint("https://resource.services.ai.azure.com")
                        .model("deployment")
                        .tokenCredential(credential)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/api/projects/");
        assertThatThrownBy(() -> FoundryChatClientOptions.builder()
                        .projectEndpoint(ENDPOINT)
                        .model("deployment")
                        .agentName("agent")
                        .tokenCredential(credential)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Exactly one");
        assertThatThrownBy(() -> FoundryChatClientOptions.builder()
                        .projectEndpoint(ENDPOINT)
                        .agentName("agent")
                        .agentVersion(" ")
                        .tokenCredential(credential)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentVersion");
        assertThatThrownBy(() -> FoundryChatClientOptions.builder()
                        .projectEndpoint(ENDPOINT)
                        .model("deployment")
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tokenCredential");
    }
}
