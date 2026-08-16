// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.devui;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingError;
import com.microsoft.agents.hosting.HostingErrorCode;
import com.microsoft.agents.hosting.HostingEvent;
import com.microsoft.agents.hosting.HostingEventType;
import com.microsoft.agents.hosting.HostingException;
import com.microsoft.agents.hosting.HostingJsonCodec;
import com.microsoft.agents.hosting.HostingOutcome;
import com.microsoft.agents.hosting.HostingRun;
import com.microsoft.agents.hosting.http.HostingHttpHandler;
import com.microsoft.agents.hosting.http.HostingHttpRequest;
import com.microsoft.agents.hosting.http.HostingHttpResponse;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class EmbeddedDevUIServer implements DevUIServer {
    private static final int MAX_REQUEST_PATH_CHARACTERS = 1024;

    private static final String UI_CONTENT_SECURITY_POLICY = "default-src 'none'; "
            + "script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; "
            + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'; object-src 'none'";

    private static final String ERROR_CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'";

    private final HttpServer server;

    private final HostingHttpHandler handler;

    private final DevUIServerOptions options;

    private final DevUIAssets assets;

    private final ExecutorService executor;

    private final ScheduledExecutorService deadlineScheduler;

    private final Semaphore requestPermits;

    private final URI endpoint;

    private final URI apiEndpoint;

    private final byte[] configuration;

    private final Set<RequestState> activeRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final AtomicBoolean running = new AtomicBoolean(true);

    private final AtomicReference<CompletableFuture<Void>> closeFuture = new AtomicReference<>();

    private EmbeddedDevUIServer(
            HttpServer server,
            HostingHttpHandler handler,
            DevUIServerOptions options,
            DevUIAssets assets,
            ExecutorService executor,
            ScheduledExecutorService deadlineScheduler,
            URI endpoint,
            URI apiEndpoint) {
        this.server = server;
        this.handler = handler;
        this.options = options;
        this.assets = assets;
        this.executor = executor;
        this.deadlineScheduler = deadlineScheduler;
        requestPermits = new Semaphore(options.limits().maxConcurrentRequests());
        this.endpoint = endpoint;
        this.apiEndpoint = apiEndpoint;
        configuration = configuration();
    }

    static DevUIServer start(HostingDispatcher dispatcher, DevUIServerOptions options) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(options, "options");
        HostingHttpHandler handler = new HostingHttpHandler(dispatcher, options.transportOptions());
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        ScheduledExecutorService deadlineScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-framework-devui-request-deadline");
            thread.setDaemon(true);
            return thread;
        });
        HttpServer server = null;
        try {
            server = HttpServer.create(
                    new InetSocketAddress(options.bindAddress(), options.port()),
                    options.limits().maxConcurrentRequests());
            server.setExecutor(executor);
            URI origin = advertisedOrigin(options, server.getAddress().getPort());
            EmbeddedDevUIServer result = new EmbeddedDevUIServer(
                    server,
                    handler,
                    options,
                    DevUIAssets.load(),
                    executor,
                    deadlineScheduler,
                    append(origin, UI_PATH),
                    append(origin, API_PATH));
            server.createContext("/", result::handle);
            server.start();
            return result;
        } catch (IOException | RuntimeException | URISyntaxException failure) {
            if (server != null) {
                server.stop(0);
            }
            handler.close();
            deadlineScheduler.shutdownNow();
            executor.shutdownNow();
            throw new HostingException(
                    HostingErrorCode.INTERNAL_ERROR, "Unable to start embedded developer UI.", failure);
        }
    }

    @Override
    public URI endpoint() {
        return endpoint;
    }

    @Override
    public URI apiEndpoint() {
        return apiEndpoint;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public CompletionStage<Void> closeAsync() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (!closeFuture.compareAndSet(null, result)) {
            return Objects.requireNonNull(closeFuture.get()).minimalCompletionStage();
        }
        running.set(false);
        Thread.startVirtualThread(() -> closeResources(result));
        return result.minimalCompletionStage();
    }

    @Override
    public void close() {
        try {
            closeAsync().toCompletableFuture().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new HostingException(
                    HostingErrorCode.CLIENT_CANCELLED, "Developer UI close was interrupted.", exception);
        } catch (ExecutionException exception) {
            throw new HostingException(
                    HostingErrorCode.INTERNAL_ERROR, "Developer UI close failed.", exception.getCause());
        }
    }

    private void handle(HttpExchange exchange) {
        if (!requestPermits.tryAcquire()) {
            safeWriteFailure(
                    exchange,
                    new HostingException(
                            HostingErrorCode.TOO_MANY_REQUESTS, "Developer UI request capacity is exhausted."));
            exchange.close();
            return;
        }
        RequestState state = new RequestState(exchange);
        if (!running.get()) {
            state.abort();
            requestPermits.release();
            return;
        }
        activeRequests.add(state);
        try {
            validateRequestUri(exchange.getRequestURI());
            HostingHttpRequest request = request(exchange, state);
            String path = request.uri().getRawPath();
            if (isApiPath(path)) {
                HostingHttpResponse response = await(handler.handleAsync(request));
                state.attach(response);
                writeHostingResponse(exchange, state, response);
            } else {
                await(handler.authenticateHttpAsync(request));
                writeDeveloperUIResponse(exchange, request);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            state.cancelRun();
            if (!state.isAborted()) {
                safeWriteFailure(
                        exchange,
                        new HostingException(
                                HostingErrorCode.CLIENT_CANCELLED, "Developer UI request was interrupted.", failure));
            }
        } catch (Throwable failure) {
            state.cancelRun();
            if (!state.isAborted()) {
                safeWriteFailure(exchange, failure);
            }
        } finally {
            activeRequests.remove(state);
            state.closeExchange();
            requestPermits.release();
        }
    }

    private void writeDeveloperUIResponse(HttpExchange exchange, HostingHttpRequest request) throws IOException {
        if (!"GET".equals(request.method())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            writeError(
                    exchange,
                    HostingError.of(
                            HostingErrorCode.METHOD_NOT_ALLOWED, "Developer UI resources require the GET method."));
            return;
        }
        if (request.body().length != 0) {
            writeError(
                    exchange,
                    HostingError.of(
                            HostingErrorCode.MALFORMED_REQUEST,
                            "Developer UI resource requests must not include a body."));
            return;
        }
        String path = request.uri().getRawPath();
        if (CONFIG_PATH.equals(path)) {
            writeStatic(exchange, "application/json; charset=utf-8", configuration);
            return;
        }
        DevUIAssets.Asset asset = assets.find(path);
        if (asset == null) {
            writeError(exchange, HostingError.of(HostingErrorCode.NOT_FOUND, "Developer UI resource was not found."));
            return;
        }
        writeStatic(exchange, asset.contentType(), asset.body());
    }

    private void writeHostingResponse(HttpExchange exchange, RequestState state, HostingHttpResponse response)
            throws IOException, InterruptedException {
        writeHeaders(exchange.getResponseHeaders(), response.headers());
        if (!response.isStreaming()) {
            byte[] body = response.body();
            try {
                exchange.sendResponseHeaders(response.status(), body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
                response.confirmDelivery();
            } catch (IOException | RuntimeException failure) {
                response.discardUndeliveredOutcome();
                throw failure;
            }
            return;
        }
        exchange.sendResponseHeaders(response.status(), 0);
        writeSse(exchange, state, response);
    }

    private void writeSse(HttpExchange exchange, RequestState state, HostingHttpResponse response)
            throws IOException, InterruptedException {
        HostingRun run = Objects.requireNonNull(response.streamingRun(), "streamingRun");
        SseBridge bridge = new SseBridge(options.limits().maxSseBufferedEvents());
        state.attach(bridge);
        boolean delivered = false;
        try (OutputStream output = exchange.getResponseBody()) {
            bridge.start(run);
            writeFrame(output, 0, "run-started", startBytes(run));
            bridge.awaitSubscription(options.limits().idleTimeout());
            bridge.requestNext();
            long sequence = 1;
            boolean sourceComplete = false;
            HostingOutcome outcome = null;
            while (true) {
                SseItem item = bridge.poll(options.limits().idleTimeout());
                if (item instanceof EventItem eventItem) {
                    HostingEvent event = eventItem.event();
                    writeFrame(
                            output,
                            sequence++,
                            event.type().value(),
                            handler.codec().encodeEvent(event));
                    bridge.requestNext();
                } else if (item instanceof SourceCompleteItem) {
                    sourceComplete = true;
                } else if (item instanceof OutcomeItem outcomeItem) {
                    outcome = outcomeItem.outcome();
                } else if (item instanceof ClosedItem) {
                    throw new IOException("Developer UI SSE transport closed.");
                }
                if (sourceComplete && outcome != null) {
                    writeFrame(output, sequence, "terminal", terminalBytes(outcome, response));
                    response.confirmDelivery();
                    delivered = true;
                    return;
                }
            }
        } finally {
            bridge.close();
            state.detach(bridge);
            if (!delivered) {
                run.cancel();
                response.discardUndeliveredOutcome();
            }
        }
    }

    private byte[] startBytes(HostingRun run) {
        StateValue value = StateValue.object(Map.of(
                "version",
                StateValue.string(HostingJsonCodec.WIRE_VERSION),
                "type",
                StateValue.string("event"),
                "event",
                StateValue.string(HostingEventType.RUN_STARTED.value()),
                "runId",
                StateValue.string(run.runId()),
                "createdAt",
                StateValue.string(Instant.now().toString()),
                "data",
                StateValue.object(Map.of())));
        return handler.codec().encodeValue(value);
    }

    private byte[] terminalBytes(HostingOutcome outcome, HostingHttpResponse response) {
        try {
            return handler.encodeOutcome(outcome, response);
        } catch (RuntimeException failure) {
            response.confirmDelivery();
            return handler.encodeOutcome(HostingOutcome.overflow(
                    outcome.runId(),
                    HostingError.of(HostingErrorCode.OVERFLOW, "SSE terminal outcome exceeded transport limits.")));
        }
    }

    private static void writeFrame(OutputStream output, long id, String event, byte[] data) throws IOException {
        output.write(("id: " + id + "\n").getBytes(StandardCharsets.UTF_8));
        output.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
        output.write("data: ".getBytes(StandardCharsets.UTF_8));
        output.write(data);
        output.write("\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void writeStatic(HttpExchange exchange, String contentType, byte[] body) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        applyUIHeaders(headers);
        headers.set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private void writeError(HttpExchange exchange, HostingError error) throws IOException {
        byte[] body = handler.encodeError(error);
        Headers headers = exchange.getResponseHeaders();
        applyErrorHeaders(headers);
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(error.code().httpStatus(), body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private void safeWriteFailure(HttpExchange exchange, Throwable failure) {
        Throwable cause = unwrap(failure);
        HostingError error = cause instanceof HostingException hosting
                ? hosting.error()
                : HostingError.of(HostingErrorCode.INTERNAL_ERROR, "Developer UI request failed.");
        try {
            writeError(exchange, error);
        } catch (IOException | RuntimeException ignored) {
            // The peer can already be disconnected or a streaming response can already be committed.
        }
    }

    private static void applyUIHeaders(Headers headers) {
        headers.set("Cache-Control", "no-store");
        headers.set("Content-Security-Policy", UI_CONTENT_SECURITY_POLICY);
        headers.set("Cross-Origin-Opener-Policy", "same-origin");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Permissions-Policy", "camera=(), geolocation=(), microphone=(), payment=(), usb=()");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
    }

    private static void applyErrorHeaders(Headers headers) {
        headers.set("Cache-Control", "no-store");
        headers.set("Content-Security-Policy", ERROR_CONTENT_SECURITY_POLICY);
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
    }

    private HostingHttpRequest request(HttpExchange exchange, RequestState state) throws IOException {
        Map<String, List<String>> headers = copyHeaders(exchange.getRequestHeaders(), options.maxHttpHeaderBytes());
        byte[] body = readBody(exchange, options.limits().maxRequestBytes(), state);
        return new HostingHttpRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI(),
                exchange.getRemoteAddress(),
                headers,
                body,
                state.cancellation());
    }

    private static Map<String, List<String>> copyHeaders(Headers source, int maximumBytes) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        long bytes = 0;
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            bytes += utf8Length(entry.getKey()) + 4L;
            ArrayList<String> values = new ArrayList<>(entry.getValue());
            for (String value : values) {
                bytes += utf8Length(value) + 2L;
            }
            if (bytes > maximumBytes) {
                throw new HostingException(
                        HostingErrorCode.MALFORMED_REQUEST, "HTTP headers exceed maxHttpHeaderBytes.");
            }
            result.put(entry.getKey(), values);
        }
        return result;
    }

    private byte[] readBody(HttpExchange exchange, long maximumBytes, RequestState state) throws IOException {
        List<String> lengths = exchange.getRequestHeaders().get("Content-Length");
        if (lengths != null) {
            validateContentLength(lengths, maximumBytes);
        }
        ScheduledFuture<?> deadline = scheduleBodyReadDeadline(state);
        try (InputStream input = exchange.getRequestBody();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if ((long) output.size() + read > maximumBytes) {
                    throw new HostingException(
                            HostingErrorCode.PAYLOAD_TOO_LARGE, "HTTP request body exceeds maxRequestBytes.");
                }
                output.write(buffer, 0, read);
                deadline.cancel(false);
                deadline = scheduleBodyReadDeadline(state);
            }
            return output.toByteArray();
        } finally {
            deadline.cancel(false);
        }
    }

    private ScheduledFuture<?> scheduleBodyReadDeadline(RequestState state) {
        return deadlineScheduler.schedule(
                state::abort, options.limits().idleTimeout().toNanos(), TimeUnit.NANOSECONDS);
    }

    private static void validateContentLength(List<String> values, long maximumBytes) {
        if (values.size() != 1) {
            throw new HostingException(HostingErrorCode.MALFORMED_REQUEST, "HTTP Content-Length must have one value.");
        }
        long length;
        try {
            length = Long.parseLong(values.getFirst());
        } catch (NumberFormatException failure) {
            throw new HostingException(HostingErrorCode.MALFORMED_REQUEST, "HTTP Content-Length is invalid.", failure);
        }
        if (length < 0) {
            throw new HostingException(HostingErrorCode.MALFORMED_REQUEST, "HTTP Content-Length must not be negative.");
        }
        if (length > maximumBytes) {
            throw new HostingException(
                    HostingErrorCode.PAYLOAD_TOO_LARGE, "HTTP request Content-Length exceeds maxRequestBytes.");
        }
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void validateRequestUri(URI uri) {
        String rawPath = uri.getRawPath();
        if (rawPath == null
                || rawPath.isEmpty()
                || !rawPath.startsWith("/")
                || rawPath.length() > MAX_REQUEST_PATH_CHARACTERS
                || rawPath.contains("\\")
                || rawPath.contains("//")
                || rawPath.contains("%")
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || Arrays.stream(rawPath.split("/", -1))
                        .anyMatch(segment -> ".".equals(segment) || "..".equals(segment))) {
            throw new HostingException(HostingErrorCode.MALFORMED_REQUEST, "Developer UI request path is invalid.");
        }
    }

    private static boolean isApiPath(String path) {
        return API_PATH.equals(path) || path.startsWith(API_PATH + "/");
    }

    private static <T> T await(CompletionStage<T> stage) throws InterruptedException {
        try {
            return stage.toCompletableFuture().get();
        } catch (ExecutionException exception) {
            Throwable cause = unwrap(exception);
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new HostingException(HostingErrorCode.INTERNAL_ERROR, "Developer UI operation failed.", cause);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof ExecutionException || current instanceof java.util.concurrent.CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private byte[] configuration() {
        return ("{\"version\":\""
                        + HostingJsonCodec.WIRE_VERSION
                        + "\",\"apiBasePath\":\""
                        + API_PATH
                        + "\",\"collections\":[\"agents\",\"workflows\",\"orchestrations\"],"
                        + "\"streamingTransport\":\"sse\",\"sameOrigin\":true}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private void closeResources(CompletableFuture<Void> result) {
        RuntimeException failure = null;
        try {
            List.copyOf(activeRequests).forEach(RequestState::abort);
            server.stop(0);
            handler.close();
            deadlineScheduler.shutdownNow();
            executor.shutdown();
            Duration timeout = options.gracefulShutdownTimeout();
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            deadlineScheduler.shutdownNow();
            executor.shutdownNow();
            failure = new HostingException(
                    HostingErrorCode.CLIENT_CANCELLED, "Developer UI shutdown was interrupted.", exception);
        } catch (RuntimeException closeFailure) {
            deadlineScheduler.shutdownNow();
            executor.shutdownNow();
            failure = closeFailure;
        }
        if (failure == null) {
            result.complete(null);
        } else {
            result.completeExceptionally(failure);
        }
    }

    private static URI advertisedOrigin(DevUIServerOptions options, int port) throws URISyntaxException {
        if (options.advertisedEndpoint() != null) {
            URI advertised = options.advertisedEndpoint();
            return new URI(advertised.getScheme(), null, advertised.getHost(), advertised.getPort(), null, null, null);
        }
        return new URI("http", null, options.bindAddress().getHostAddress(), port, null, null, null);
    }

    private static URI append(URI origin, String path) throws URISyntaxException {
        return new URI(origin.getScheme(), null, origin.getHost(), origin.getPort(), path, null, null);
    }

    private static void writeHeaders(Headers target, Map<String, List<String>> source) {
        source.forEach((name, values) -> values.forEach(value -> target.add(name, value)));
    }

    private sealed interface SseItem permits EventItem, SourceCompleteItem, OutcomeItem, ClosedItem {}

    private record EventItem(HostingEvent event) implements SseItem {}

    private record SourceCompleteItem() implements SseItem {}

    private record OutcomeItem(HostingOutcome outcome) implements SseItem {}

    private record ClosedItem() implements SseItem {}

    private static final class SseBridge implements Flow.Subscriber<HostingEvent>, AutoCloseable {
        private final BlockingQueue<SseItem> items;

        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

        private final CompletableFuture<Flow.Subscription> subscribed = new CompletableFuture<>();

        private final AtomicBoolean sourceComplete = new AtomicBoolean();

        private final AtomicBoolean closed = new AtomicBoolean();

        private SseBridge(int maximumBufferedEvents) {
            items = new ArrayBlockingQueue<>(Math.addExact(maximumBufferedEvents, 3));
        }

        private void start(HostingRun run) {
            run.terminalAsync().whenComplete((outcome, failure) -> {
                HostingOutcome value = outcome;
                if (value == null) {
                    value = HostingOutcome.failed(
                            run.runId(), HostingError.of(HostingErrorCode.INTERNAL_ERROR, "Hosted SSE run failed."));
                }
                offer(new OutcomeItem(value));
            });
            run.events().subscribe(this);
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            Objects.requireNonNull(value, "value");
            if (!subscription.compareAndSet(null, value)) {
                value.cancel();
                return;
            }
            subscribed.complete(value);
            if (closed.get()) {
                value.cancel();
            }
        }

        @Override
        public void onNext(HostingEvent item) {
            if (!closed.get()) {
                offer(new EventItem(Objects.requireNonNull(item, "item")));
            }
        }

        @Override
        public void onError(Throwable throwable) {
            completeSource();
        }

        @Override
        public void onComplete() {
            completeSource();
        }

        private void completeSource() {
            if (sourceComplete.compareAndSet(false, true)) {
                offer(new SourceCompleteItem());
            }
        }

        private void awaitSubscription(Duration timeout) throws InterruptedException {
            try {
                subscribed.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException failure) {
                throw new HostingException(
                        HostingErrorCode.RUN_TIMEOUT, "SSE publisher did not provide a subscription.", failure);
            }
        }

        private void requestNext() {
            Flow.Subscription value = subscription.get();
            if (value == null) {
                throw new HostingException(
                        HostingErrorCode.INTERNAL_ERROR, "SSE publisher subscription is unavailable.");
            }
            value.request(1);
        }

        private SseItem poll(Duration timeout) throws InterruptedException {
            SseItem item = items.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (item == null) {
                throw new HostingException(HostingErrorCode.RUN_TIMEOUT, "SSE transport exceeded idleTimeout.");
            }
            return item;
        }

        private void offer(SseItem item) {
            if (closed.get()) {
                return;
            }
            if (!items.offer(item)) {
                close();
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Flow.Subscription value = subscription.get();
            if (value != null) {
                value.cancel();
            }
            items.clear();
            items.offer(new ClosedItem());
        }
    }

    private static final class RequestState {
        private final HttpExchange exchange;

        private final DefaultRunCancellation cancellation = new DefaultRunCancellation();

        private final AtomicReference<HostingHttpResponse> response = new AtomicReference<>();

        private final AtomicReference<SseBridge> bridge = new AtomicReference<>();

        private final AtomicBoolean aborted = new AtomicBoolean();

        private final AtomicBoolean exchangeClosed = new AtomicBoolean();

        private RequestState(HttpExchange exchange) {
            this.exchange = Objects.requireNonNull(exchange, "exchange");
        }

        private DefaultRunCancellation cancellation() {
            return cancellation;
        }

        private boolean isAborted() {
            return aborted.get();
        }

        private void attach(HostingHttpResponse value) {
            response.set(Objects.requireNonNull(value, "value"));
        }

        private void attach(SseBridge value) {
            bridge.set(Objects.requireNonNull(value, "value"));
        }

        private void detach(SseBridge value) {
            bridge.compareAndSet(value, null);
        }

        private void cancelRun() {
            cancellation.cancel();
            HostingHttpResponse current = response.get();
            if (current != null && current.streamingRun() != null) {
                current.streamingRun().cancel();
                current.discardUndeliveredOutcome();
            }
            SseBridge currentBridge = bridge.get();
            if (currentBridge != null) {
                currentBridge.close();
            }
        }

        private void abort() {
            if (!aborted.compareAndSet(false, true)) {
                return;
            }
            cancelRun();
            closeExchange();
        }

        private void closeExchange() {
            if (exchangeClosed.compareAndSet(false, true)) {
                exchange.close();
            }
        }
    }
}
