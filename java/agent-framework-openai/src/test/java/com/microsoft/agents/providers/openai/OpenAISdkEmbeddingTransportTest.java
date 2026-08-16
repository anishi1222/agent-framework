// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.ObjectMappers;
import com.openai.core.http.HttpResponseFor;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.services.async.EmbeddingServiceAsync;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpenAISdkEmbeddingTransportTest {
    @Test
    void sdkTransport_shouldMapFloatAndBase64ResponsesWithRawRequestId() throws Exception {
        // Arrange
        Fixture floats = fixture("req_float", """
                {
                  "data":[
                    {"embedding":[0.1,0.2],"index":0,"object":"embedding"},
                    {"embedding":[0.3,0.4],"index":1,"object":"embedding"}
                  ],
                  "model":"text-embedding-3-small",
                  "object":"list",
                  "usage":{"prompt_tokens":2,"total_tokens":2}
                }
                """);
        String encoded = base64(0.5f, 0.75f);
        Fixture base64 = fixture("req_base64", """
                {
                  "data":[{"embedding":"%s","index":0,"object":"embedding"}],
                  "model":"text-embedding-3-small",
                  "object":"list",
                  "usage":{"prompt_tokens":1,"total_tokens":1}
                }
                """.formatted(encoded));

        // Act
        OpenAIEmbeddingTransport.Response floatResponse = floats.transport
                .generateAsync(
                        request(List.of("one", "two"), OpenAIEmbeddingEncodingFormat.FLOAT),
                        new DefaultRunCancellation())
                .toCompletableFuture()
                .join();
        OpenAIEmbeddingTransport.Response base64Response = base64.transport
                .generateAsync(
                        request(List.of("one"), OpenAIEmbeddingEncodingFormat.BASE64), new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(floatResponse.items()).hasSize(2);
        assertThat(floatResponse.items().get(0).vector().values().get(0))
                .isCloseTo(0.1, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(floatResponse.items().get(0).vector().values().get(1))
                .isCloseTo(0.2, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(floatResponse.items().get(1).vector().values().get(0))
                .isCloseTo(0.3, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(floatResponse.items().get(1).vector().values().get(1))
                .isCloseTo(0.4, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(floatResponse.metadata())
                .containsEntry("openai.requestId", com.microsoft.agents.core.StateValue.string("req_float"));
        assertThat(floatResponse.usage().inputTokens()).contains(java.math.BigInteger.valueOf(2));
        assertThat(base64Response.items().getFirst().vector().values()).containsExactly(0.5, 0.75);
        ArgumentCaptor<EmbeddingCreateParams> params = ArgumentCaptor.forClass(EmbeddingCreateParams.class);
        verify(base64.rawService).create(params.capture());
        assertThat(params.getValue().encodingFormat()).contains(EmbeddingCreateParams.EncodingFormat.BASE64);
        verify(floats.raw).close();
        verify(base64.raw).close();
    }

    private static Fixture fixture(String requestId, String json) throws Exception {
        OpenAIClientAsync client = mock(OpenAIClientAsync.class);
        EmbeddingServiceAsync service = mock(EmbeddingServiceAsync.class);
        EmbeddingServiceAsync.WithRawResponse rawService = mock(EmbeddingServiceAsync.WithRawResponse.class);
        @SuppressWarnings("unchecked")
        HttpResponseFor<CreateEmbeddingResponse> raw = mock(HttpResponseFor.class);
        when(client.embeddings()).thenReturn(service);
        when(service.withRawResponse()).thenReturn(rawService);
        when(rawService.create(any(EmbeddingCreateParams.class))).thenReturn(CompletableFuture.completedFuture(raw));
        when(raw.requestId()).thenReturn(Optional.of(requestId));
        when(raw.parse()).thenReturn(ObjectMappers.jsonMapper().readValue(json, CreateEmbeddingResponse.class));
        return new Fixture(new OpenAISdkEmbeddingTransport(client), rawService, raw);
    }

    private static OpenAIEmbeddingTransport.Request request(
            List<String> values, OpenAIEmbeddingEncodingFormat encoding) {
        return new OpenAIEmbeddingTransport.Request(values, "model", 2, encoding, "user", java.util.Map.of());
    }

    private static String base64(float... values) {
        ByteBuffer bytes = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) {
            bytes.putFloat(value);
        }
        return Base64.getEncoder().encodeToString(bytes.array());
    }

    private record Fixture(
            OpenAISdkEmbeddingTransport transport,
            EmbeddingServiceAsync.WithRawResponse rawService,
            HttpResponseFor<CreateEmbeddingResponse> raw) {}
}
