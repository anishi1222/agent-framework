// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.microsoft.agents.core.StateValue;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class JdkA2AClientErrorTest {
    @Test
    void knownRemoteError_shouldRetainTypedAndRawCodeMessageAndData() throws Exception {
        // Arrange
        StateValue data = StateValue.object(Map.of("field", StateValue.string("message.parts")));
        RemoteError remote = new RemoteError(A2AErrorCode.INVALID_PARAMS.code(), "Message parts are invalid.", data);

        // Act
        A2AProtocolException failure = invoke(remote);

        // Assert
        assertThat(failure.errorCode()).contains(A2AErrorCode.INVALID_PARAMS);
        assertThat(failure.rawErrorCode()).isEqualTo(A2AErrorCode.INVALID_PARAMS.code());
        assertThat(failure).hasMessage("Message parts are invalid.");
        assertThat(failure.data()).isEqualTo(data);
    }

    @Test
    void futureAndApplicationRemoteErrors_shouldRetainRawCodesWithoutInvalidResponseMapping() throws Exception {
        // Arrange / Act
        A2AProtocolException future =
                invoke(new RemoteError(-32042, "A future A2A error.", StateValue.string("future-detail")));
        A2AProtocolException application =
                invoke(new RemoteError(7001, "Application quota reached.", StateValue.integer(17)));

        // Assert
        assertThat(future.errorCode()).isEmpty();
        assertThat(future.rawErrorCode()).isEqualTo(-32042);
        assertThat(future).hasMessage("A future A2A error.");
        assertThat(future.data()).isEqualTo(StateValue.string("future-detail"));
        assertThat(application.errorCode()).isEmpty();
        assertThat(application.rawErrorCode()).isEqualTo(7001);
        assertThat(application).hasMessage("Application quota reached.");
        assertThat(application.data()).isEqualTo(StateValue.integer(17));
        assertThat(List.of(future.rawErrorCode(), application.rawErrorCode()))
                .doesNotContain(A2AErrorCode.INVALID_AGENT_RESPONSE.code());
    }

    @Test
    void remoteErrorData_shouldRequireExplicitAccessAndStayOutOfDiagnostics() throws Exception {
        // Arrange
        StateValue data = StateValue.object(Map.of(
                "retryable", StateValue.bool(true), "credential", StateValue.string("do-not-render-this-secret")));

        // Act
        A2AProtocolException failure = invoke(new RemoteError(7002, "Remote operation failed.", data));

        // Assert
        assertThat(failure.data()).isEqualTo(data);
        assertThat(failure.getMessage()).isEqualTo("Remote operation failed.");
        assertThat(failure.toString()).doesNotContain("do-not-render-this-secret");
    }

    private static A2AProtocolException invoke(RemoteError remote) throws Exception {
        try (ErrorHost host = new ErrorHost(remote);
                A2AClient client = A2AClient.create(A2AClientOptions.builder(host.endpoint())
                        .allowInsecureLoopbackHttp(true)
                        .build())) {
            Throwable failure = catchThrowable(() ->
                    client.sendMessageAsync(request()).toCompletableFuture().join());
            assertThat(failure).isNotNull();
            Throwable current = failure;
            while (current.getCause() != null) {
                current = current.getCause();
            }
            assertThat(current).isInstanceOf(A2AProtocolException.class);
            return (A2AProtocolException) current;
        }
    }

    private static SendMessageRequest request() {
        return new SendMessageRequest(Message.builder(Role.ROLE_USER)
                .messageId("message-1")
                .parts(List.of(new TextPart("hello")))
                .build());
    }

    private record RemoteError(int code, String message, StateValue data) {}

    private static final class ErrorHost implements AutoCloseable {
        private final A2AJsonCodec codec = new A2AJsonCodec(A2ALimits.defaults());

        private final HttpServer server;

        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        private final RemoteError remote;

        private ErrorHost(RemoteError remote) throws IOException {
            this.remote = remote;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
            server.setExecutor(executor);
            server.createContext("/a2a", exchange -> {
                try {
                    StateValue.ObjectValue request = (StateValue.ObjectValue)
                            codec.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    LinkedHashMap<String, StateValue> error = new LinkedHashMap<>();
                    error.put("code", StateValue.integer(remote.code()));
                    error.put("message", StateValue.string(remote.message()));
                    error.put("data", remote.data());
                    StateValue response = StateValue.object(Map.of(
                            "jsonrpc",
                            StateValue.string(A2AProtocol.JSON_RPC_VERSION),
                            "id",
                            request.values().get("id"),
                            "error",
                            StateValue.object(error)));
                    byte[] bytes = codec.write(response);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        private URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/a2a");
        }

        @Override
        public void close() {
            server.stop(0);
            executor.close();
        }
    }
}
