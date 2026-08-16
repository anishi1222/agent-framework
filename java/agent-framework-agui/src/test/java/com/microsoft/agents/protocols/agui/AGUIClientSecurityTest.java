// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.StateValue;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AGUIClientSecurityTest {
    @Test
    void options_shouldRequireHttpsOrExplicitLoopbackAndRejectReservedHeaders() {
        // Act and assert
        assertThatThrownBy(() -> AGUIClientOptions.builder(URI.create("http://example.com/ag-ui"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AGUIClientOptions.builder(URI.create("https://user@example.com/ag-ui"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AGUIClientOptions.builder(URI.create("https://example.com/ag-ui?token=value"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AGUIClientOptions.builder(URI.create("https://example.com/ag-ui"))
                        .header("Host", "other.example")
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AGUIClientOptions.builder(URI.create("http://127.0.0.1:8080/ag-ui"))
                        .allowInsecureLoopback()
                        .build()
                        .allowedHosts())
                .contains("127.0.0.1");
    }

    @Test
    void client_shouldNeverFollowRedirectOrLeakHeadersInErrors() throws Exception {
        // Arrange
        AtomicBoolean redirectedTarget = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.createContext("/ag-ui", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            redirectedTarget.set(true);
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/ag-ui");

        try (AGUIClient client = new AGUIClient(AGUIClientOptions.builder(endpoint)
                .allowInsecureLoopback()
                .header("X-Test-Token", "sensitive-marker")
                .build())) {
            // Act and assert
            assertThatThrownBy(
                            () -> client.runAsync(input()).toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(AGUIProtocolException.class)
                    .hasMessageNotContaining("sensitive-marker");
            assertThat(redirectedTarget).isFalse();
        } finally {
            server.stop(0);
        }
    }

    private static RunAgentInput input() {
        return new RunAgentInput(
                "thread",
                "run",
                StateValue.object(Map.of()),
                List.of(new AGUIMessages.User("user", new AGUIMessages.TextUserContent("hello"), null, null)),
                List.of(),
                List.of(),
                StateValue.object(Map.of()));
    }
}
