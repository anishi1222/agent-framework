// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureopenai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AzureOpenAIChatClientOptionsTest {
    private static final String SECRET = "azure-secret-value";

    @Test
    void options_shouldSupportKeyAndCallerOwnedTokenModesWithoutDisclosingSecrets() {
        // Arrange
        TokenCredential credential = context ->
                Mono.just(new AccessToken("token", OffsetDateTime.now().plusHours(1)));

        // Act
        AzureOpenAIChatClientOptions keyOptions = builder().apiKey(SECRET).build();
        AzureOpenAIChatClientOptions tokenOptions =
                builder().tokenCredential(credential).build();

        // Assert
        assertThat(keyOptions.authenticationMode()).isEqualTo(AzureOpenAIAuthenticationMode.API_KEY);
        assertThat(tokenOptions.authenticationMode()).isEqualTo(AzureOpenAIAuthenticationMode.TOKEN_CREDENTIAL);
        assertThat(keyOptions.toString()).contains("[REDACTED]").doesNotContain(SECRET);
        assertThat(tokenOptions.toString()).doesNotContain("token");
    }

    @Test
    void options_shouldRejectAmbiguousAuthenticationAndInvalidEndpointOrIdentity() {
        TokenCredential credential = context ->
                Mono.just(new AccessToken("token", OffsetDateTime.now().plusHours(1)));

        assertThatThrownBy(() -> builder().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Exactly one");
        assertThatThrownBy(() ->
                        builder().apiKey(SECRET).tokenCredential(credential).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Exactly one");
        assertThatThrownBy(() ->
                        builder().endpoint("http://example.test").apiKey(SECRET).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> builder().deployment(" ").apiKey(SECRET).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deployment");
        assertThatThrownBy(
                        () -> builder().apiVersion("2099-01-01").apiKey(SECRET).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not supported");
    }

    private static AzureOpenAIChatClientOptions.Builder builder() {
        return AzureOpenAIChatClientOptions.builder()
                .endpoint("https://resource.openai.azure.com")
                .deployment("deployment");
    }
}
