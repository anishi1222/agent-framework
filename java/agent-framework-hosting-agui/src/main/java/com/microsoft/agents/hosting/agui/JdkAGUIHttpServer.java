// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.hosting.http.HostingHttpRequest;
import com.microsoft.agents.hosting.http.HostingHttpServerOptions;
import com.microsoft.agents.protocols.agui.AGUIEvent;
import com.microsoft.agents.protocols.agui.AGUIProtocolException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class JdkAGUIHttpServer implements AGUIHttpServer {
    private final HttpServer server;

    private final AGUIHostingHttpHandler handler;

    private final ExecutorService executor;

    private final URI endpoint;

    private final AtomicReference<CompletableFuture<Void>> closeFuture = new AtomicReference<>();

    private JdkAGUIHttpServer(
            HttpServer server, AGUIHostingHttpHandler handler, ExecutorService executor, URI endpoint) {
        this.server = server;
        this.handler = handler;
        this.executor = executor;
        this.endpoint = endpoint;
    }

    static AGUIHttpServer start(AGUIHostingHttpHandler handler) {
        java.util.Objects.requireNonNull(handler, "handler");
        HostingHttpServerOptions options = handler.transportOptions();
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(options.bindAddress(), options.port()),
                    options.limits().maxConcurrentRequests());
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            JdkAGUIHttpServer result = new JdkAGUIHttpServer(
                    server,
                    handler,
                    executor,
                    advertised(options, server.getAddress().getPort()));
            server.createContext("/", result::handle);
            server.start();
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to start AG-UI HTTP server.", exception);
        }
    }

    @Override
    public URI endpoint() {
        return endpoint;
    }

    @Override
    public boolean isRunning() {
        return closeFuture.get() == null;
    }

    @Override
    public CompletionStage<Void> closeAsync() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (!closeFuture.compareAndSet(null, result)) {
            return java.util.Objects.requireNonNull(closeFuture.get()).minimalCompletionStage();
        }
        Thread.startVirtualThread(() -> {
            try {
                Duration timeout = handler.transportOptions().gracefulShutdownTimeout();
                server.stop(Math.toIntExact(Math.max(0, timeout.toSeconds())));
                handler.close();
                executor.shutdown();
                if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow();
                }
                result.complete(null);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
                result.completeExceptionally(exception);
            } catch (RuntimeException failure) {
                executor.shutdownNow();
                result.completeExceptionally(failure);
            }
        });
        return result.minimalCompletionStage();
    }

    @Override
    public void close() {
        try {
            closeAsync().toCompletableFuture().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AG-UI server close was interrupted.", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("AG-UI server close failed.", exception.getCause());
        }
    }

    private void handle(HttpExchange exchange) {
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        AGUIHttpResponse response = null;
        try {
            HostingHttpRequest request = request(exchange, cancellation);
            response = handler.handleAsync(request).toCompletableFuture().get();
            writeHeaders(exchange.getResponseHeaders(), response.headers());
            if (!response.isStreaming()) {
                byte[] body = response.body();
                exchange.sendResponseHeaders(response.status(), body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
                return;
            }
            exchange.sendResponseHeaders(response.status(), 0);
            stream(exchange, response.streamingRun());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancellation.cancel();
            if (response != null && response.streamingRun() != null) {
                response.streamingRun().discardUndelivered();
            }
        } catch (ExecutionException exception) {
            cancellation.cancel();
            writeFallback(exchange);
        } catch (IOException | RuntimeException failure) {
            cancellation.cancel();
            if (response != null && response.streamingRun() != null) {
                response.streamingRun().discardUndelivered();
            }
            writeFallback(exchange);
        } finally {
            exchange.close();
        }
    }

    private void stream(HttpExchange exchange, AGUIHostedRun run) throws IOException, InterruptedException {
        QueueSubscriber subscriber = new QueueSubscriber();
        run.events().subscribe(subscriber);
        try (OutputStream output = exchange.getResponseBody()) {
            subscriber.requestNext();
            while (true) {
                QueueItem item =
                        subscriber.poll(handler.transportOptions().limits().idleTimeout());
                switch (item) {
                    case EventItem event -> {
                        output.write(handler.codec().encodeSseFrame(event.event()));
                        output.flush();
                        subscriber.requestNext();
                    }
                    case CompleteItem _ -> {
                        output.flush();
                        run.confirmDelivery();
                        return;
                    }
                    case ErrorItem error -> {
                        throw new IOException("AG-UI event publisher failed.", error.failure());
                    }
                }
            }
        } catch (IOException | InterruptedException failure) {
            subscriber.cancel();
            run.discardUndelivered();
            throw failure;
        }
    }

    private HostingHttpRequest request(HttpExchange exchange, DefaultRunCancellation cancellation) throws IOException {
        HostingHttpServerOptions options = handler.transportOptions();
        Map<String, List<String>> headers = copyHeaders(exchange.getRequestHeaders(), options.maxHttpHeaderBytes());
        byte[] body = readBody(exchange.getRequestBody(), options.limits().maxRequestBytes());
        return new HostingHttpRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI(),
                exchange.getRemoteAddress(),
                headers,
                body,
                cancellation);
    }

    private static Map<String, List<String>> copyHeaders(Headers source, int maximumBytes) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        long bytes = 0;
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            bytes += entry.getKey().length();
            ArrayList<String> values = new ArrayList<>(entry.getValue());
            for (String value : values) {
                bytes += value.length();
            }
            if (bytes > maximumBytes) {
                throw new IllegalArgumentException("HTTP headers exceed maxHttpHeaderBytes.");
            }
            result.put(entry.getKey(), values);
        }
        return result;
    }

    private static byte[] readBody(InputStream input, long maximumBytes) throws IOException {
        try (InputStream body = input;
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = body.read(buffer)) >= 0) {
                if ((long) output.size() + read > maximumBytes) {
                    throw new IllegalArgumentException("HTTP body exceeds maxRequestBytes.");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void writeHeaders(Headers target, Map<String, List<String>> source) {
        source.forEach((name, values) -> values.forEach(value -> target.add(name, value)));
    }

    private static void writeFallback(HttpExchange exchange) {
        try {
            byte[] body = "{\"error\":{\"code\":\"internal_error\",\"message\":\"Request failed.\"}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        } catch (IOException ignored) {
            // The peer can already be disconnected.
        }
    }

    private static URI advertised(HostingHttpServerOptions options, int port) {
        if (options.advertisedEndpoint() != null) {
            return options.advertisedEndpoint();
        }
        String host = options.bindAddress().getHostAddress();
        if (host.contains(":")) {
            host = "[" + host + "]";
        }
        return URI.create("http://" + host + ":" + port + "/");
    }

    private sealed interface QueueItem permits EventItem, CompleteItem, ErrorItem {}

    private record EventItem(AGUIEvent event) implements QueueItem {}

    private record CompleteItem() implements QueueItem {}

    private record ErrorItem(Throwable failure) implements QueueItem {}

    private static final class QueueSubscriber implements Flow.Subscriber<AGUIEvent> {
        private final ArrayBlockingQueue<QueueItem> queue = new ArrayBlockingQueue<>(2);

        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

        private final AtomicBoolean terminal = new AtomicBoolean();

        @Override
        public void onSubscribe(Flow.Subscription value) {
            if (!subscription.compareAndSet(null, value)) {
                value.cancel();
            }
        }

        @Override
        public void onNext(AGUIEvent item) {
            offer(new EventItem(item));
        }

        @Override
        public void onError(Throwable throwable) {
            if (terminal.compareAndSet(false, true)) {
                offer(new ErrorItem(throwable));
            }
        }

        @Override
        public void onComplete() {
            if (terminal.compareAndSet(false, true)) {
                offer(new CompleteItem());
            }
        }

        private void requestNext() {
            Flow.Subscription value = subscription.get();
            if (value == null) {
                throw new IllegalStateException("AG-UI publisher did not provide a subscription.");
            }
            value.request(1);
        }

        private QueueItem poll(Duration timeout) throws InterruptedException {
            QueueItem item = queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (item == null) {
                cancel();
                throw new AGUIProtocolException(
                        com.microsoft.agents.protocols.agui.AGUIErrorCode.TIMEOUT,
                        "AG-UI server stream exceeded idleTimeout.");
            }
            return item;
        }

        private void cancel() {
            Flow.Subscription value = subscription.get();
            if (value != null) {
                value.cancel();
            }
        }

        private void offer(QueueItem item) {
            if (!queue.offer(item)) {
                cancel();
                throw new IllegalStateException("AG-UI server transport queue overflowed.");
            }
        }
    }
}
