// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.protocols.a2a.A2AErrorCode;
import com.microsoft.agents.protocols.a2a.A2AException;
import com.microsoft.agents.protocols.a2a.A2AJsonCodec;
import com.microsoft.agents.protocols.a2a.A2AProtocol;
import com.microsoft.agents.protocols.a2a.A2AProtocolException;
import com.microsoft.agents.protocols.a2a.A2AStreamEvent;
import com.microsoft.agents.protocols.a2a.AgentCard;
import com.microsoft.agents.protocols.a2a.AgentInterface;
import com.microsoft.agents.protocols.a2a.SendMessageRequest;
import com.microsoft.agents.protocols.a2a.SendMessageResult;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class EmbeddedA2AHttpServer implements A2AHttpServer {
    private final A2AService service;

    private final A2AHttpServerOptions options;

    private final HttpServer server;

    private final ExecutorService requestExecutor;

    private final A2AJsonCodec codec;

    private final Semaphore concurrency;

    private final URI endpoint;

    private final URI agentCardUri;

    private final Set<DefaultRunCancellation> activeStreams = ConcurrentHashMap.newKeySet();

    private final Set<SseSubscriber> streamSubscribers = ConcurrentHashMap.newKeySet();

    private final Set<HttpExchange> exchanges = ConcurrentHashMap.newKeySet();

    private final AtomicInteger activeRequests = new AtomicInteger();

    private final Object closeMonitor = new Object();

    private final AtomicBoolean running = new AtomicBoolean(true);

    private final AtomicReference<CompletableFuture<Void>> closeFuture = new AtomicReference<>();

    private EmbeddedA2AHttpServer(
            A2AService service,
            A2AHttpServerOptions options,
            HttpServer server,
            ExecutorService requestExecutor,
            URI endpoint,
            URI agentCardUri) {
        this.service = service;
        this.options = options;
        this.server = server;
        this.requestExecutor = requestExecutor;
        this.endpoint = endpoint;
        this.agentCardUri = agentCardUri;
        codec = new A2AJsonCodec(options.limits());
        concurrency = new Semaphore(options.limits().maxConcurrentRequests());
    }

    static A2AHttpServer start(A2AService service, A2AHttpServerOptions options) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(options, "options");
        HttpServer server;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            server = HttpServer.create(
                    new InetSocketAddress(options.bindAddress(), options.port()),
                    options.limits().maxConcurrentRequests());
            server.setExecutor(executor);
            int port = server.getAddress().getPort();
            URI endpoint = options.publicEndpoint() == null
                    ? uri(options.bindAddress().getHostAddress(), port, options.endpoint())
                    : options.publicEndpoint();
            URI cardUri = sibling(endpoint, A2AProtocol.AGENT_CARD_PATH);
            EmbeddedA2AHttpServer host =
                    new EmbeddedA2AHttpServer(service, options, server, executor, endpoint, cardUri);
            server.createContext(options.endpoint(), host::handleRpc);
            server.createContext(A2AProtocol.AGENT_CARD_PATH, host::handleCard);
            server.start();
            return host;
        } catch (IOException | RuntimeException failure) {
            executor.close();
            throw new A2AException("Unable to start embedded A2A HTTP server.", failure);
        }
    }

    @Override
    public URI endpoint() {
        return endpoint;
    }

    @Override
    public URI agentCardUri() {
        return agentCardUri;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void handleCard(HttpExchange exchange) {
        begin(exchange, () -> {
            if (!exchange.getRequestURI().getPath().equals(A2AProtocol.AGENT_CARD_PATH)) {
                writeEmpty(exchange, 404);
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                writeEmpty(exchange, 405);
                return;
            }
            validateRequestMetadata(exchange);
            AgentCard card = cardWithEndpoint(service.publicCard(), endpoint);
            writeJson(exchange, 200, codec.agentCardToValue(card), "application/json");
        });
    }

    private void handleRpc(HttpExchange exchange) {
        begin(exchange, () -> {
            if (!exchange.getRequestURI().getPath().equals(options.endpoint())) {
                writeEmpty(exchange, 404);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                writeEmpty(exchange, 405);
                return;
            }
            validateRequestMetadata(exchange);
            if (!supportedContentType(exchange)) {
                writeError(
                        exchange,
                        415,
                        StateValue.nullValue(),
                        new A2AProtocolException(
                                A2AErrorCode.CONTENT_TYPE_NOT_SUPPORTED,
                                "JSON-RPC Content-Type must be application/json."));
                return;
            }
            byte[] body = readRequest(exchange);
            StateValue id = StateValue.nullValue();
            try {
                StateValue.ObjectValue request = requireObject(codec.parse(body), "JSON-RPC request");
                validateRequestEnvelope(request);
                id = request.values().get("id");
                if (!validId(id)) {
                    throw new A2AProtocolException(
                            A2AErrorCode.INVALID_REQUEST, "JSON-RPC id must be a string or number.");
                }
                String method = requireString(request, "method");
                StateValue params = request.values().get("params");
                if (params == null) {
                    throw new A2AProtocolException(A2AErrorCode.INVALID_REQUEST, "JSON-RPC params are required.");
                }
                if (!A2AProtocol.VERSION.equals(exchange.getRequestHeaders().getFirst("A2A-Version"))) {
                    throw new A2AProtocolException(
                            A2AErrorCode.VERSION_NOT_SUPPORTED, "Only A2A protocol version 1.0 is supported.");
                }
                A2APrincipal principal = authenticate(exchange);
                if (A2AProtocol.SEND_STREAMING_MESSAGE.equals(method) || A2AProtocol.SUBSCRIBE_TO_TASK.equals(method)) {
                    dispatchStreaming(exchange, id, method, params, principal);
                } else {
                    StateValue result = dispatchFinite(method, params, principal);
                    writeRpcResult(exchange, id, result);
                }
            } catch (A2AAuthenticationException failure) {
                writeError(
                        exchange,
                        failure.statusCode(),
                        id,
                        new A2AProtocolException(A2AErrorCode.INVALID_REQUEST, failure.getMessage()));
            } catch (A2AProtocolException failure) {
                writeError(exchange, 200, id, failure);
            } catch (ValidationException failure) {
                writeError(
                        exchange, 200, id, new A2AProtocolException(A2AErrorCode.INVALID_PARAMS, failure.getMessage()));
            } catch (A2AException failure) {
                writeError(
                        exchange,
                        200,
                        id,
                        new A2AProtocolException(A2AErrorCode.INTERNAL_ERROR, "A2A operation failed."));
            } catch (RuntimeException failure) {
                writeError(
                        exchange,
                        200,
                        id,
                        new A2AProtocolException(A2AErrorCode.INTERNAL_ERROR, "Internal A2A server error."));
            }
        });
    }

    private StateValue dispatchFinite(String method, StateValue params, A2APrincipal principal) {
        return switch (method) {
            case A2AProtocol.SEND_MESSAGE -> {
                SendMessageResult result =
                        join(service.sendMessageAsync(principal, codec.sendMessageRequestFromValue(params)));
                yield codec.sendMessageResultToValue(result);
            }
            case A2AProtocol.GET_TASK ->
                codec.taskToValue(join(service.getTaskAsync(principal, codec.getTaskRequestFromValue(params))));
            case A2AProtocol.LIST_TASKS ->
                codec.taskPageToValue(join(service.listTasksAsync(principal, codec.listTasksRequestFromValue(params))));
            case A2AProtocol.CANCEL_TASK ->
                codec.taskToValue(join(service.cancelTaskAsync(principal, codec.cancelTaskRequestFromValue(params))));
            case A2AProtocol.CREATE_PUSH_CONFIG ->
                codec.pushConfigToValue(
                        join(service.createPushNotificationConfigAsync(principal, codec.pushConfigFromValue(params))));
            case A2AProtocol.GET_PUSH_CONFIG ->
                codec.pushConfigToValue(join(service.getPushNotificationConfigAsync(
                        principal, codec.getPushConfigRequestFromValue(params))));
            case A2AProtocol.LIST_PUSH_CONFIGS ->
                codec.pushConfigPageToValue(join(service.listPushNotificationConfigsAsync(
                        principal, codec.listPushConfigsRequestFromValue(params))));
            case A2AProtocol.DELETE_PUSH_CONFIG -> {
                join(service.deletePushNotificationConfigAsync(
                        principal, codec.deletePushConfigRequestFromValue(params)));
                yield StateValue.object(Map.of());
            }
            case A2AProtocol.GET_EXTENDED_AGENT_CARD ->
                codec.agentCardToValue(cardWithEndpoint(
                        join(service.getExtendedAgentCardAsync(codec.extendedCardRequestFromValue(params))), endpoint));
            default ->
                throw new A2AProtocolException(A2AErrorCode.METHOD_NOT_FOUND, "JSON-RPC method is not supported.");
        };
    }

    private void dispatchStreaming(
            HttpExchange exchange, StateValue id, String method, StateValue params, A2APrincipal principal) {
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        Flow.Publisher<A2AStreamEvent> publisher;
        if (A2AProtocol.SEND_STREAMING_MESSAGE.equals(method)) {
            SendMessageRequest request = codec.sendMessageRequestFromValue(params);
            publisher = service.sendMessageStreaming(principal, request, cancellation);
        } else {
            publisher = service.subscribeToTask(principal, codec.subscribeRequestFromValue(params));
        }
        activeStreams.add(cancellation);
        Headers headers = exchange.getResponseHeaders();
        securityHeaders(headers);
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache");
        try {
            exchange.sendResponseHeaders(200, 0);
        } catch (IOException failure) {
            activeStreams.remove(cancellation);
            cancellation.cancel();
            throw new A2AException("Unable to start A2A SSE response.", failure);
        }
        exchange.setAttribute("a2a.streaming", Boolean.TRUE);
        SseSubscriber subscriber = new SseSubscriber(exchange, id, cancellation);
        streamSubscribers.add(subscriber);
        try {
            publisher.subscribe(subscriber);
        } catch (RuntimeException failure) {
            subscriber.close();
            throw failure;
        }
    }

    private void begin(HttpExchange exchange, ThrowingRunnable action) {
        if (!running.get()) {
            safeEmpty(exchange, 503);
            exchange.close();
            return;
        }
        if (!concurrency.tryAcquire()) {
            safeError(
                    exchange,
                    429,
                    StateValue.nullValue(),
                    new A2AProtocolException(A2AErrorCode.INTERNAL_ERROR, "A2A host concurrency limit is exhausted."));
            exchange.close();
            return;
        }
        activeRequests.incrementAndGet();
        exchanges.add(exchange);
        try {
            action.run();
        } catch (A2AAuthenticationException failure) {
            safeEmpty(exchange, failure.statusCode());
        } catch (A2AProtocolException failure) {
            safeError(exchange, 200, StateValue.nullValue(), failure);
        } catch (ValidationException failure) {
            safeError(
                    exchange,
                    400,
                    StateValue.nullValue(),
                    new A2AProtocolException(A2AErrorCode.INVALID_REQUEST, failure.getMessage()));
        } catch (A2AException failure) {
            safeError(
                    exchange,
                    500,
                    StateValue.nullValue(),
                    new A2AProtocolException(A2AErrorCode.INTERNAL_ERROR, "Internal A2A server error."));
        } catch (IOException failure) {
            safeEmpty(exchange, 500);
        } catch (RuntimeException failure) {
            safeError(
                    exchange,
                    500,
                    StateValue.nullValue(),
                    new A2AProtocolException(A2AErrorCode.INTERNAL_ERROR, "Internal A2A server error."));
        } finally {
            if (!Boolean.TRUE.equals(exchange.getAttribute("a2a.streaming"))) {
                endExchange(exchange);
            }
        }
    }

    private void validateRequestMetadata(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || !matches(options.allowedHosts(), host)) {
            throw new A2AAuthenticationException(403, "Host header is not allowed.");
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !matches(options.allowedOrigins(), origin)) {
            throw new A2AAuthenticationException(403, "Origin header is not allowed.");
        }
    }

    private A2APrincipal authenticate(HttpExchange exchange) {
        A2AHostAuthenticator authenticator = options.authenticator();
        if (authenticator == null) {
            if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
                throw new A2AAuthenticationException(403, "Anonymous A2A access is limited to loopback.");
            }
            return A2APrincipal.loopbackAnonymous();
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
        A2AHostRequest request = new A2AHostRequest(
                exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRemoteAddress(), headers);
        try {
            return Objects.requireNonNull(join(authenticator.authenticateAsync(request)), "authenticator principal");
        } catch (A2AAuthenticationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new A2AAuthenticationException(401, "Authentication failed.");
        }
    }

    private byte[] readRequest(HttpExchange exchange) throws IOException {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                long length = Long.parseLong(contentLength);
                if (length < 0 || length > options.limits().maxRequestBytes()) {
                    throw new A2AProtocolException(
                            A2AErrorCode.INVALID_REQUEST, "A2A request exceeds maxRequestBytes.");
                }
            } catch (NumberFormatException failure) {
                throw new A2AProtocolException(
                        A2AErrorCode.INVALID_REQUEST, "Content-Length is invalid.", null, failure);
            }
        }
        try (InputStream input = exchange.getRequestBody()) {
            byte[] bytes = input.readNBytes(options.limits().maxRequestBytes() + 1);
            if (bytes.length > options.limits().maxRequestBytes()) {
                throw new A2AProtocolException(A2AErrorCode.INVALID_REQUEST, "A2A request exceeds maxRequestBytes.");
            }
            return bytes;
        }
    }

    private static boolean supportedContentType(HttpExchange exchange) {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.startsWith("application/json");
    }

    private static void validateRequestEnvelope(StateValue.ObjectValue request) {
        if (!A2AProtocol.JSON_RPC_VERSION.equals(requireString(request, "jsonrpc"))) {
            throw new A2AProtocolException(A2AErrorCode.INVALID_REQUEST, "JSON-RPC request version must be 2.0.");
        }
        if (request.values().containsKey("result") || request.values().containsKey("error")) {
            throw new A2AProtocolException(
                    A2AErrorCode.INVALID_REQUEST, "JSON-RPC request must not contain result or error.");
        }
    }

    private void writeRpcResult(HttpExchange exchange, StateValue id, StateValue result) {
        LinkedHashMap<String, StateValue> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", StateValue.string(A2AProtocol.JSON_RPC_VERSION));
        envelope.put("id", id);
        envelope.put("result", result);
        writeJson(exchange, 200, StateValue.object(envelope), "application/json");
    }

    private void writeError(HttpExchange exchange, int status, StateValue id, A2AProtocolException failure) {
        LinkedHashMap<String, StateValue> error = new LinkedHashMap<>();
        error.put("code", StateValue.integer(failure.rawErrorCode()));
        error.put("message", StateValue.string(failure.getMessage()));
        if (failure.data() != null) {
            error.put("data", failure.data());
        }
        LinkedHashMap<String, StateValue> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", StateValue.string(A2AProtocol.JSON_RPC_VERSION));
        envelope.put("id", id == null ? StateValue.nullValue() : id);
        envelope.put("error", StateValue.object(error));
        writeJson(exchange, status, StateValue.object(envelope), "application/json");
    }

    private void writeJson(HttpExchange exchange, int status, StateValue value, String contentType) {
        byte[] bytes = codec.write(value);
        if (bytes.length > options.limits().maxResponseBytes()) {
            throw new A2AException("A2A response exceeds maxResponseBytes.");
        }
        Headers headers = exchange.getResponseHeaders();
        securityHeaders(headers);
        headers.set("Content-Type", contentType + "; charset=utf-8");
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } catch (IOException failure) {
            throw new A2AException("Unable to write A2A HTTP response.", failure);
        }
    }

    private static void writeEmpty(HttpExchange exchange, int status) throws IOException {
        securityHeaders(exchange.getResponseHeaders());
        exchange.sendResponseHeaders(status, -1);
    }

    private void safeError(HttpExchange exchange, int status, StateValue id, A2AProtocolException failure) {
        try {
            writeError(exchange, status, id, failure);
        } catch (RuntimeException ignored) {
            safeEmpty(exchange, status);
        }
    }

    private static void safeEmpty(HttpExchange exchange, int status) {
        try {
            writeEmpty(exchange, status);
        } catch (IOException ignored) {
            exchange.close();
        }
    }

    private static void securityHeaders(Headers headers) {
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cache-Control", "no-store");
    }

    private void endExchange(HttpExchange exchange) {
        if (!exchanges.remove(exchange)) {
            return;
        }
        exchange.close();
        concurrency.release();
        if (activeRequests.decrementAndGet() == 0) {
            synchronized (closeMonitor) {
                closeMonitor.notifyAll();
            }
        }
    }

    private static boolean matches(Set<String> patterns, String actual) {
        String normalized = actual.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            String candidate = pattern.toLowerCase(Locale.ROOT);
            if (candidate.equals(normalized)) {
                return true;
            }
            if (candidate.endsWith(":*")) {
                String prefix = candidate.substring(0, candidate.length() - 1);
                if (normalized.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean validId(StateValue id) {
        return id instanceof StateValue.StringValue || id instanceof StateValue.NumberValue;
    }

    private static StateValue.ObjectValue requireObject(StateValue value, String name) {
        if (value instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw new A2AProtocolException(A2AErrorCode.INVALID_REQUEST, name + " must be an object.");
    }

    private static String requireString(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw new A2AProtocolException(
                A2AErrorCode.INVALID_REQUEST, "JSON-RPC member '" + name + "' must be a string.");
    }

    private static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new A2AException("A2A host operation failed.", cause);
        }
    }

    private static AgentCard cardWithEndpoint(AgentCard card, URI endpoint) {
        boolean endpointChanged = card.supportedInterfaces().stream()
                .anyMatch(agentInterface ->
                        "JSONRPC".equals(agentInterface.protocolBinding()) && !endpoint.equals(agentInterface.url()));
        List<AgentInterface> interfaces = card.supportedInterfaces().stream()
                .map(agentInterface -> "JSONRPC".equals(agentInterface.protocolBinding())
                        ? new AgentInterface(
                                agentInterface.protocolBinding(),
                                endpoint,
                                agentInterface.protocolVersion(),
                                agentInterface.tenant())
                        : agentInterface)
                .toList();
        return new AgentCard(
                card.name(),
                card.description(),
                card.provider(),
                card.version(),
                card.documentationUrl(),
                card.capabilities(),
                card.defaultInputModes(),
                card.defaultOutputModes(),
                card.skills(),
                card.securitySchemes(),
                card.securityRequirements(),
                card.iconUrl(),
                interfaces,
                endpointChanged ? List.of() : card.signatures(),
                card.additionalProperties());
    }

    private static URI uri(String host, int port, String path) {
        try {
            return new URI("http", null, host, port, path, null, null);
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Unable to construct embedded A2A URI.", exception);
        }
    }

    private static URI sibling(URI base, String path) {
        try {
            return new URI(base.getScheme(), null, base.getHost(), base.getPort(), path, null, null);
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Unable to construct A2A card URI.", exception);
        }
    }

    @Override
    public CompletionStage<Void> closeAsync() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (!closeFuture.compareAndSet(null, result)) {
            return Objects.requireNonNull(closeFuture.get()).minimalCompletionStage();
        }
        running.set(false);
        Thread.startVirtualThread(() -> {
            RuntimeException closeFailure = null;
            try {
                streamSubscribers.forEach(SseSubscriber::close);
                activeStreams.forEach(DefaultRunCancellation::cancel);
                List.copyOf(exchanges).forEach(this::endExchange);
                server.stop(0);
                waitForRequests(options.closeTimeout());
            } catch (RuntimeException failure) {
                closeFailure = failure;
            } finally {
                requestExecutor.shutdown();
                try {
                    if (!requestExecutor.awaitTermination(
                            options.closeTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        requestExecutor.shutdownNow();
                    }
                } catch (InterruptedException exception) {
                    requestExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                    if (closeFailure == null) {
                        closeFailure = new A2AException("A2A server close was interrupted.", exception);
                    }
                }
            }
            if (closeFailure == null) {
                result.complete(null);
            } else {
                result.completeExceptionally(closeFailure);
            }
        });
        return result.minimalCompletionStage();
    }

    @Override
    public void close() {
        try {
            closeAsync()
                    .toCompletableFuture()
                    .get(options.closeTimeout().toMillis() * 2, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new A2AException("A2A server close was interrupted.", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new A2AException("A2A server close failed.", exception.getCause());
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new A2AException("A2A server close timed out.", exception);
        }
    }

    private void waitForRequests(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (closeMonitor) {
            while (activeRequests.get() > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new A2AException("A2A server close timed out with active requests.");
                }
                try {
                    java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(closeMonitor, remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new A2AException("A2A server close was interrupted.", exception);
                }
            }
        }
    }

    private final class SseSubscriber implements Flow.Subscriber<A2AStreamEvent> {
        private final HttpExchange exchange;

        private final StateValue id;

        private final DefaultRunCancellation cancellation;

        private final OutputStream output;

        private final AtomicBoolean finished = new AtomicBoolean();

        private Flow.Subscription subscription;

        private SseSubscriber(HttpExchange exchange, StateValue id, DefaultRunCancellation cancellation) {
            this.exchange = exchange;
            this.id = id;
            this.cancellation = cancellation;
            this.output = exchange.getResponseBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        @Override
        public synchronized void onNext(A2AStreamEvent event) {
            if (finished.get()) {
                return;
            }
            LinkedHashMap<String, StateValue> envelope = new LinkedHashMap<>();
            envelope.put("jsonrpc", StateValue.string(A2AProtocol.JSON_RPC_VERSION));
            envelope.put("id", id);
            envelope.put("result", codec.streamEventToValue(event));
            byte[] json = codec.write(StateValue.object(envelope));
            if (json.length > options.limits().maxEventBytes()) {
                fail(new A2AException("A2A SSE event exceeds maxEventBytes."));
                return;
            }
            try {
                output.write("data: ".getBytes(StandardCharsets.UTF_8));
                output.write(json);
                output.write('\n');
                output.write('\n');
                output.flush();
                subscription.request(1);
            } catch (IOException failure) {
                fail(failure);
            }
        }

        @Override
        public void onError(Throwable failure) {
            finish(true);
        }

        @Override
        public void onComplete() {
            finish(false);
        }

        private void fail(Throwable failure) {
            Flow.Subscription current = subscription;
            if (current != null) {
                current.cancel();
            }
            cancellation.cancel();
            finish(true);
        }

        private void close() {
            Flow.Subscription current = subscription;
            if (current != null) {
                current.cancel();
            }
            cancellation.cancel();
            finish(true);
        }

        private void finish(boolean cancelRun) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            if (cancelRun) {
                cancellation.cancel();
            }
            activeStreams.remove(cancellation);
            streamSubscribers.remove(this);
            try {
                output.close();
            } catch (IOException ignored) {
                // The peer may already have disconnected.
            }
            endExchange(exchange);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws IOException;
    }
}
