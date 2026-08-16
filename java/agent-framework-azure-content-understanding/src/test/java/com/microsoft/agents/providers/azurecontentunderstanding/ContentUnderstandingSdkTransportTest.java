// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azurecontentunderstanding;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.BinaryData;
import com.microsoft.agents.azure.AzureAccessToken;
import com.microsoft.agents.azure.AzureTokenRequest;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.StateValue;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ContentUnderstandingSdkTransportTest {
    @Test
    void sdkTransport_shouldExecuteAnalyzeLroWithStableApiPathVersionAndScope() {
        RecordingHttpClient http = new RecordingHttpClient();
        AtomicReference<AzureTokenRequest> tokenRequest = new AtomicReference<>();
        AzureContentUnderstandingOptions options = AzureContentUnderstandingOptions.builder()
                .endpoint("https://resource.services.ai.azure.com/")
                .authenticationProvider((request, cancellation) -> {
                    tokenRequest.set(request);
                    return CompletableFuture.completedStage(
                            new AzureAccessToken("token-secret", Instant.now().plusSeconds(3600)));
                })
                .pollInterval(Duration.ofMillis(1))
                .operationTimeout(Duration.ofSeconds(5))
                .build();
        ContentUnderstandingSdkTransport transport = ContentUnderstandingSdkTransport.create(options, http);

        ContentAnalysisResult result = transport
                .analyzeAsync(
                        new ContentAnalysisRequest(
                                "prebuilt-layout",
                                List.of(new ContentUrlInput(
                                        java.net.URI.create("https://storage.example.com/doc.pdf?sig=sas-secret"),
                                        "doc.pdf",
                                        "application/pdf",
                                        "1-3")),
                                Map.of()),
                        new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        HttpRequest initial = http.initial.get();
        assertThat(initial.getHttpMethod().toString()).isEqualTo("POST");
        assertThat(initial.getUrl().toString())
                .contains("/contentunderstanding/analyzers/prebuilt-layout:analyze")
                .contains("api-version=2025-11-01");
        assertThat(initial.getHeaders().getValue(HttpHeaderName.AUTHORIZATION)).isEqualTo("Bearer token-secret");
        assertThat(initial.getBodyAsBinaryData().toString())
                .contains("\"url\":\"https://storage.example.com/doc.pdf?sig=sas-secret\"")
                .contains("\"range\":\"1-3\"");
        assertThat(tokenRequest.get().scopes()).containsExactly("https://cognitiveservices.azure.com/.default");
        assertThat(result.operationId()).isEqualTo("operation-one");
        assertThat(result.status()).isEqualTo(ContentOperationStatus.SUCCEEDED);
        assertThat(result.contents()).hasSize(1);
        assertThat(result.contents().getFirst().markdown()).isEqualTo("# Document");
    }

    @Test
    void sdkTransport_shouldUseAnalyzerCrudAndBoundedListPaths() {
        AnalyzerCrudHttpClient http = new AnalyzerCrudHttpClient();
        AzureContentUnderstandingOptions options = AzureContentUnderstandingOptions.builder()
                .endpoint("https://resource.services.ai.azure.com/")
                .authenticationProvider((request, cancellation) -> CompletableFuture.completedStage(
                        new AzureAccessToken("token-secret", Instant.now().plusSeconds(3600))))
                .pollInterval(Duration.ofMillis(1))
                .operationTimeout(Duration.ofSeconds(5))
                .build();
        ContentUnderstandingSdkTransport transport = ContentUnderstandingSdkTransport.create(options, http);
        ContentAnalyzerRequest request = new ContentAnalyzerRequest(
                "custom-one",
                StateValue.object(Map.of(
                        "analyzerId", StateValue.string("custom-one"),
                        "description", StateValue.string("Custom analyzer"),
                        "baseAnalyzerId", StateValue.string("prebuilt-document"))));
        DefaultRunCancellation cancellation = new DefaultRunCancellation();

        ContentAnalyzerDefinition created = transport
                .createAnalyzerAsync(request, cancellation)
                .toCompletableFuture()
                .join();
        ContentAnalyzerDefinition loaded = transport
                .getAnalyzerAsync("custom-one", cancellation)
                .toCompletableFuture()
                .join();
        ContentAnalyzerDefinition updated = transport
                .updateAnalyzerAsync(request, cancellation)
                .toCompletableFuture()
                .join();
        ContentUnderstandingPage<ContentAnalyzerDefinition> page = transport
                .listAnalyzersAsync(1, null, cancellation)
                .toCompletableFuture()
                .join();
        transport
                .deleteAnalyzerAsync("custom-one", cancellation)
                .toCompletableFuture()
                .join();

        assertThat(created.analyzerId()).isEqualTo("custom-one");
        assertThat(loaded.analyzerId()).isEqualTo("custom-one");
        assertThat(updated.analyzerId()).isEqualTo("custom-one");
        assertThat(page.items())
                .extracting(ContentAnalyzerDefinition::analyzerId)
                .containsExactly("custom-one");
        assertThat(http.requests)
                .extracting(requestValue -> requestValue.getHttpMethod().toString())
                .contains("PUT", "GET", "PATCH", "DELETE");
        assertThat(http.requests)
                .allSatisfy(requestValue ->
                        assertThat(requestValue.getUrl().toString()).contains("api-version=2025-11-01"));
        assertThat(http.requests.stream()
                        .filter(requestValue -> requestValue.getHttpMethod() == com.azure.core.http.HttpMethod.PUT)
                        .findFirst()
                        .orElseThrow()
                        .getUrl()
                        .getPath())
                .endsWith("/contentunderstanding/analyzers/custom-one");
        assertThat(http.requests.stream()
                        .filter(requestValue -> requestValue.getHttpMethod() == com.azure.core.http.HttpMethod.PATCH)
                        .findFirst()
                        .orElseThrow()
                        .getBodyAsBinaryData()
                        .toString())
                .contains("\"baseAnalyzerId\":\"prebuilt-document\"");
    }

    @Test
    void sdkTransportCancellation_shouldCancelPendingLroWithoutRemoteDelete() {
        NeverHttpClient http = new NeverHttpClient();
        AzureContentUnderstandingOptions options = AzureContentUnderstandingOptions.builder()
                .endpoint("https://resource.services.ai.azure.com/")
                .authenticationProvider((request, cancellation) -> CompletableFuture.completedStage(
                        new AzureAccessToken("token-secret", Instant.now().plusSeconds(3600))))
                .pollInterval(Duration.ofMillis(1))
                .operationTimeout(Duration.ofSeconds(5))
                .build();
        ContentUnderstandingSdkTransport transport = ContentUnderstandingSdkTransport.create(options, http);
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        java.util.concurrent.CompletionStage<ContentAnalysisResult> result = transport.analyzeAsync(
                new ContentAnalysisRequest(
                        "prebuilt-layout",
                        List.of(new ContentBytesInput(new byte[] {1, 2, 3}, "doc.pdf", "application/pdf", null)),
                        Map.of()),
                cancellation);

        cancellation.cancel();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> result.toCompletableFuture().join())
                .hasRootCauseInstanceOf(com.microsoft.agents.core.RunCancelledException.class);
        assertThat(http.cancelled).isTrue();
        assertThat(http.requests)
                .noneSatisfy(request ->
                        assertThat(request.getHttpMethod().toString()).isEqualTo("DELETE"));
    }

    private static final class RecordingHttpClient implements com.azure.core.http.HttpClient {
        private final AtomicReference<HttpRequest> initial = new AtomicReference<>();

        @Override
        public Mono<HttpResponse> send(HttpRequest request) {
            if (request.getHttpMethod() == com.azure.core.http.HttpMethod.POST) {
                initial.set(request);
                return Mono.just(new StringHttpResponse(
                        request,
                        202,
                        "",
                        new HttpHeaders()
                                .set(
                                        HttpHeaderName.fromString("operation-location"),
                                        "https://resource.services.ai.azure.com/"
                                                + "contentunderstanding/analyzers/prebuilt-layout/"
                                                + "operations/operation-one?api-version=2025-11-01")));
            }

            return Mono.just(new StringHttpResponse(request, 200, """
                    {
                      "id":"operation-one",
                      "status":"Succeeded",
                      "result":{
                        "analyzerId":"prebuilt-layout",
                        "apiVersion":"2025-11-01",
                        "createdAt":"2026-08-10T00:00:00Z",
                        "stringEncoding":"codePoint",
                        "warnings":[],
                        "contents":[{
                          "kind":"document",
                          "mimeType":"application/pdf",
                          "analyzerId":"prebuilt-layout",
                          "markdown":"# Document",
                          "fields":{}
                        }]
                      },
                      "usage":{"documentPagesStandard":1}
                    }
                    """, new HttpHeaders()));
        }
    }

    private static final class AnalyzerCrudHttpClient implements com.azure.core.http.HttpClient {
        private static final String ANALYZER = """
                {"analyzerId":"custom-one","status":"ready",
                 "description":"Custom analyzer",
                 "baseAnalyzerId":"prebuilt-document","tags":{}}
                """;

        private final List<HttpRequest> requests = new ArrayList<>();

        @Override
        public Mono<HttpResponse> send(HttpRequest request) {
            requests.add(request);
            if (request.getHttpMethod() == com.azure.core.http.HttpMethod.PUT) {
                return Mono.just(new StringHttpResponse(
                        request,
                        201,
                        "",
                        new HttpHeaders()
                                .set(
                                        HttpHeaderName.fromString("operation-location"),
                                        "https://resource.services.ai.azure.com/"
                                                + "contentunderstanding/analyzers/custom-one/"
                                                + "operations/create-one?api-version=2025-11-01")));
            }
            if (request.getHttpMethod() == com.azure.core.http.HttpMethod.DELETE) {
                return Mono.just(new StringHttpResponse(request, 204, "", new HttpHeaders()));
            }
            if (request.getHttpMethod() == com.azure.core.http.HttpMethod.GET
                    && request.getUrl().getPath().contains("/operations/")) {
                return Mono.just(new StringHttpResponse(
                        request,
                        200,
                        "{\"id\":\"create-one\",\"status\":\"Succeeded\",\"result\":" + ANALYZER + "}",
                        new HttpHeaders()));
            }
            if (request.getHttpMethod() == com.azure.core.http.HttpMethod.GET
                    && request.getUrl().getPath().endsWith("/analyzers")) {
                return Mono.just(
                        new StringHttpResponse(request, 200, "{\"value\":[" + ANALYZER + "]}", new HttpHeaders()));
            }
            return Mono.just(new StringHttpResponse(request, 200, ANALYZER, new HttpHeaders()));
        }
    }

    private static final class NeverHttpClient implements com.azure.core.http.HttpClient {
        private final List<HttpRequest> requests = new ArrayList<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public Mono<HttpResponse> send(HttpRequest request) {
            requests.add(request);
            return Mono.<HttpResponse>never().doOnCancel(() -> cancelled.set(true));
        }
    }

    private static final class StringHttpResponse extends HttpResponse {
        private final int status;
        private final byte[] body;
        private final HttpHeaders headers;

        private StringHttpResponse(HttpRequest request, int status, String body, HttpHeaders headers) {
            super(request);
            this.status = status;
            this.body = body.getBytes(StandardCharsets.UTF_8);
            this.headers = headers.set(HttpHeaderName.CONTENT_TYPE, "application/json")
                    .set(HttpHeaderName.CONTENT_LENGTH, Integer.toString(this.body.length))
                    .set(HttpHeaderName.fromString("x-request-id"), "request-one");
        }

        @Override
        public int getStatusCode() {
            return status;
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

        @Override
        public BinaryData getBodyAsBinaryData() {
            return BinaryData.fromBytes(body);
        }
    }
}
