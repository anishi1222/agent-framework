// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.StateValue;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAIModelsTest {
    @Test
    void secret_shouldCopyInputAndAlwaysRedactDiagnostics() {
        // Arrange
        char[] source = "sk-test-secret".toCharArray();

        // Act
        OpenAISecret secret = OpenAISecret.of(source);
        source[0] = 'x';

        // Assert
        assertThat(secret).isEqualTo(OpenAISecret.of("sk-test-secret"));
        assertThat(secret.toString()).isEqualTo("[REDACTED]");
    }

    @Test
    void clientOptions_shouldBeImmutableAndRedactCredentials() {
        // Arrange / Act
        OpenAIChatClientOptions options = OpenAIChatClientOptions.builder()
                .apiKey("sk-sensitive-value")
                .model("gpt-test")
                .baseUrl("https://example.test/v1")
                .organization("org-test")
                .project("project-test")
                .timeout(Duration.ofSeconds(30))
                .maxRetries(4)
                .maxBufferedUpdates(8)
                .build();

        // Assert
        assertThat(options.hasApiKey()).isTrue();
        assertThat(options.model()).isEqualTo("gpt-test");
        assertThat(options.baseUrl()).contains(URI.create("https://example.test/v1"));
        assertThat(options.timeout()).contains(Duration.ofSeconds(30));
        assertThat(options.toString()).contains("[REDACTED]").doesNotContain("sk-sensitive-value");
    }

    @Test
    void clientOptions_shouldRejectInvalidBoundsAndLocations() {
        // Arrange / Act / Assert
        assertThatThrownBy(() -> OpenAIChatClientOptions.builder()
                        .model("model")
                        .maxRetries(-1)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRetries");
        assertThatThrownBy(() -> OpenAIChatClientOptions.builder()
                        .model("model")
                        .maxBufferedUpdates(0)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBufferedUpdates");
        assertThatThrownBy(() -> OpenAIChatClientOptions.builder()
                        .model("model")
                        .baseUrl("/relative")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");
        assertThatThrownBy(() -> OpenAIChatClientOptions.builder().model(" ").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
    }

    @Test
    void responseOptions_shouldExposeConservativeImmutableDefaults() {
        // Arrange / Act
        OpenAIResponseOptions defaults = OpenAIResponseOptions.defaults();
        OpenAIResponseOptions configured = OpenAIResponseOptions.builder()
                .reasoningEffort(OpenAIReasoningEffort.HIGH)
                .reasoningSummary(OpenAIReasoningSummary.CONCISE)
                .serviceTier(OpenAIServiceTier.FLEX)
                .truncation(OpenAITruncation.AUTO)
                .imageOutputFormat(OpenAIImageOutputFormat.JPEG)
                .background(true)
                .includeEncryptedReasoning(false)
                .build();

        // Assert
        assertThat(defaults.background()).isNull();
        assertThat(defaults.includeEncryptedReasoning()).isTrue();
        assertThat(configured.reasoningEffort()).isEqualTo(OpenAIReasoningEffort.HIGH);
        assertThat(configured.reasoningSummary()).isEqualTo(OpenAIReasoningSummary.CONCISE);
        assertThat(configured.imageOutputFormat()).isEqualTo(OpenAIImageOutputFormat.JPEG);
        assertThat(configured.background()).isTrue();
        assertThat(configured.includeEncryptedReasoning()).isFalse();
    }

    @Test
    void transportModels_shouldDefensivelyCopyCollections() {
        // Arrange
        ArrayList<OpenAITransport.InputItem> input = new ArrayList<>(List.of(new OpenAITransport.MessageInput(
                OpenAITransport.InputRole.USER, List.of(new OpenAITransport.TextInput("hello")))));
        ArrayList<OpenAITransport.OutputItem> output =
                new ArrayList<>(List.of(new OpenAITransport.TextOutput("message", "world", false, Map.of())));

        // Act
        OpenAITransport.Request request = new OpenAITransport.Request(
                "model",
                input,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                OpenAIResponseOptions.defaults());
        OpenAITransport.Response response = new OpenAITransport.Response(
                "response",
                null,
                "model",
                Instant.EPOCH,
                OpenAITransport.ResponseStatus.COMPLETED,
                output,
                null,
                Map.of("trace", StateValue.string("safe")),
                null,
                null,
                null);
        input.clear();
        output.clear();

        // Assert
        assertThat(request.input()).hasSize(1);
        assertThat(response.outputs()).hasSize(1);
        assertThatThrownBy(() -> request.input().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void providerExceptions_shouldRetainOnlySafeStructuredDiagnostics() {
        // Arrange / Act
        OpenAIProviderException failure = new OpenAIHttpException(500, "req_safe-123", "server_error");

        // Assert
        assertThat(failure.statusCode()).hasValue(500);
        assertThat(failure.requestId()).contains("req_safe-123");
        assertThat(failure.errorCode()).contains("server_error");
        assertThat(failure.getMessage()).doesNotContain("body", "secret", "authorization");
        assertThat(failure.getCause()).isNull();
    }
}
