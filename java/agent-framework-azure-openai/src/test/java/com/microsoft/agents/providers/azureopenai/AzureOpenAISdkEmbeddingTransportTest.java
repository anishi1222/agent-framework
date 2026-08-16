// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureopenai;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.BinaryData;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.providers.openai.OpenAIEmbeddingEncodingFormat;
import com.microsoft.agents.providers.openai.OpenAIEmbeddingTransport;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AzureOpenAISdkEmbeddingTransportTest {
    private static final int OPENAI_FEATURE_INDEX = 56;

    private static final Pattern FEATURE_TOKEN = Pattern.compile("\\(feat=v1\\.([0-9a-f]+)\\)");

    @Test
    void sdkTransport_shouldMapKeyAuthenticatedRequestResponseAndApprovedUserAgent() {
        // Arrange
        RecordingHttpClient http = new RecordingHttpClient();
        AzureOpenAIEmbeddingClientOptions options = AzureOpenAIEmbeddingClientOptions.builder()
                .endpoint("https://resource.openai.azure.com")
                .deployment("deployment")
                .apiVersion("2025-01-01-preview")
                .apiKey("key-secret")
                .build();
        AzureOpenAISdkEmbeddingTransport transport = AzureOpenAISdkEmbeddingTransport.create(options, http);

        // Act
        OpenAIEmbeddingTransport.Response response = transport
                .generateAsync(request(), new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        HttpRequest sent = http.request.get();
        assertThat(sent.getUrl().toString())
                .startsWith("https://resource.openai.azure.com")
                .contains("/openai/deployments/override/embeddings")
                .contains("api-version=2025-01-01-preview");
        assertThat(sent.getHeaders().getValue(HttpHeaderName.fromString("api-key")))
                .isEqualTo("key-secret");
        String userAgent = sent.getHeaders().getValue(HttpHeaderName.fromString("User-Agent"));
        assertThat(userAgent).contains("agent-framework-java/");
        Matcher featureToken = FEATURE_TOKEN.matcher(userAgent);
        assertThat(featureToken.find()).isTrue();
        assertThat(new BigInteger(featureToken.group(1), 16).testBit(OPENAI_FEATURE_INDEX))
                .isTrue();
        assertThat(sent.getBodyAsBinaryData().toString())
                .contains("\"input\":[\"one\",\"two\"]")
                .contains("\"dimensions\":2")
                .contains("\"user\":\"user-1\"")
                .contains("\"input_type\":\"document\"");
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).vector().values().get(0))
                .isCloseTo(0.1, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(response.items().get(1).vector().values().get(1))
                .isCloseTo(0.4, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(response.usage().inputTokens()).contains(BigInteger.valueOf(2));
        assertThat(response.metadata()).containsEntry("openai.requestId", StateValue.string("request-embedding-1"));
    }

    @Test
    void sdkTransport_shouldUseTokenAuthAndStripFrameworkUserAgentFromCustomOrigin() {
        // Arrange
        RecordingHttpClient http = new RecordingHttpClient();
        TokenCredential credential = request ->
                Mono.just(new AccessToken("token-secret", OffsetDateTime.now().plusHours(1)));
        AzureOpenAIEmbeddingClientOptions options = AzureOpenAIEmbeddingClientOptions.builder()
                .endpoint("https://gateway.example.com")
                .deployment("deployment")
                .tokenCredential(credential)
                .build();
        AzureOpenAISdkEmbeddingTransport transport = AzureOpenAISdkEmbeddingTransport.create(options, http);

        // Act
        transport
                .generateAsync(request(), new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        HttpRequest sent = http.request.get();
        assertThat(sent.getHeaders().getValue(HttpHeaderName.AUTHORIZATION)).isEqualTo("Bearer token-secret");
        String userAgent = sent.getHeaders().getValue(HttpHeaderName.fromString("User-Agent"));
        assertThat(userAgent).doesNotContain("agent-framework-java/").doesNotContain("(feat=");
    }

    private static OpenAIEmbeddingTransport.Request request() {
        return new OpenAIEmbeddingTransport.Request(
                List.of("one", "two"),
                "override",
                2,
                OpenAIEmbeddingEncodingFormat.FLOAT,
                "user-1",
                Map.of(AzureOpenAISdkEmbeddingTransport.INPUT_TYPE_METADATA_KEY, StateValue.string("document")));
    }

    private static String responseJson() {
        return """
                {
                  "data":[
                    {"embedding":[0.1,0.2],"index":0,"object":"embedding"},
                    {"embedding":[0.3,0.4],"index":1,"object":"embedding"}
                  ],
                  "model":"override",
                  "object":"list",
                  "usage":{"prompt_tokens":2,"total_tokens":2}
                }
                """;
    }

    private static final class RecordingHttpClient implements com.azure.core.http.HttpClient {
        private final AtomicReference<HttpRequest> request = new AtomicReference<>();

        @Override
        public Mono<HttpResponse> send(HttpRequest request) {
            this.request.set(request);
            return Mono.just(new StringHttpResponse(request, responseJson()));
        }
    }

    private static final class StringHttpResponse extends HttpResponse {
        private final byte[] body;

        private final HttpHeaders headers;

        private StringHttpResponse(HttpRequest request, String body) {
            super(request);
            this.body = body.getBytes(StandardCharsets.UTF_8);
            headers = new HttpHeaders()
                    .set(HttpHeaderName.CONTENT_TYPE, "application/json")
                    .set(HttpHeaderName.CONTENT_LENGTH, Integer.toString(this.body.length))
                    .set(HttpHeaderName.fromString("apim-request-id"), "request-embedding-1");
        }

        @Override
        public int getStatusCode() {
            return 200;
        }

        @SuppressWarnings("deprecation")
        @Override
        public String getHeaderValue(String name) {
            return headers.getValue(HttpHeaderName.fromString(name));
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public Flux<ByteBuffer> getBody() {
            return Flux.just(ByteBuffer.wrap(body));
        }

        @Override
        public BinaryData getBodyAsBinaryData() {
            return BinaryData.fromBytes(body);
        }

        @Override
        public Mono<byte[]> getBodyAsByteArray() {
            return Mono.just(body.clone());
        }

        @Override
        public Mono<String> getBodyAsString() {
            return Mono.just(new String(body, StandardCharsets.UTF_8));
        }

        @Override
        public Mono<String> getBodyAsString(Charset charset) {
            return Mono.just(new String(body, charset));
        }
    }
}
