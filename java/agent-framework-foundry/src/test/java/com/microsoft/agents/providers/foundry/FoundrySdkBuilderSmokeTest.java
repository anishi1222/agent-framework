// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.core.credential.AccessToken;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.BinaryData;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class FoundrySdkBuilderSmokeTest {
    @Test
    void projectBuilder_shouldExecuteFiniteAndStreamingAgainstLocalServer() throws Exception {
        smoke(false);
    }

    @Test
    void agentsBuilder_shouldExecuteFiniteAndStreamingAgainstLocalServer() throws Exception {
        smoke(true);
    }

    private static void smoke(boolean agentSurface) throws Exception {
        // Arrange
        try (LocalFoundryServer server = new LocalFoundryServer()) {
            FoundryChatClientOptions.Builder optionsBuilder = FoundryChatClientOptions.builder()
                    .projectEndpoint(server.projectEndpoint())
                    .tokenCredential(context -> Mono.just(
                            new AccessToken("local-token", OffsetDateTime.now().plusHours(1))));
            if (agentSurface) {
                optionsBuilder.agentName("weather-agent").agentVersion("7");
            } else {
                optionsBuilder.model("deployment");
            }
            FoundryChatClientOptions options = optionsBuilder.build();
            FoundryChatClient client = FoundryChatClient.builder()
                    .options(options)
                    .transport(FoundrySdkTransport.create(options, new LoopbackHttpClient()), true)
                    .build();

            // Act
            var finite = client.completeAsync(request()).toCompletableFuture().join();
            List<ChatResponseUpdate> updates =
                    collect(client.completeStreaming(request())).join();
            client.close();

            // Assert
            assertThat(finite.text()).isEqualTo("finite");
            assertThat(finite.metadata()).containsEntry("foundry.requestId", StateValue.string("request-finite"));
            assertThat(updates.stream().map(ChatResponseUpdate::text).reduce("", String::concat))
                    .isEqualTo("stream");
            assertThat(updates.stream()
                            .flatMap(update -> update.contents().stream())
                            .filter(FunctionCallContent.class::isInstance)
                            .map(FunctionCallContent.class::cast))
                    .singleElement()
                    .satisfies(call -> {
                        assertThat(call.callId()).isEqualTo("call-weather");
                        assertThat(call.name()).isEqualTo("lookup");
                        assertThat(call.arguments())
                                .isEqualTo(StateValue.object(Map.of("city", StateValue.string("Paris"))));
                    });
            assertThat(updates).allSatisfy(update -> assertThat(update.metadata())
                    .containsEntry("foundry.requestId", StateValue.string("request-stream")));
            assertThat(server.paths)
                    .containsExactly(
                            "/api/projects/project-one/openai/v1/responses",
                            "/api/projects/project-one/openai/v1/responses");
            assertThat(server.authorizationHeaders).containsExactly("Bearer local-token", "Bearer local-token");
            if (agentSurface) {
                assertThat(server.requestBodies).allSatisfy(body -> assertThat(body)
                        .contains("\"agent_reference\"")
                        .contains("\"name\":\"weather-agent\"")
                        .contains("\"version\":\"7\"")
                        .doesNotContain("\"model\""));
            } else {
                assertThat(server.requestBodies)
                        .allSatisfy(body -> assertThat(body).contains("\"model\":\"deployment\""));
            }
            assertThat(server.requestBodies.get(1)).contains("\"stream\":true");
            assertThat(server.streamOpened.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(server.streamClosed.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static ChatClientRequest request() {
        return new ChatClientRequest(
                List.of(Message.text(Role.USER, "hello")),
                ChatOptions.builder()
                        .maxTokens(64)
                        .user("local-user")
                        .store(false)
                        .build());
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

    private static final class LocalFoundryServer implements AutoCloseable {
        private final HttpServer server;

        private final List<String> paths = new CopyOnWriteArrayList<>();

        private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();

        private final List<String> requestBodies = new CopyOnWriteArrayList<>();

        private final CountDownLatch streamOpened = new CountDownLatch(1);

        private final CountDownLatch streamClosed = new CountDownLatch(1);

        private LocalFoundryServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private String projectEndpoint() {
            return "https://127.0.0.1:" + server.getAddress().getPort() + "/api/projects/project-one";
        }

        private void handle(HttpExchange exchange) throws IOException {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            paths.add(exchange.getRequestURI().getPath());
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBodies.add(requestBody);
            boolean streaming = requestBody.contains("\"stream\":true");
            byte[] body = (streaming ? streamingResponse() : finiteResponse()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.getResponseHeaders().set("x-request-id", streaming ? "request-stream" : "request-finite");
            exchange.sendResponseHeaders(200, body.length);
            if (streaming) {
                streamOpened.countDown();
            }
            try (exchange;
                    var responseBody = exchange.getResponseBody()) {
                responseBody.write(body);
            } finally {
                if (streaming) {
                    streamClosed.countDown();
                }
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class LoopbackHttpClient implements com.azure.core.http.HttpClient {
        private final java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();

        @Override
        public Mono<HttpResponse> send(HttpRequest request) {
            URI original = URI.create(request.getUrl().toString());
            URI loopback = URI.create("http://"
                    + original.getAuthority()
                    + original.getRawPath()
                    + (original.getRawQuery() == null ? "" : "?" + original.getRawQuery()));
            java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(loopback)
                    .method(
                            request.getHttpMethod().toString(),
                            BodyPublishers.ofByteArray(
                                    request.getBodyAsBinaryData().toBytes()));
            copyHeader(request, builder, HttpHeaderName.AUTHORIZATION);
            copyHeader(request, builder, HttpHeaderName.CONTENT_TYPE);
            return Mono.fromFuture(client.sendAsync(builder.build(), BodyHandlers.ofByteArray())
                    .thenApply(response -> new ByteArrayHttpResponse(request, response)));
        }

        private static void copyHeader(
                HttpRequest source, java.net.http.HttpRequest.Builder target, HttpHeaderName name) {
            String value = source.getHeaders().getValue(name);
            if (value != null) {
                target.header(name.toString(), value);
            }
        }
    }

    private static final class ByteArrayHttpResponse extends HttpResponse {
        private final int statusCode;

        private final byte[] body;

        private final HttpHeaders headers = new HttpHeaders();

        private ByteArrayHttpResponse(HttpRequest request, java.net.http.HttpResponse<byte[]> response) {
            super(request);
            statusCode = response.statusCode();
            body = response.body().clone();
            response.headers().map().forEach((name, values) -> headers.set(HttpHeaderName.fromString(name), values));
        }

        @Override
        public int getStatusCode() {
            return statusCode;
        }

        @SuppressWarnings("deprecation")
        @Override
        public String getHeaderValue(String name) {
            return headers.getValue(name);
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public Flux<ByteBuffer> getBody() {
            return Flux.just(ByteBuffer.wrap(body.clone()));
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

    private static String finiteResponse() {
        return """
                {
                  "id":"resp-finite","object":"response","created_at":1,"status":"completed",
                  "error":null,"incomplete_details":null,"instructions":null,
                  "model":"deployment",
                  "output":[{"type":"message","id":"msg-finite","status":"completed","role":"assistant",
                             "content":[{"type":"output_text","text":"finite","annotations":[]}]}],
                  "parallel_tool_calls":true,"previous_response_id":null,
                  "temperature":1.0,"tool_choice":"auto","tools":[],"top_p":1.0,
                  "metadata":{},"usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2,
                  "input_tokens_details":{"cached_tokens":0},
                  "output_tokens_details":{"reasoning_tokens":0}}
                }
                """;
    }

    private static String streamingResponse() {
        String created = """
                {"type":"response.created","sequence_number":0,
                 "response":{"id":"resp-stream","created_at":1,"metadata":{},
                 "model":"deployment","output":[],"status":"in_progress"}}
                """.strip();
        String text = """
                {"type":"response.output_text.delta","sequence_number":1,
                 "item_id":"msg-stream","output_index":0,"content_index":0,
                 "delta":"stream","logprobs":[]}
                """.strip();
        String completed = """
                {"type":"response.completed","sequence_number":2,
                 "response":{"id":"resp-stream","created_at":1,"metadata":{},"model":"deployment",
                 "status":"completed","output":[
                 {"id":"msg-stream","type":"message","role":"assistant","status":"completed",
                  "content":[{"type":"output_text","text":"stream","annotations":[]}]},
                 {"type":"function_call","id":"item-weather","status":"completed",
                  "call_id":"call-weather","name":"lookup","arguments":"{\\"city\\":\\"Paris\\"}"}]}}
                """.strip();
        return event("response.created", created)
                + event("response.output_text.delta", text)
                + event("response.completed", completed);
    }

    private static String event(String type, String data) {
        return "event: " + type + "\ndata: " + data.replace("\n", "") + "\n\n";
    }
}
