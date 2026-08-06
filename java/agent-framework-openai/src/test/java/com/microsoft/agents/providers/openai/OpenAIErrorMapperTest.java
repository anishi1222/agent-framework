// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import java.net.SocketTimeoutException;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class OpenAIErrorMapperTest {
    @Test
    void serviceErrors_shouldMapStatusAndSanitizedRequestIdWithoutBody() {
        // Arrange
        UnauthorizedException sdkFailure = UnauthorizedException.builder()
                .headers(Headers.builder()
                        .put("x-request-id", "req_safe-123\r\nAuthorization: secret")
                        .build())
                .build();

        // Act
        RuntimeException mapped = OpenAIErrorMapper.map(sdkFailure);

        // Assert
        assertThat(mapped).isInstanceOf(OpenAIAuthenticationException.class);
        OpenAIProviderException providerFailure = (OpenAIProviderException) mapped;
        assertThat(providerFailure.statusCode()).hasValue(401);
        assertThat(providerFailure.requestId()).isEmpty();
        assertThat(providerFailure.getMessage()).doesNotContain("Authorization", "secret", "\r", "\n");
        assertThat(providerFailure.getCause()).isNull();
    }

    @Test
    void rateLimitAndHttpFailures_shouldRemainDistinct() {
        // Arrange
        Headers headers = Headers.builder().put("x-request-id", "req-429").build();

        // Act
        RuntimeException rateLimit = OpenAIErrorMapper.map(
                RateLimitException.builder().headers(headers).build());
        RuntimeException badRequest = OpenAIErrorMapper.map(
                BadRequestException.builder().headers(headers).build());

        // Assert
        assertThat(rateLimit).isInstanceOf(OpenAIRateLimitException.class);
        assertThat(((OpenAIProviderException) rateLimit).statusCode()).hasValue(429);
        assertThat(badRequest).isInstanceOf(OpenAIHttpException.class);
        assertThat(((OpenAIProviderException) badRequest).statusCode()).hasValue(400);
    }

    @Test
    void ioTimeoutAndCancellation_shouldRemainTyped() {
        // Arrange / Act
        RuntimeException timeout =
                OpenAIErrorMapper.map(new OpenAIIoException("sensitive sdk message", new SocketTimeoutException()));
        RuntimeException cancelled = OpenAIErrorMapper.map(new CancellationException("cancelled"));

        // Assert
        assertThat(timeout).isInstanceOf(OpenAITimeoutException.class);
        assertThat(timeout.getMessage()).doesNotContain("sensitive");
        assertThat(cancelled).isInstanceOf(com.microsoft.agents.core.RunCancelledException.class);
    }

    @Test
    void invalidSdkData_shouldBecomeSanitizedProtocolFailure() {
        // Arrange / Act
        RuntimeException mapped =
                OpenAIErrorMapper.map(new OpenAIInvalidDataException("response body contains secret"));

        // Assert
        assertThat(mapped)
                .isInstanceOf(OpenAIProtocolException.class)
                .hasMessage("OpenAI returned data that the pinned SDK could not decode.")
                .hasFieldOrPropertyWithValue("cause", null);
    }
}
