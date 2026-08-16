// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AzureAuthenticationProvidersTest {
    @Test
    void token_shouldRedactCredentialMaterial() {
        AzureAccessToken token = new AzureAccessToken("credential-secret", Instant.parse("2030-01-01T00:00:00Z"));

        assertThat(token.toString()).contains("[REDACTED]").doesNotContain("credential-secret");
    }

    @Test
    void request_shouldRejectMissingScopes() {
        assertThatThrownBy(() -> new AzureTokenRequest(List.of(), null)).isInstanceOf(IllegalArgumentException.class);
    }
}
