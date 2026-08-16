// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CopilotStudioClientOptionsTest {
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    @Test
    void environmentOptions_shouldDeriveCurrentPowerPlatformEndpointAndAudience() {
        CopilotStudioClientOptions options = CopilotStudioClientOptions.builder()
                .tenantId(TENANT)
                .environment("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "cr123_agent")
                .allowedHosts(Set.of("aaaaaaaabbbbccccddddeeeeeeeeee.ee.environment.api.powerplatform.com"))
                .build();

        assertThat(options.endpoint().getPath())
                .isEqualTo("/copilotstudio/dataverse-backed/authenticated/bots/cr123_agent");
        assertThat(options.tokenAudience()).isEqualTo("https://api.powerplatform.com/.default");
    }

    @Test
    void endpointOptions_shouldRejectRemoteHttpMissingAllowlistAndInvalidIdentifiers() {
        assertThatThrownBy(() -> CopilotStudioClientOptions.builder()
                        .tenantId(TENANT)
                        .endpoint(
                                URI.create("http://example.com/copilotstudio/dataverse-backed/authenticated/bots/bot"))
                        .allowedHosts(Set.of("example.com"))
                        .allowInsecureLoopback(true)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> CopilotStudioClientOptions.builder()
                        .tenantId(TENANT)
                        .endpoint(
                                URI.create("https://example.com/copilotstudio/dataverse-backed/authenticated/bots/bot"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowedHosts");
        assertThatThrownBy(() -> CopilotStudioClientOptions.builder()
                        .tenantId("not-a-guid")
                        .environment("also-not-a-guid", "../bot")
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void token_shouldRedactAndRejectHeaderInjection() {
        CopilotStudioAccessToken token =
                new CopilotStudioAccessToken("secret-token", Instant.now().plusSeconds(300));

        assertThat(token.toString()).doesNotContain("secret-token").contains("REDACTED");
        assertThatThrownBy(() -> new CopilotStudioAccessToken(
                        "token\r\nInjected: yes", Instant.now().plusSeconds(300)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control");
    }
}
