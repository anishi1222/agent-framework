// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureopenai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpResponse;
import org.junit.jupiter.api.Test;

class AzureOpenAIErrorMapperTest {
    @Test
    void httpFailure_shouldRetainOnlySafeStatusIdentifiersAndServiceCode() {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(429);
        when(response.getHeaders())
                .thenReturn(new HttpHeaders()
                        .set(HttpHeaderName.fromString("x-request-id"), "request-1")
                        .set(HttpHeaderName.fromString("x-ms-correlation-request-id"), "correlation-1")
                        .set(HttpHeaderName.fromString("x-ms-error-code"), "rate_limit"));

        RuntimeException mapped =
                AzureOpenAIErrorMapper.map(new HttpResponseException("body credential-secret", response));

        assertThat(mapped).isInstanceOfSatisfying(AzureOpenAIProviderException.class, failure -> {
            assertThat(failure.kind()).isEqualTo(AzureOpenAIProviderException.Kind.RATE_LIMIT);
            assertThat(failure.statusCode()).hasValue(429);
            assertThat(failure.requestId()).contains("request-1");
            assertThat(failure.correlationId()).contains("correlation-1");
            assertThat(failure.serviceCode()).contains("rate_limit");
            assertThat(failure.getMessage()).doesNotContain("body", "credential-secret");
            assertThat(failure.getCause()).isNull();
        });
    }
}
