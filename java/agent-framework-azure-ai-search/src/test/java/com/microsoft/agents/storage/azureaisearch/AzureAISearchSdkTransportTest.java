// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.azureaisearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.BinaryData;
import com.microsoft.agents.agents.memory.EmbeddingVector;
import com.microsoft.agents.agents.memory.MemoryScope;
import com.microsoft.agents.azure.AzureAccessToken;
import com.microsoft.agents.azure.AzureTokenRequest;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AzureAISearchSdkTransportTest {
    @Test
    void sdkTransport_shouldUseStableSdkRouteAndMandatoryScopedFilter() {
        RecordingHttpClient http = new RecordingHttpClient(indexJson(false), searchJson(false));
        AzureAISearchOptions options =
                indexOptions().staticFilter("enabled eq true").build();
        AzureAISearchSdkTransport transport = AzureAISearchSdkTransport.create(options, http);

        List<AzureAISearchResult> results = transport
                .searchAsync(
                        new AzureAISearchRequest(new MemoryScope("tenant'o", "scope"), "hello", null),
                        new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        assertThat(http.requests).hasSize(2);
        HttpRequest index = http.requests.get(0);
        HttpRequest search = http.requests.get(1);
        assertThat(index.getHttpMethod()).isEqualTo(HttpMethod.GET);
        assertThat(index.getUrl().toString()).contains("/indexes('documents')").contains("api-version=2026-04-01");
        assertThat(search.getHttpMethod()).isEqualTo(HttpMethod.POST);
        assertThat(search.getUrl().toString())
                .contains("/indexes('documents')/docs/search.post.search")
                .contains("api-version=2026-04-01");
        assertThat(search.getHeaders().getValue(HttpHeaderName.fromString("api-key")))
                .isEqualTo("test-key");
        assertThat(search.getBodyAsBinaryData().toString())
                .contains("\"search\":\"hello\"")
                .contains("\"searchFields\":\"content\"")
                .contains("\"top\":5")
                .contains("\"filter\":\"(tenantId eq 'tenant''o' and scopeId eq 'scope') and (enabled eq true)\"")
                .contains("\"select\":\"id,content,title,sourceUrl\"");
        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.recordId()).isEqualTo("doc-one");
            assertThat(result.text()).isEqualTo("<unsafe>content</unsafe>");
            assertThat(result.citation()).isEqualTo("https://example.com/doc-one");
            assertThat(result.score()).isEqualTo(1.25);
        });
    }

    @Test
    void semanticHybrid_shouldUseCaptionPreFilterAndAtLeastFiftyVectorCandidates() {
        RecordingHttpClient http = new RecordingHttpClient(indexJson(true), searchJson(true));
        AzureAISearchOptions options = indexOptions()
                .mode(AzureAISearchQueryMode.SEMANTIC_HYBRID)
                .semanticConfigurationName("semantic")
                .fieldMapping(AzureAISearchFieldMapping.builder()
                        .vectorField("embedding")
                        .build())
                .embeddingProvider((request, cancellation) ->
                        Mono.just(new EmbeddingVector(List.of(1.0, 2.0, 3.0))).toFuture())
                .build();
        AzureAISearchSdkTransport transport = AzureAISearchSdkTransport.create(options, http);

        List<AzureAISearchResult> results = transport
                .searchAsync(
                        new AzureAISearchRequest(
                                new MemoryScope("tenant", "scope"),
                                "semantic query",
                                new EmbeddingVector(List.of(1.0, 2.0, 3.0))),
                        new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        String body = http.requests.get(1).getBodyAsBinaryData().toString();
        assertThat(body)
                .contains("\"queryType\":\"semantic\"")
                .contains("\"semanticConfiguration\":\"semantic\"")
                .contains("\"captions\":\"extractive\"")
                .contains("\"vectorFilterMode\":\"preFilter\"")
                .contains("\"k\":50")
                .contains("\"fields\":\"embedding\"")
                .contains("\"vector\":[1.0,2.0,3.0]");
        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.text()).isEqualTo("semantic caption");
            assertThat(result.score()).isEqualTo(2.5);
        });
    }

    @Test
    void vectorSearch_shouldDiscoverSingleFieldAndUseServerSideVectorizer() {
        RecordingHttpClient http = new RecordingHttpClient(vectorIndexJson(), searchJson(false));
        AzureAISearchOptions options =
                indexOptions().mode(AzureAISearchQueryMode.VECTOR).build();
        AzureAISearchSdkTransport transport = AzureAISearchSdkTransport.create(options, http);

        transport
                .searchAsync(
                        new AzureAISearchRequest(new MemoryScope("tenant", "scope"), "vector query", null),
                        new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        assertThat(http.requests.get(1).getBodyAsBinaryData().toString())
                .contains("\"kind\":\"text\"")
                .contains("\"text\":\"vector query\"")
                .contains("\"fields\":\"embedding\"")
                .contains("\"k\":5")
                .contains("\"vectorFilterMode\":\"preFilter\"");
    }

    @Test
    void vectorSearch_shouldRejectClientEmbeddingDimensionMismatch() {
        RecordingHttpClient http = new RecordingHttpClient(indexJson(true), searchJson(false));
        AzureAISearchOptions options = indexOptions()
                .mode(AzureAISearchQueryMode.VECTOR)
                .fieldMapping(AzureAISearchFieldMapping.builder()
                        .vectorField("embedding")
                        .build())
                .embeddingProvider((request, cancellation) ->
                        Mono.just(new EmbeddingVector(List.of(1.0, 2.0))).toFuture())
                .build();
        AzureAISearchSdkTransport transport = AzureAISearchSdkTransport.create(options, http);

        assertThatThrownBy(() -> transport
                        .searchAsync(
                                new AzureAISearchRequest(
                                        new MemoryScope("tenant", "scope"),
                                        "vector query",
                                        new EmbeddingVector(List.of(1.0, 2.0))),
                                new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(AzureAISearchValidationException.class);
        assertThat(http.requests).hasSize(1);
    }

    @Test
    void vectorSearch_shouldRejectAmbiguousVectorFieldDiscovery() {
        RecordingHttpClient http = new RecordingHttpClient(ambiguousVectorIndexJson(), searchJson(false));
        AzureAISearchSdkTransport transport = AzureAISearchSdkTransport.create(
                indexOptions().mode(AzureAISearchQueryMode.VECTOR).build(), http);

        assertThatThrownBy(() -> transport
                        .searchAsync(
                                new AzureAISearchRequest(new MemoryScope("tenant", "scope"), "vector query", null),
                                new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(AzureAISearchValidationException.class);
        assertThat(http.requests).hasSize(1);
    }

    @Test
    void vectorSearch_shouldRejectProfileWithoutServerVectorizer() {
        RecordingHttpClient http = new RecordingHttpClient(
                vectorIndexJson().replace("\"vectorizer\":\"vectorizer\"", "\"vectorizer\":\"missing\""),
                searchJson(false));
        AzureAISearchSdkTransport transport = AzureAISearchSdkTransport.create(
                indexOptions().mode(AzureAISearchQueryMode.VECTOR).build(), http);

        assertThatThrownBy(() -> transport
                        .searchAsync(
                                new AzureAISearchRequest(new MemoryScope("tenant", "scope"), "vector query", null),
                                new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(AzureAISearchValidationException.class);
        assertThat(http.requests).hasSize(1);
    }

    @Test
    void schemaValidation_shouldRejectNonFilterableScopeField() {
        RecordingHttpClient http = new RecordingHttpClient(
                indexJson(false)
                        .replace(
                                "\"name\":\"scopeId\",\"type\":\"Edm.String\",\"filterable\":true",
                                "\"name\":\"scopeId\",\"type\":\"Edm.String\",\"filterable\":false"),
                searchJson(false));
        AzureAISearchSdkTransport transport =
                AzureAISearchSdkTransport.create(indexOptions().build(), http);

        assertThatThrownBy(() -> transport
                        .searchAsync(
                                new AzureAISearchRequest(new MemoryScope("tenant", "scope"), "query", null),
                                new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(AzureAISearchValidationException.class);
        assertThat(http.requests).hasSize(1);
    }

    @Test
    void rbacAuthentication_shouldUseConfiguredSovereignAudienceScope() {
        RecordingHttpClient http = new RecordingHttpClient(indexJson(false), searchJson(false));
        AtomicReference<AzureTokenRequest> tokenRequest = new AtomicReference<>();
        AzureAISearchAuthentication authentication = AzureAISearchAuthentication.rbac((request, cancellation) -> {
            tokenRequest.set(request);
            return Mono.just(new AzureAccessToken("token-secret", Instant.now().plusSeconds(3600)))
                    .toFuture();
        });
        AzureAISearchOptions options = AzureAISearchOptions.forIndex(
                        AzureAISearchEndpoint.of("https://search.example.us"), "documents", authentication)
                .audience(AzureAISearchAudience.AZURE_GOVERNMENT)
                .build();
        AzureAISearchSdkTransport transport = AzureAISearchSdkTransport.create(options, http);

        transport
                .searchAsync(
                        new AzureAISearchRequest(new MemoryScope("tenant", "scope"), "hello", null),
                        new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        assertThat(tokenRequest.get().scopes()).containsExactly("https://search.azure.us/.default");
        assertThat(http.requests.get(1).getHeaders().getValue(HttpHeaderName.AUTHORIZATION))
                .isEqualTo("Bearer token-secret");
    }

    @Test
    void agenticRetrieval_shouldValidateIndexSourcesAndApplyFilterAddOnToEverySource() {
        AgenticHttpClient http = new AgenticHttpClient();
        AzureAISearchOptions options = AzureAISearchOptions.forKnowledgeBase(
                        AzureAISearchEndpoint.of("https://search.example.com"),
                        "knowledge-base",
                        AzureAISearchAuthentication.apiKey(AzureAISearchApiKey.of("test-key")))
                .build();
        AzureAISearchSdkTransport transport = AzureAISearchSdkTransport.create(options, http);

        List<AzureAISearchResult> results = transport
                .searchAsync(
                        new AzureAISearchRequest(new MemoryScope("tenant'o", "scope"), "agentic query", null),
                        new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        assertThat(http.requests).hasSize(4);
        assertThat(http.requests)
                .extracting(request -> request.getUrl().getPath())
                .anySatisfy(path -> assertThat(path).contains("/knowledgebases('knowledge-base')"))
                .anySatisfy(path -> assertThat(path.toLowerCase()).contains("/knowledgesources('source-one')"))
                .anySatisfy(path -> assertThat(path).contains("/indexes('documents')"))
                .anySatisfy(path -> assertThat(path).contains("/knowledgebases('knowledge-base')/retrieve"));
        HttpRequest retrieve = http.requests.stream()
                .filter(request -> request.getHttpMethod() == HttpMethod.POST)
                .findFirst()
                .orElseThrow();
        assertThat(retrieve.getBodyAsBinaryData().toString())
                .contains("\"intents\":[{\"search\":\"agentic query\",\"type\":\"semantic\"}]")
                .contains("\"knowledgeSourceName\":\"source-one\"")
                .contains("\"kind\":\"searchIndex\"")
                .contains("\"includeReferences\":true")
                .contains("\"includeReferenceSourceData\":true")
                .contains("\"filterAddOn\":\"tenantId eq 'tenant''o' and scopeId eq 'scope'\"");
        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.recordId()).isEqualTo("doc-one");
            assertThat(result.text()).isEqualTo("source reference content");
            assertThat(result.text()).doesNotContain("generated response");
            assertThat(result.citation()).isEqualTo("https://example.com/doc-one");
            assertThat(result.score()).isEqualTo(3.25);
        });
    }

    @Test
    void agenticRetrieval_shouldRejectNonIndexKnowledgeSource() {
        NonIndexKnowledgeSourceHttpClient http = new NonIndexKnowledgeSourceHttpClient();
        AzureAISearchOptions options = AzureAISearchOptions.forKnowledgeBase(
                        AzureAISearchEndpoint.of("https://search.example.com"),
                        "knowledge-base",
                        AzureAISearchAuthentication.apiKey(AzureAISearchApiKey.of("test-key")))
                .build();
        AzureAISearchSdkTransport transport = AzureAISearchSdkTransport.create(options, http);

        assertThatThrownBy(() -> transport
                        .searchAsync(
                                new AzureAISearchRequest(new MemoryScope("tenant", "scope"), "agentic query", null),
                                new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(AzureAISearchValidationException.class);
        assertThat(http.requests).extracting(request -> request.getHttpMethod()).containsOnly(HttpMethod.GET);
    }

    @Test
    void agenticRetrieval_shouldUseGeneratedResponseWhenReferencesAreAbsent() {
        AgenticHttpClient http = new AgenticHttpClient(false);
        AzureAISearchOptions options = AzureAISearchOptions.forKnowledgeBase(
                        AzureAISearchEndpoint.of("https://search.example.com"),
                        "knowledge-base",
                        AzureAISearchAuthentication.apiKey(AzureAISearchApiKey.of("test-key")))
                .build();
        AzureAISearchSdkTransport transport = AzureAISearchSdkTransport.create(options, http);

        List<AzureAISearchResult> results = transport
                .searchAsync(
                        new AzureAISearchRequest(new MemoryScope("tenant", "scope"), "agentic query", null),
                        new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.recordId()).isEqualTo("agentic-response");
            assertThat(result.text()).isEqualTo("generated response");
            assertThat(result.citation()).isEqualTo("azure-search://agentic/response");
        });
    }

    @Test
    void sdkTransport_shouldRejectMalformedSuccessfulDocuments() {
        RecordingHttpClient http = new RecordingHttpClient(indexJson(false), """
                        {
                          "@odata.context":"https://example/$metadata#docs(*)",
                          "value":[{"@search.score":1.0,"id":"doc-one"}]
                        }
                        """);
        AzureAISearchSdkTransport transport =
                AzureAISearchSdkTransport.create(indexOptions().build(), http);

        assertThatThrownBy(() -> transport
                        .searchAsync(
                                new AzureAISearchRequest(new MemoryScope("tenant", "scope"), "hello", null),
                                new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOfSatisfying(
                        AzureAISearchException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(AzureAISearchException.Kind.DATA_CONTRACT));
    }

    @Test
    void sdkTransport_shouldClassifyInvalidAzureJsonAsDataContractFailure() {
        RecordingHttpClient http = new RecordingHttpClient(indexJson(false), "{");
        AzureAISearchSdkTransport transport =
                AzureAISearchSdkTransport.create(indexOptions().build(), http);

        assertThatThrownBy(() -> transport
                        .searchAsync(
                                new AzureAISearchRequest(new MemoryScope("tenant", "scope"), "query", null),
                                new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOfSatisfying(
                        AzureAISearchException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(AzureAISearchException.Kind.DATA_CONTRACT));
    }

    @Test
    void sdkTransportCancellation_shouldCancelPendingSdkRequest() {
        NeverSearchHttpClient http = new NeverSearchHttpClient(indexJson(false));
        AzureAISearchSdkTransport transport = AzureAISearchSdkTransport.create(
                indexOptions().operationTimeout(Duration.ofSeconds(5)).build(), http);
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        var result = transport.searchAsync(
                new AzureAISearchRequest(new MemoryScope("tenant", "scope"), "hello", null), cancellation);

        cancellation.cancel();

        assertThatThrownBy(() -> result.toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(RunCancelledException.class);
        assertThat(http.cancelled).isTrue();
    }

    @Test
    void sdkTransportTimeout_shouldMapToNonSecretTimeoutFailure() {
        NeverSearchHttpClient http = new NeverSearchHttpClient(indexJson(false));
        AzureAISearchSdkTransport transport = AzureAISearchSdkTransport.create(
                indexOptions().operationTimeout(Duration.ofMillis(25)).build(), http);

        assertThatThrownBy(() -> transport
                        .searchAsync(
                                new AzureAISearchRequest(new MemoryScope("tenant", "scope"), "secret query", null),
                                new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOfSatisfying(AzureAISearchException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(AzureAISearchException.Kind.TIMEOUT);
                    assertThat(failure.getMessage()).doesNotContain("secret query");
                });
    }

    @Test
    void sdkSupport_shouldPreserveBoundedRetryDiagnosticsForThrottling() {
        HttpRequest request = new HttpRequest(HttpMethod.GET, "https://search.example.com");
        HttpHeaders headers = new HttpHeaders()
                .set(HttpHeaderName.fromString("x-ms-retry-after-ms"), "1500")
                .set(HttpHeaderName.fromString("x-ms-request-id"), "request-one");
        HttpResponse response = new StringHttpResponse(request, 429, "{}", headers);

        RuntimeException mapped =
                AzureAISearchSdkSupport.mapFailure(new HttpResponseException("throttled", response), "search");

        assertThat(mapped).isInstanceOfSatisfying(AzureAISearchException.class, failure -> {
            assertThat(failure.kind()).isEqualTo(AzureAISearchException.Kind.SERVICE);
            assertThat(failure.statusCode()).isEqualTo(429);
            assertThat(failure.requestId()).isEqualTo("request-one");
            assertThat(failure.retryAfter()).isEqualTo(Duration.ofMillis(1500));
            assertThat(failure.continuable()).isTrue();
        });
    }

    private static AzureAISearchOptions.Builder indexOptions() {
        return AzureAISearchOptions.forIndex(
                AzureAISearchEndpoint.of("https://search.example.com"),
                "documents",
                AzureAISearchAuthentication.apiKey(AzureAISearchApiKey.of("test-key")));
    }

    private static String indexJson(boolean semanticVector) {
        String additional = semanticVector ? """
                  ,{"name":"embedding","type":"Collection(Edm.Single)",
                    "searchable":true,"retrievable":false,
                    "dimensions":3,"vectorSearchProfile":"profile"}
                  ],
                  "semantic":{"configurations":[{
                    "name":"semantic",
                    "prioritizedFields":{
                      "titleField":{"fieldName":"title"},
                      "prioritizedContentFields":[{"fieldName":"content"}],
                      "prioritizedKeywordsFields":[]
                    }
                  }]}
                  """ : "]\n";
        return """
                {
                  "name":"documents",
                  "fields":[
                    {"name":"id","type":"Edm.String","key":true,"retrievable":true},
                    {"name":"content","type":"Edm.String","searchable":true,"retrievable":true},
                    {"name":"title","type":"Edm.String","retrievable":true},
                    {"name":"sourceUrl","type":"Edm.String","retrievable":true},
                    {"name":"tenantId","type":"Edm.String","filterable":true},
                    {"name":"scopeId","type":"Edm.String","filterable":true}
                """ + additional + "}";
    }

    private static String searchJson(boolean semantic) {
        String semanticProperties = semantic ? """
                  ,"@search.rerankerScore":2.5,
                  "@search.captions":[{"text":"semantic caption","highlights":""}]
                  """ : "";
        return """
                {
                  "@odata.context":"https://example/$metadata#docs(*)",
                  "value":[{
                    "@search.score":1.25
                """ + semanticProperties + """
                    ,"id":"doc-one",
                    "content":"<unsafe>content</unsafe>",
                    "title":"Document One",
                    "sourceUrl":"https://example.com/doc-one"
                  }]
                }
                """;
    }

    private static String vectorIndexJson() {
        return """
                {
                  "name":"documents",
                  "fields":[
                    {"name":"id","type":"Edm.String","key":true,"retrievable":true},
                    {"name":"content","type":"Edm.String","retrievable":true},
                    {"name":"title","type":"Edm.String","retrievable":true},
                    {"name":"sourceUrl","type":"Edm.String","retrievable":true},
                    {"name":"tenantId","type":"Edm.String","filterable":true},
                    {"name":"scopeId","type":"Edm.String","filterable":true},
                    {"name":"embedding","type":"Collection(Edm.Single)",
                     "searchable":true,"retrievable":false,
                     "dimensions":3,"vectorSearchProfile":"profile"}
                  ],
                  "vectorSearch":{
                    "algorithms":[{"name":"hnsw","kind":"hnsw"}],
                    "profiles":[{
                      "name":"profile",
                      "algorithm":"hnsw",
                      "vectorizer":"vectorizer"
                    }],
                    "vectorizers":[{
                      "name":"vectorizer",
                      "kind":"azureOpenAI",
                      "azureOpenAIParameters":{
                        "resourceUri":"https://openai.example.com",
                        "deploymentId":"embedding",
                        "modelName":"text-embedding-3-small"
                      }
                    }]
                  }
                }
                """;
    }

    private static String ambiguousVectorIndexJson() {
        return """
                {
                  "name":"documents",
                  "fields":[
                    {"name":"id","type":"Edm.String","key":true,"retrievable":true},
                    {"name":"content","type":"Edm.String","retrievable":true},
                    {"name":"title","type":"Edm.String","retrievable":true},
                    {"name":"sourceUrl","type":"Edm.String","retrievable":true},
                    {"name":"tenantId","type":"Edm.String","filterable":true},
                    {"name":"scopeId","type":"Edm.String","filterable":true},
                    {"name":"embedding","type":"Collection(Edm.Single)",
                     "searchable":true,"retrievable":false,
                     "dimensions":3,"vectorSearchProfile":"profile"},
                    {"name":"summaryEmbedding","type":"Collection(Edm.Single)",
                     "searchable":true,"retrievable":false,
                     "dimensions":3,"vectorSearchProfile":"profile"}
                  ],
                  "vectorSearch":{
                    "algorithms":[{"name":"hnsw","kind":"hnsw"}],
                    "profiles":[{
                      "name":"profile",
                      "algorithm":"hnsw",
                      "vectorizer":"vectorizer"
                    }],
                    "vectorizers":[{
                      "name":"vectorizer",
                      "kind":"azureOpenAI",
                      "azureOpenAIParameters":{
                        "resourceUri":"https://openai.example.com",
                        "deploymentId":"embedding",
                        "modelName":"text-embedding-3-small"
                      }
                    }]
                  }
                }
                """;
    }

    private static final class RecordingHttpClient implements com.azure.core.http.HttpClient {
        private final String indexBody;

        private final String searchBody;

        private final List<HttpRequest> requests = new CopyOnWriteArrayList<>();

        private RecordingHttpClient(String indexBody, String searchBody) {
            this.indexBody = indexBody;
            this.searchBody = searchBody;
        }

        @Override
        public Mono<HttpResponse> send(HttpRequest request) {
            requests.add(request);
            return Mono.just(new StringHttpResponse(
                    request, 200, request.getHttpMethod() == HttpMethod.GET ? indexBody : searchBody));
        }
    }

    private static final class NeverSearchHttpClient implements com.azure.core.http.HttpClient {
        private final String indexBody;

        private final AtomicBoolean cancelled = new AtomicBoolean();

        private NeverSearchHttpClient(String indexBody) {
            this.indexBody = indexBody;
        }

        @Override
        public Mono<HttpResponse> send(HttpRequest request) {
            if (request.getHttpMethod() == HttpMethod.GET) {
                return Mono.just(new StringHttpResponse(request, 200, indexBody));
            }
            return Mono.<HttpResponse>never().doOnCancel(() -> cancelled.set(true));
        }
    }

    private static final class AgenticHttpClient implements com.azure.core.http.HttpClient {
        private final List<HttpRequest> requests = new CopyOnWriteArrayList<>();

        private final boolean includeReferences;

        private AgenticHttpClient() {
            this(true);
        }

        private AgenticHttpClient(boolean includeReferences) {
            this.includeReferences = includeReferences;
        }

        @Override
        public Mono<HttpResponse> send(HttpRequest request) {
            requests.add(request);
            String path = request.getUrl().getPath();
            String lower = path.toLowerCase();
            if (request.getHttpMethod() == HttpMethod.POST) {
                return response(request, includeReferences ? """
                        {
                          "response":[{
                            "role":"assistant",
                            "content":[{
                              "type":"text",
                              "text":"generated response"
                            }]
                          }],
                          "activity":[],
                          "references":[{
                            "type":"searchIndex",
                            "id":"reference-one",
                            "activitySource":0,
                            "docKey":"doc-one",
                            "sourceData":{
                              "id":"doc-one",
                              "content":"source reference content",
                              "title":"Document One",
                              "sourceUrl":"https://example.com/doc-one"
                            },
                            "rerankerScore":3.25
                          }]
                        }
                        """ : """
                        {
                          "response":[{
                            "role":"assistant",
                            "content":[{
                              "type":"text",
                              "text":"generated response"
                            }]
                          }],
                          "activity":[],
                          "references":[]
                        }
                        """);
            }
            if (lower.contains("knowledgesources")) {
                return response(request, """
                        {
                          "name":"source-one",
                          "kind":"searchIndex",
                          "searchIndexParameters":{
                            "searchIndexName":"documents",
                            "sourceDataFields":[
                              {"name":"id"},
                              {"name":"content"},
                              {"name":"title"},
                              {"name":"sourceUrl"}
                            ],
                            "searchFields":[{"name":"content"}],
                            "semanticConfigurationName":"semantic"
                          }
                        }
                        """);
            }
            if (lower.contains("knowledgebases")) {
                return response(request, """
                        {
                          "name":"knowledge-base",
                          "knowledgeSources":[{"name":"source-one"}],
                          "models":[]
                        }
                        """);
            }
            return response(request, indexJson(false));
        }

        private static Mono<HttpResponse> response(HttpRequest request, String body) {
            return Mono.just(new StringHttpResponse(request, 200, body));
        }
    }

    private static final class NonIndexKnowledgeSourceHttpClient implements com.azure.core.http.HttpClient {
        private final List<HttpRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public Mono<HttpResponse> send(HttpRequest request) {
            requests.add(request);
            String lower = request.getUrl().getPath().toLowerCase();
            if (lower.contains("knowledgesources")) {
                return Mono.just(new StringHttpResponse(request, 200, """
                        {
                          "name":"source-one",
                          "kind":"web",
                          "webParameters":{
                            "domains":{
                              "allowedDomains":[{
                                "address":"https://example.com",
                                "includeSubpages":true
                              }]
                            }
                          }
                        }
                        """));
            }
            return Mono.just(new StringHttpResponse(request, 200, """
                    {
                      "name":"knowledge-base",
                      "knowledgeSources":[{
                        "name":"source-one",
                        "kind":"web"
                      }]
                    }
                    """));
        }
    }

    private static final class StringHttpResponse extends HttpResponse {
        private final byte[] body;

        private final HttpHeaders headers;

        private StringHttpResponse(HttpRequest request, int status, String body) {
            this(
                    request,
                    status,
                    body,
                    new HttpHeaders().set(HttpHeaderName.fromString("x-ms-request-id"), "request-one"));
        }

        private StringHttpResponse(HttpRequest request, int status, String body, HttpHeaders headers) {
            super(request);
            this.status = status;
            this.body = body.getBytes(StandardCharsets.UTF_8);
            this.headers = headers.set(HttpHeaderName.CONTENT_TYPE, "application/json")
                    .set(HttpHeaderName.CONTENT_LENGTH, Integer.toString(this.body.length));
        }

        private final int status;

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
