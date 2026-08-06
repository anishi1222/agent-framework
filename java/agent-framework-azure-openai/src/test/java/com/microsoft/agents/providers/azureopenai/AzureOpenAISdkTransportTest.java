// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureopenai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.exception.ClientAuthenticationException;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.BinaryData;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AzureOpenAISdkTransportTest {
    @Test
    void sdkTransport_shouldApplyKeyAuthEndpointVersionAndProductionFiniteMapping() {
        // Arrange
        String sdkRoundTrip = BinaryData.fromObject(BinaryData.fromString(responseJson())
                        .toObject(com.azure.ai.openai.responses.models.ResponsesResponse.class))
                .toString();
        assertThat(sdkRoundTrip).as(sdkRoundTrip).contains("\"id\":\"resp-1\"");
        RecordingHttpClient http = new RecordingHttpClient(false);
        AzureOpenAIChatClientOptions options = AzureOpenAIChatClientOptions.builder()
                .endpoint("https://resource.openai.azure.com")
                .deployment("deployment")
                .apiVersion("2025-03-01-preview")
                .apiKey("key-secret")
                .build();
        AzureOpenAITransport transport = AzureOpenAISdkTransport.create(options, http);
        AzureOpenAIChatClient client = AzureOpenAIChatClient.builder()
                .options(options)
                .transport(transport, true)
                .build();

        // Act
        var response = client.completeAsync(request(
                        ChatOptions.builder().conversationId("resp_previous").build()))
                .toCompletableFuture()
                .join();

        // Assert
        HttpRequest sent = http.request.get();
        assertThat(sent.getUrl().toString())
                .startsWith("https://resource.openai.azure.com")
                .contains("api-version=2025-03-01-preview");
        assertThat(sent.getHeaders().getValue(HttpHeaderName.fromString("api-key")))
                .isEqualTo("key-secret");
        assertThat(sent.getBodyAsBinaryData().toString())
                .contains("\"model\":\"deployment\"")
                .contains("\"role\":\"user\"")
                .contains("\"previous_response_id\":\"resp_previous\"")
                .doesNotContain("\"conversation\"");
        assertThat(response.text()).isEqualTo("done");
        assertThat(response.usage().totalTokens().orElseThrow().longValueExact())
                .isEqualTo(2);
    }

    @Test
    void sdkTransport_shouldRoundTripTypedParallelFunctionCallsThroughChatAgent() {
        // Arrange
        RecordingHttpClient http = new RecordingHttpClient(false, functionCallsResponseJson(), responseJson());
        AzureOpenAIChatClientOptions options = AzureOpenAIChatClientOptions.builder()
                .endpoint("https://resource.openai.azure.com")
                .deployment("deployment")
                .apiKey("key-secret")
                .build();
        AzureOpenAIChatClient client = AzureOpenAIChatClient.builder()
                .options(options)
                .transport(AzureOpenAISdkTransport.create(options, http), true)
                .build();
        FunctionTool tool = FunctionTool.create(
                toolMetadata(), (context, arguments) -> CompletableFuture.completedFuture(arguments));
        ChatAgent agent = new ChatAgent(client, List.of(tool));

        // Act
        AgentResponse<Void> response =
                agent.runAsync("Compare Paris and Tokyo.").toCompletableFuture().join();

        // Assert
        assertThat(response.text()).isEqualTo("done");
        assertThat(http.requests).hasSize(2);
        String followUpBody = http.requests.get(1).getBodyAsBinaryData().toString();
        assertThat(followUpBody)
                .contains("\"id\":\"item-paris\"")
                .contains("\"call_id\":\"call-paris\"")
                .contains("\"name\":\"lookup\"")
                .contains("\\\"city\\\":\\\"Paris\\\"")
                .contains("\"id\":\"item-tokyo\"")
                .contains("\"call_id\":\"call-tokyo\"")
                .contains("\\\"city\\\":\\\"Tokyo\\\"")
                .contains("\"type\":\"function_call_output\"");
        assertThat(followUpBody.indexOf("\"id\":\"item-paris\""))
                .isLessThan(followUpBody.indexOf("\"id\":\"item-tokyo\""));
        assertThat(followUpBody.indexOf("\"call_id\":\"call-paris\""))
                .isLessThan(followUpBody.indexOf("\"call_id\":\"call-tokyo\""));
    }

    @Test
    void sdkTransport_shouldApplyTokenAuthAndMapStreamingEvents() {
        // Arrange
        List<com.azure.ai.openai.responses.models.ResponsesStreamEvent> sdkEvents =
                new com.azure.ai.openai.responses.implementation.OpenAIServerSentEvents(
                                Flux.just(ByteBuffer.wrap(streamJson().getBytes(StandardCharsets.UTF_8))))
                        .getEvents()
                        .collectList()
                        .block();
        assertThat(sdkEvents).hasSize(3);
        for (com.azure.ai.openai.responses.models.ResponsesStreamEvent event : sdkEvents) {
            com.microsoft.agents.providers.openai.OpenAIResponsesJsonCodec.decodeStreamEvent(
                    AzureOpenAISdkTransport.eventJson(event), null, "deployment");
        }
        TokenCredential credential = context ->
                Mono.just(new AccessToken("token-secret", OffsetDateTime.now().plusHours(1)));
        RecordingHttpClient http = new RecordingHttpClient(true);
        AzureOpenAIChatClientOptions options = AzureOpenAIChatClientOptions.builder()
                .endpoint("https://resource.openai.azure.com")
                .deployment("deployment")
                .tokenCredential(credential)
                .build();
        AzureOpenAIChatClient client = AzureOpenAIChatClient.builder()
                .options(options)
                .transport(AzureOpenAISdkTransport.create(options, http), true)
                .build();

        // Act
        List<ChatResponseUpdate> updates =
                collect(client.completeStreaming(request())).join();

        // Assert
        assertThat(http.request.get().getHeaders().getValue(HttpHeaderName.AUTHORIZATION))
                .startsWith("Bearer ");
        assertThat(updates.stream().map(ChatResponseUpdate::text).reduce("", String::concat))
                .isEqualTo("done");
        assertThat(updates.getLast().finishReason()).isNotNull();
    }

    @Test
    void sdkTransport_shouldSanitizeTokenCredentialFailures() {
        // Arrange
        TokenCredential credential =
                context -> Mono.error(new ClientAuthenticationException("credential-secret", null));
        AzureOpenAIChatClientOptions options = AzureOpenAIChatClientOptions.builder()
                .endpoint("https://resource.openai.azure.com")
                .deployment("deployment")
                .tokenCredential(credential)
                .build();
        AzureOpenAIChatClient client = AzureOpenAIChatClient.builder()
                .options(options)
                .transport(AzureOpenAISdkTransport.create(options, new RecordingHttpClient(false)), true)
                .build();

        // Act / Assert
        assertThatThrownBy(() ->
                        client.completeAsync(request()).toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOfSatisfying(AzureOpenAIProviderException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(AzureOpenAIProviderException.Kind.AUTHENTICATION);
                    assertThat(failure.getMessage()).doesNotContain("credential-secret");
                });
    }

    private static ChatClientRequest request() {
        return request(ChatOptions.empty());
    }

    private static ChatClientRequest request(ChatOptions options) {
        return new ChatClientRequest(List.of(Message.text(Role.USER, "hello")), options);
    }

    private static ToolMetadata toolMetadata() {
        return new ToolMetadata(
                "lookup",
                "Looks up a city.",
                Set.of(ToolCapability.FUNCTION),
                ToolApprovalMode.NEVER_REQUIRE,
                StateValue.object(Map.of("type", StateValue.string("object"))),
                StateValue.object(Map.of()));
    }

    private static CompletableFuture<List<ChatResponseUpdate>> collect(Flow.Publisher<ChatResponseUpdate> publisher) {
        ArrayList<ChatResponseUpdate> updates = new ArrayList<>();
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

    private static String responseJson() {
        return """
                {
                  "id":"resp-1","object":"response","created_at":1,"status":"completed",
                  "error":null,"incomplete_details":null,"instructions":null,
                  "model":"deployment",
                  "output":[{"type":"message","id":"msg-1","status":"completed","role":"assistant",
                             "content":[{"type":"output_text","text":"done","annotations":[]}]}],
                  "parallel_tool_calls":true,"previous_response_id":null,
                  "temperature":1.0,"tool_choice":"auto","tools":[],"top_p":1.0,
                  "metadata":{},"usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2,
                  "input_tokens_details":{"cached_tokens":0},
                  "output_tokens_details":{"reasoning_tokens":0}}
                }
                """;
    }

    private static String functionCallsResponseJson() {
        return """
                {
                  "id":"resp-functions","object":"response","created_at":1,"status":"completed",
                  "error":null,"incomplete_details":null,"instructions":null,
                  "model":"deployment",
                  "output":[
                    {"type":"function_call","id":"item-paris","status":"completed",
                     "call_id":"call-paris","name":"lookup","arguments":"{\\"city\\":\\"Paris\\"}"},
                    {"type":"function_call","id":"item-tokyo","status":"completed",
                     "call_id":"call-tokyo","name":"lookup","arguments":"{\\"city\\":\\"Tokyo\\"}"}
                  ],
                  "parallel_tool_calls":true,"previous_response_id":null,
                  "temperature":1.0,"tool_choice":"auto","tools":[],"top_p":1.0,
                  "metadata":{},"usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2,
                  "input_tokens_details":{"cached_tokens":0},
                  "output_tokens_details":{"reasoning_tokens":0}}
                }
                """;
    }

    private static String streamJson() {
        String created = "{\"type\":\"response.created\",\"sequence_number\":0,\"response\":"
                + responseJson().strip().replace("\"status\":\"completed\"", "\"status\":\"in_progress\"")
                + "}";
        String delta = """
                {"type":"response.output_text.delta","sequence_number":1,
                 "item_id":"msg-1","output_index":0,"content_index":0,"delta":"done"}
                """.strip();
        String completed = "{\"type\":\"response.completed\",\"sequence_number\":2,\"response\":"
                + responseJson().strip()
                + "}";
        return "event: response.created\n"
                + "data: "
                + created
                + "\n\n"
                + "event: response.output_text.delta\n"
                + "data: "
                + delta
                + "\n\n"
                + "event: response.completed\n"
                + "data: "
                + completed
                + "\n\n";
    }

    private static final class RecordingHttpClient implements com.azure.core.http.HttpClient {
        private final boolean streaming;

        private final AtomicReference<HttpRequest> request = new AtomicReference<>();

        private final List<HttpRequest> requests = new CopyOnWriteArrayList<>();

        private final ConcurrentLinkedQueue<String> finiteBodies = new ConcurrentLinkedQueue<>();

        private RecordingHttpClient(boolean streaming) {
            this(streaming, new String[0]);
        }

        private RecordingHttpClient(boolean streaming, String... finiteBodies) {
            this.streaming = streaming;
            this.finiteBodies.addAll(List.of(finiteBodies));
        }

        @Override
        public Mono<HttpResponse> send(HttpRequest request) {
            this.request.set(request);
            requests.add(request);
            String queued = finiteBodies.poll();
            String body = streaming ? streamJson() : queued == null ? responseJson() : queued;
            String contentType = streaming ? "text/event-stream" : "application/json";
            return Mono.just(new StringHttpResponse(request, body, contentType));
        }
    }

    private static final class StringHttpResponse extends HttpResponse {
        private final byte[] body;

        private final HttpHeaders headers;

        private StringHttpResponse(HttpRequest request, String body, String contentType) {
            super(request);
            this.body = body.getBytes(StandardCharsets.UTF_8);
            headers = new HttpHeaders()
                    .set(HttpHeaderName.CONTENT_TYPE, contentType)
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
