// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.BinaryData;
import com.microsoft.agents.azure.AzureAccessToken;
import com.microsoft.agents.azure.AzureTokenRequest;
import com.microsoft.agents.core.DefaultRunCancellation;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class PersistentSdkTransportTest {
    @Test
    void sdkTransport_shouldUseVerifiedPathVersionScopeAndAuthorization() {
        RecordingHttpClient http = new RecordingHttpClient();
        AtomicReference<AzureTokenRequest> tokenRequest = new AtomicReference<>();
        AzureAIPersistentClientOptions options = AzureAIPersistentClientOptions.builder()
                .endpoint("https://resource.services.ai.azure.com/api/projects/project-one")
                .authenticationProvider((request, cancellation) -> {
                    tokenRequest.set(request);
                    return CompletableFuture.completedStage(
                            new AzureAccessToken("token-secret", Instant.now().plusSeconds(3600)));
                })
                .build();
        PersistentSdkTransport transport = PersistentSdkTransport.create(options, http);

        PersistentThread thread = transport
                .createThreadAsync(java.util.Map.of("owner", "framework"), new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        HttpRequest sent = http.request.get();
        assertThat(sent.getUrl().toString())
                .startsWith("https://resource.services.ai.azure.com/api/projects/project-one/threads")
                .contains("api-version=2025-05-15-preview");
        assertThat(sent.getHeaders().getValue(HttpHeaderName.AUTHORIZATION)).isEqualTo("Bearer token-secret");
        assertThat(sent.getBodyAsBinaryData().toString()).contains("\"metadata\":{\"owner\":\"framework\"}");
        assertThat(tokenRequest.get().scopes()).containsExactly("https://ai.azure.com/.default");
        assertThat(thread.id()).isEqualTo("thread-one");
    }

    @Test
    void sdkTransport_shouldMapRequiresActionAndSubmitExactToolOutputs() {
        RequiresActionHttpClient http = new RequiresActionHttpClient();
        AzureAIPersistentClientOptions options = AzureAIPersistentClientOptions.builder()
                .endpoint("https://resource.services.ai.azure.com/api/projects/project-one")
                .authenticationProvider((request, cancellation) -> CompletableFuture.completedStage(
                        new AzureAccessToken("token-secret", Instant.now().plusSeconds(3600))))
                .build();
        PersistentSdkTransport transport = PersistentSdkTransport.create(options, http);

        PersistentRun requiresAction = transport
                .getRunAsync("thread-one", "run-one", new DefaultRunCancellation())
                .toCompletableFuture()
                .join();
        PersistentRun continued = transport
                .submitToolOutputsAsync(
                        "thread-one",
                        "run-one",
                        List.of(new PersistentToolOutput("call-one", "{\"temperature\":72}")),
                        new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        assertThat(requiresAction.status()).isEqualTo(PersistentRunStatus.REQUIRES_ACTION);
        assertThat(requiresAction.requiredAction()).isNotNull();
        assertThat(requiresAction.requiredAction().supported()).isTrue();
        assertThat(requiresAction.requiredAction().toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("call-one");
            assertThat(call.name()).isEqualTo("weather");
            assertThat(call.argumentsJson()).isEqualTo("{\"city\":\"Seattle\"}");
            assertThat(call.supported()).isTrue();
        });
        assertThat(continued.status()).isEqualTo(PersistentRunStatus.IN_PROGRESS);
        assertThat(http.requests).hasSize(2);
        HttpRequest get = http.requests.get(0);
        assertThat(get.getHttpMethod().toString()).isEqualTo("GET");
        assertThat(get.getUrl().toString())
                .contains("/threads/thread-one/runs/run-one")
                .contains("api-version=2025-05-15-preview");
        HttpRequest submit = http.requests.get(1);
        assertThat(submit.getHttpMethod().toString()).isEqualTo("POST");
        assertThat(submit.getUrl().toString())
                .contains("/threads/thread-one/runs/run-one/submit_tool_outputs")
                .contains("api-version=2025-05-15-preview");
        assertThat(submit.getBodyAsBinaryData().toString())
                .contains("\"tool_call_id\":\"call-one\"")
                .contains("\\\"temperature\\\":72");
    }

    private static final class RecordingHttpClient implements com.azure.core.http.HttpClient {
        private final AtomicReference<HttpRequest> request = new AtomicReference<>();

        @Override
        public Mono<HttpResponse> send(HttpRequest request) {
            this.request.set(request);
            return Mono.just(new StringHttpResponse(request, """
                    {"id":"thread-one","object":"thread","created_at":1,
                     "metadata":{"owner":"framework"}}
                    """));
        }
    }

    private static final class RequiresActionHttpClient implements com.azure.core.http.HttpClient {
        private final List<HttpRequest> requests = new ArrayList<>();

        @Override
        public Mono<HttpResponse> send(HttpRequest request) {
            requests.add(request);
            if (request.getUrl().getPath().endsWith("/submit_tool_outputs")) {
                return Mono.just(new StringHttpResponse(request, """
                        {"id":"run-one","object":"thread.run","created_at":1,
                         "thread_id":"thread-one","assistant_id":"agent-one",
                         "status":"in_progress","metadata":{}}
                        """));
            }
            return Mono.just(new StringHttpResponse(request, """
                    {"id":"run-one","object":"thread.run","created_at":1,
                     "thread_id":"thread-one","assistant_id":"agent-one",
                     "status":"requires_action",
                     "required_action":{"type":"submit_tool_outputs",
                       "submit_tool_outputs":{"tool_calls":[{
                         "id":"call-one","type":"function",
                         "function":{"name":"weather",
                           "arguments":"{\\"city\\":\\"Seattle\\"}"}}]}},
                     "metadata":{}}
                    """));
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
                    .set(HttpHeaderName.fromString("x-request-id"), "request-one");
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
