// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.microsoft.agents.core.StateValue;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AGUIClientEofTest {
    private static final String START = "data: {\"type\":\"RUN_STARTED\",\"threadId\":\"thread\",\"runId\":\"run\"}";

    private static final String FINISH = "data: {\"type\":\"RUN_FINISHED\",\"threadId\":\"thread\","
            + "\"runId\":\"run\",\"outcome\":{\"type\":\"success\"}}";

    @Test
    void client_shouldConsumeOfficialBlankLineTerminatedFinalFrameExactlyOnce() throws Exception {
        // Act
        List<AGUIEvent> events = run(START + "\n\n" + FINISH + "\n\n");

        // Assert
        assertThat(events)
                .extracting(AGUIEvent::type)
                .containsExactly(AGUIEventType.RUN_STARTED, AGUIEventType.RUN_FINISHED);
    }

    @Test
    void client_shouldFlushCompleteTerminalFrameEndingExactlyAtEof() throws Exception {
        // Act
        List<AGUIEvent> events = run(START + "\n\n" + FINISH);

        // Assert
        assertThat(events)
                .extracting(AGUIEvent::type)
                .containsExactly(AGUIEventType.RUN_STARTED, AGUIEventType.RUN_FINISHED);
    }

    @Test
    void client_shouldRejectTruncatedFinalFrameAtEof() throws Exception {
        // Act
        Throwable failure = runFailure(START + "\n\n" + "data: {\"type\":\"RUN_FINISHED\",\"threadId\":\"thread\"");

        // Assert
        assertProtocolFailure(failure, AGUIErrorCode.MALFORMED_INPUT);
    }

    @Test
    void client_shouldRejectEofWithoutRunFinishedOrRunError() throws Exception {
        // Act
        Throwable failure = runFailure(START + "\n\n");

        // Assert
        assertProtocolFailure(failure, AGUIErrorCode.INVALID_SEQUENCE);
    }

    @Test
    void client_shouldRejectEventAfterTerminal() throws Exception {
        // Arrange
        String late = "data: {\"type\":\"CUSTOM\",\"name\":\"late\",\"value\":null}\n\n";

        // Act
        Throwable failure = runFailure(START + "\n\n" + FINISH + "\n\n" + late);

        // Assert
        assertProtocolFailure(failure, AGUIErrorCode.INVALID_SEQUENCE);
    }

    @Test
    void clientCancellation_shouldSilentlyStopDeliveryAfterCancelReturns() throws Exception {
        // Arrange
        String response = START + "\n\n" + FINISH + "\n\n";

        // Act
        List<AGUIEventType> delivered = withServer(response, client -> {
            CopyOnWriteArrayList<AGUIEventType> types = new CopyOnWriteArrayList<>();
            CountDownLatch cancelled = new CountDownLatch(1);
            client.runStreaming(input()).subscribe(new Flow.Subscriber<>() {
                private Flow.Subscription subscription;

                @Override
                public void onSubscribe(Flow.Subscription value) {
                    subscription = value;
                    value.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(AGUIEvent item) {
                    types.add(item.type());
                    subscription.cancel();
                    cancelled.countDown();
                }

                @Override
                public void onError(Throwable throwable) {
                    types.add(AGUIEventType.RUN_ERROR);
                    cancelled.countDown();
                }

                @Override
                public void onComplete() {
                    types.add(AGUIEventType.RUN_FINISHED);
                    cancelled.countDown();
                }
            });
            assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
            return types;
        });

        // Assert
        assertThat(delivered).containsExactly(AGUIEventType.RUN_STARTED);
    }

    private static List<AGUIEvent> run(String sse) throws Exception {
        return withServer(
                sse, client -> client.runAsync(input()).toCompletableFuture().join());
    }

    private static Throwable runFailure(String sse) throws Exception {
        return withServer(
                sse,
                client -> catchThrowable(
                        () -> client.runAsync(input()).toCompletableFuture().join()));
    }

    private static <T> T withServer(String sse, ClientAction<T> action) throws Exception {
        byte[] response = sse.getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.createContext("/ag-ui", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", AGUIProtocol.SSE_MEDIA_TYPE);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/ag-ui");
        try (AGUIClient client = new AGUIClient(
                AGUIClientOptions.builder(endpoint).allowInsecureLoopback().build())) {
            return action.apply(client);
        } finally {
            server.stop(0);
        }
    }

    private static void assertProtocolFailure(Throwable failure, AGUIErrorCode code) {
        assertThat(failure).isInstanceOf(CompletionException.class);
        Throwable cause = failure.getCause();
        assertThat(cause)
                .isInstanceOfSatisfying(
                        AGUIProtocolException.class,
                        protocol -> assertThat(protocol.code()).isEqualTo(code));
    }

    private static RunAgentInput input() {
        return new RunAgentInput(
                "thread",
                "run",
                StateValue.object(Map.of()),
                List.of(),
                List.of(),
                List.of(),
                StateValue.object(Map.of()));
    }

    @FunctionalInterface
    private interface ClientAction<T> {
        T apply(AGUIClient client) throws Exception;
    }
}
