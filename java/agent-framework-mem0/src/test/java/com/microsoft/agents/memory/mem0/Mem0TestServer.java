// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.memory.mem0;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Mem0TestServer implements AutoCloseable {
    private final HttpServer server;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final ConcurrentLinkedQueue<Response> responses = new ConcurrentLinkedQueue<>();

    private final CopyOnWriteArrayList<RecordedRequest> requests = new CopyOnWriteArrayList<>();

    Mem0TestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    Mem0Endpoint endpoint() {
        return Mem0Endpoint.of("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    void enqueueJson(int status, String body) {
        enqueue(new Response(status, "application/json", body, Map.of(), Duration.ZERO));
    }

    void enqueueJson(int status, String body, Map<String, String> headers) {
        enqueue(new Response(status, "application/json", body, headers, Duration.ZERO));
    }

    void enqueue(Response response) {
        responses.add(response);
    }

    void awaitRequestCount(int count) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (requests.size() < count && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while awaiting test requests.", exception);
            }
        }
        if (requests.size() < count) {
            throw new AssertionError("Expected " + count + " requests but observed " + requests.size() + ".");
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders()
                .forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
        requests.add(new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath(),
                exchange.getRequestURI().getRawQuery(),
                Map.copyOf(headers),
                new String(requestBody, StandardCharsets.UTF_8)));

        Response response = responses.poll();
        if (response == null) {
            response = new Response(
                    500, "application/json", "{\"error\":\"missing test response\"}", Map.of(), Duration.ZERO);
        }
        if (!response.delay().isZero()) {
            try {
                Thread.sleep(response.delay());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        if (response.contentType() != null) {
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
        }
        response.headers()
                .forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        try {
            exchange.sendResponseHeaders(response.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException ignored) {
            // Client-side cancellation can close the loopback exchange before the delayed response.
        } finally {
            exchange.close();
        }
    }

    record Response(int status, String contentType, String body, Map<String, String> headers, Duration delay) {
        Response {
            body = body == null ? "" : body;
            headers = Map.copyOf(headers);
        }
    }

    record RecordedRequest(String method, String path, String query, Map<String, List<String>> headers, String body) {
        String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.getFirst();
        }

        List<String> headerValues(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null ? List.of() : new ArrayList<>(values);
        }
    }
}
