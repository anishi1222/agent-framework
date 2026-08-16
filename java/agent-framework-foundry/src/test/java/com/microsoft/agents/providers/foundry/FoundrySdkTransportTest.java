// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.exception.ClientAuthenticationException;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.BinaryData;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.ObjectMappers;
import com.openai.core.http.HttpResponseFor;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.services.async.ResponseServiceAsync;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class FoundrySdkTransportTest {
    @Test
    void sdkTransport_shouldUseStableProjectOpenAIModelSurface() {
        RecordingHttpClient http = new RecordingHttpClient();
        FoundryChatClientOptions options = modelOptions(validCredential());
        FoundryChatClient client = FoundryChatClient.builder()
                .options(options)
                .transport(FoundrySdkTransport.create(options, http), true)
                .build();

        var response = client.completeAsync(new ChatClientRequest(
                        List.of(Message.text(Role.USER, "hello")),
                        ChatOptions.builder().conversationId("conversation-1").build()))
                .toCompletableFuture()
                .join();

        HttpRequest sent = http.request.get();
        assertThat(sent.getUrl().toString())
                .startsWith("https://resource.services.ai.azure.com/api/projects/project-one")
                .contains("/openai/v1/responses");
        assertThat(sent.getHeaders().getValue(HttpHeaderName.AUTHORIZATION)).startsWith("Bearer ");
        assertThat(sent.getBodyAsBinaryData().toString())
                .contains("\"model\":\"deployment\"")
                .contains("\"conversation\":\"conversation-1\"");
        assertThat(response.text()).isEqualTo("done");
        assertThat(response.conversationId()).isEqualTo("conversation-1");
        assertThat(response.usage().totalTokens().orElseThrow().longValueExact())
                .isEqualTo(2);
    }

    @Test
    void sdkTransport_shouldInjectVersionedAgentReferenceAndRemoveModel() {
        RecordingHttpClient http = new RecordingHttpClient();
        FoundryChatClientOptions options = FoundryChatClientOptions.builder()
                .projectEndpoint(endpoint())
                .agentName("weather-agent")
                .agentVersion("7")
                .tokenCredential(validCredential())
                .build();
        FoundryChatClient client = FoundryChatClient.builder()
                .options(options)
                .transport(FoundrySdkTransport.create(options, http), true)
                .build();

        client.completeAsync(new ChatClientRequest(List.of(Message.text(Role.USER, "hello")), ChatOptions.empty()))
                .toCompletableFuture()
                .join();

        String body = http.request.get().getBodyAsBinaryData().toString();
        assertThat(body)
                .contains("\"agent_reference\"")
                .contains("\"name\":\"weather-agent\"")
                .contains("\"version\":\"7\"")
                .doesNotContain("\"model\"");
    }

    @Test
    void sdkTransport_shouldSanitizeTokenCredentialFailure() {
        TokenCredential failed = context -> Mono.error(new ClientAuthenticationException("credential-secret", null));
        FoundryChatClientOptions options = modelOptions(failed);
        FoundryChatClient client = FoundryChatClient.builder()
                .options(options)
                .transport(FoundrySdkTransport.create(options, new RecordingHttpClient()), true)
                .build();

        assertThatThrownBy(() -> client.completeAsync(
                                new ChatClientRequest(List.of(Message.text(Role.USER, "hello")), ChatOptions.empty()))
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOfSatisfying(FoundryProviderException.class, failure -> {
                    assertThat(failure.getMessage()).doesNotContain("credential-secret");
                    assertThat(failure.kind()).isEqualTo(FoundryProviderException.Kind.AUTHENTICATION);
                });
    }

    @Test
    void sdkTransport_shouldMapOfficialOpenAIStreamingModelsAndCloseResources() throws Exception {
        // Arrange
        OpenAIClientAsync sdkClient = mock(OpenAIClientAsync.class);
        ResponseServiceAsync responses = mock(ResponseServiceAsync.class);
        ResponseServiceAsync.WithRawResponse rawService = mock(ResponseServiceAsync.WithRawResponse.class);
        @SuppressWarnings("unchecked")
        HttpResponseFor<StreamResponse<ResponseStreamEvent>> raw = mock(HttpResponseFor.class);
        @SuppressWarnings("unchecked")
        StreamResponse<ResponseStreamEvent> stream = mock(StreamResponse.class);
        when(sdkClient.responses()).thenReturn(responses);
        when(responses.withRawResponse()).thenReturn(rawService);
        when(rawService.createStreaming(any(ResponseCreateParams.class)))
                .thenReturn(CompletableFuture.completedFuture(raw));
        when(raw.requestId()).thenReturn(Optional.of("request-stream"));
        when(raw.parse()).thenReturn(stream);
        when(stream.stream()).thenReturn(Stream.of(event("""
                        {"type":"response.created","sequence_number":0,
                         "response":{"id":"resp-stream","created_at":1,"metadata":{},
                         "model":"deployment","output":[],"status":"in_progress"}}
                        """), event("""
                        {"type":"response.output_text.delta","sequence_number":1,
                         "item_id":"msg-stream","output_index":0,"content_index":0,
                         "delta":"done","logprobs":[]}
                        """), event("""
                        {"type":"response.completed","sequence_number":2,
                         "response":{"id":"resp-stream","created_at":1,"metadata":{},
                         "model":"deployment","status":"completed",
                         "output":[{"id":"msg-stream","type":"message","role":"assistant",
                         "status":"completed","content":[{"type":"output_text","text":"done",
                         "annotations":[]}]}]}}
                        """)));
        FoundryChatClientOptions options = modelOptions(validCredential());
        FoundryChatClient client = FoundryChatClient.builder()
                .options(options)
                .transport(new FoundrySdkTransport(options, sdkClient), true)
                .build();

        // Act
        List<ChatResponseUpdate> updates = collect(client.completeStreaming(
                        new ChatClientRequest(List.of(Message.text(Role.USER, "hello")), ChatOptions.empty())))
                .join();
        client.close();

        // Assert
        assertThat(updates.stream().map(ChatResponseUpdate::text).reduce("", String::concat))
                .isEqualTo("done");
        assertThat(updates).allSatisfy(update -> assertThat(update.metadata()).containsKey("foundry.requestId"));
        verify(stream).close();
        verify(raw).close();
        verify(sdkClient).close();
    }

    private static FoundryChatClientOptions modelOptions(TokenCredential credential) {
        return FoundryChatClientOptions.builder()
                .projectEndpoint(endpoint())
                .model("deployment")
                .tokenCredential(credential)
                .build();
    }

    private static TokenCredential validCredential() {
        return context ->
                Mono.just(new AccessToken("token-secret", OffsetDateTime.now().plusHours(1)));
    }

    private static String endpoint() {
        return "https://resource.services.ai.azure.com/api/projects/project-one";
    }

    private static String responseJson() {
        return """
                {
                  "id":"resp-1","object":"response","created_at":1,"status":"completed",
                  "error":null,"incomplete_details":null,"instructions":null,
                  "model":"deployment",
                  "output":[{"type":"message","id":"msg-1","status":"completed","role":"assistant",
                             "content":[{"type":"output_text","text":"done","annotations":[]}]}],
                  "parallel_tool_calls":true,"previous_response_id":null,
                  "conversation":{"id":"conversation-1"},
                  "temperature":1.0,"tool_choice":"auto","tools":[],"top_p":1.0,
                  "metadata":{},"usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2,
                  "input_tokens_details":{"cached_tokens":0},
                  "output_tokens_details":{"reasoning_tokens":0}}
                }
                """;
    }

    private static ResponseStreamEvent event(String json) throws Exception {
        return ObjectMappers.jsonMapper().readValue(json, ResponseStreamEvent.class);
    }

    private static CompletableFuture<List<ChatResponseUpdate>> collect(Flow.Publisher<ChatResponseUpdate> publisher) {
        java.util.ArrayList<ChatResponseUpdate> updates = new java.util.ArrayList<>();
        CompletableFuture<List<ChatResponseUpdate>> result = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ChatResponseUpdate item) {
                updates.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.complete(List.copyOf(updates));
            }
        });
        return result;
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
                    .set(HttpHeaderName.fromString("x-request-id"), "request-1");
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
