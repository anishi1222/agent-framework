// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.StateValue;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

final class JdkA2AClient implements A2AClient {
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "a2a-version",
            "accept",
            "connection",
            "content-length",
            "content-type",
            "expect",
            "host",
            "transfer-encoding",
            "upgrade");

    private final A2AClientOptions options;
    private final A2AJsonCodec codec;
    private final HttpClient httpClient;
    private final Semaphore concurrentRequests;
    private final AtomicLong requestIds = new AtomicLong();
    private final Set<CompletableFuture<?>> activeRequests = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    JdkA2AClient(A2AClientOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        codec = new A2AJsonCodec(options.limits());
        concurrentRequests = new Semaphore(options.limits().maxConcurrentRequests());
        httpClient = HttpClient.newBuilder()
                .connectTimeout(options.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    @Override
    public RunHandle<AgentCard> startFetchAgentCard() {
        URI cardUri = publicCardUri();
        return startGet("FetchAgentCard", cardUri, bytes -> codec.agentCardFromValue(codec.parse(bytes)));
    }

    @Override
    public RunHandle<AgentCard> startFetchExtendedAgentCard(A2ARequests.GetExtendedAgentCard request) {
        Objects.requireNonNull(request, "request");
        return startRpc(
                A2AProtocol.GET_EXTENDED_AGENT_CARD,
                codec.extendedCardRequestToValue(request),
                codec::agentCardFromValue);
    }

    @Override
    public RunHandle<SendMessageResult> startSendMessage(SendMessageRequest request) {
        Objects.requireNonNull(request, "request");
        return startRpc(
                A2AProtocol.SEND_MESSAGE, codec.sendMessageRequestToValue(request), codec::sendMessageResultFromValue);
    }

    @Override
    public Flow.Publisher<A2AStreamEvent> sendMessageStreaming(
            SendMessageRequest request, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        return stream(
                A2AProtocol.SEND_STREAMING_MESSAGE, codec.sendMessageRequestToValue(request), cancellation, false);
    }

    @Override
    public RunHandle<Task> startGetTask(A2ARequests.GetTask request) {
        Objects.requireNonNull(request, "request");
        return startRpc(A2AProtocol.GET_TASK, codec.getTaskRequestToValue(request), codec::taskFromValue);
    }

    @Override
    public RunHandle<A2ACursorPage<Task>> startListTasks(A2ARequests.ListTasks request) {
        Objects.requireNonNull(request, "request");
        return startRpc(A2AProtocol.LIST_TASKS, codec.listTasksRequestToValue(request), codec::taskPageFromValue);
    }

    @Override
    public CompletableFuture<List<Task>> listAllTasksAsync(A2ARequests.ListTasks request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<List<Task>> result = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                ArrayList<Task> tasks = new ArrayList<>();
                HashSet<String> cursors = new HashSet<>();
                A2ARequests.ListTasks current = request;
                while (true) {
                    A2ACursorPage<Task> page =
                            listTasksAsync(current).toCompletableFuture().get();
                    tasks.addAll(page.items());
                    if (tasks.size() > options.limits().maxCollectionEntries()) {
                        throw new A2ATransportException("ListTasks exceeded maxCollectionEntries across pages.");
                    }
                    if (!page.hasNextPage()) {
                        result.complete(List.copyOf(tasks));
                        return;
                    }
                    if (!cursors.add(page.nextPageToken())) {
                        throw new A2AProtocolException(
                                A2AErrorCode.INVALID_AGENT_RESPONSE, "ListTasks returned a cursor loop.");
                    }
                    current = current.next(page.nextPageToken());
                }
            } catch (Throwable failure) {
                result.completeExceptionally(unwrapExecution(failure));
            }
        });
        return result;
    }

    @Override
    public RunHandle<Task> startCancelTask(A2ARequests.CancelTask request) {
        Objects.requireNonNull(request, "request");
        return startRpc(A2AProtocol.CANCEL_TASK, codec.cancelTaskRequestToValue(request), codec::taskFromValue);
    }

    @Override
    public Flow.Publisher<A2AStreamEvent> subscribeToTaskStreaming(
            A2ARequests.SubscribeToTask request, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        return stream(A2AProtocol.SUBSCRIBE_TO_TASK, codec.subscribeRequestToValue(request), cancellation, true);
    }

    @Override
    public RunHandle<PushNotificationConfig> startCreatePushNotificationConfig(PushNotificationConfig config) {
        Objects.requireNonNull(config, "config");
        return startRpc(A2AProtocol.CREATE_PUSH_CONFIG, codec.pushConfigToValue(config), codec::pushConfigFromValue);
    }

    @Override
    public RunHandle<PushNotificationConfig> startGetPushNotificationConfig(A2ARequests.GetPushConfig request) {
        Objects.requireNonNull(request, "request");
        return startRpc(
                A2AProtocol.GET_PUSH_CONFIG, codec.getPushConfigRequestToValue(request), codec::pushConfigFromValue);
    }

    @Override
    public RunHandle<A2ACursorPage<PushNotificationConfig>> startListPushNotificationConfigs(
            A2ARequests.ListPushConfigs request) {
        Objects.requireNonNull(request, "request");
        return startRpc(
                A2AProtocol.LIST_PUSH_CONFIGS,
                codec.listPushConfigsRequestToValue(request),
                codec::pushConfigPageFromValue);
    }

    @Override
    public CompletableFuture<List<PushNotificationConfig>> listAllPushNotificationConfigsAsync(
            A2ARequests.ListPushConfigs request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<List<PushNotificationConfig>> result = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                ArrayList<PushNotificationConfig> configs = new ArrayList<>();
                HashSet<String> cursors = new HashSet<>();
                A2ARequests.ListPushConfigs current = request;
                while (true) {
                    A2ACursorPage<PushNotificationConfig> page = listPushNotificationConfigsAsync(current)
                            .toCompletableFuture()
                            .get();
                    configs.addAll(page.items());
                    if (configs.size() > options.limits().maxCollectionEntries()) {
                        throw new A2ATransportException("Push configuration listing exceeded maxCollectionEntries.");
                    }
                    if (!page.hasNextPage()) {
                        result.complete(List.copyOf(configs));
                        return;
                    }
                    if (!cursors.add(page.nextPageToken())) {
                        throw new A2AProtocolException(
                                A2AErrorCode.INVALID_AGENT_RESPONSE,
                                "Push configuration listing returned a cursor loop.");
                    }
                    current = new A2ARequests.ListPushConfigs(
                            current.taskId(), current.pageSize(), page.nextPageToken(), current.tenant());
                }
            } catch (Throwable failure) {
                result.completeExceptionally(unwrapExecution(failure));
            }
        });
        return result;
    }

    @Override
    public RunHandle<Boolean> startDeletePushNotificationConfig(A2ARequests.DeletePushConfig request) {
        Objects.requireNonNull(request, "request");
        return startRpc(
                A2AProtocol.DELETE_PUSH_CONFIG, codec.deletePushConfigRequestToValue(request), ignored -> Boolean.TRUE);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        activeRequests.forEach(future -> future.cancel(true));
        activeRequests.clear();
        httpClient.shutdown();
        try {
            if (!httpClient.awaitTermination(options.closeTimeout())) {
                httpClient.shutdownNow();
                httpClient.awaitTermination(options.closeTimeout());
            }
        } catch (InterruptedException exception) {
            httpClient.shutdownNow();
            Thread.currentThread().interrupt();
            throw new A2ATransportException("A2A client close was interrupted.", exception);
        }
    }

    private <T> RunHandle<T> startRpc(String method, StateValue params, Function<StateValue, T> decoder) {
        String id = nextRequestId();
        StateValue.ObjectValue envelope = requestEnvelope(method, id, params);
        byte[] body = codec.write(envelope);
        HttpRequest request = postRequest(method, body, false);
        return startFinite(
                request,
                method,
                responseBody -> decoder.apply(decodeRpcResult(responseBody, id)),
                "application/a2a+json");
    }

    private <T> RunHandle<T> startGet(String operation, URI uri, Function<byte[], T> decoder) {
        validateTarget(uri);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(options.requestTimeout())
                .header("Accept", "application/json, application/a2a+json")
                .header("A2A-Version", A2AProtocol.VERSION);
        addProvidedHeaders(builder, operation, uri, false);
        return startFinite(builder.build(), operation, decoder, "application/json");
    }

    private <T> RunHandle<T> startFinite(
            HttpRequest request, String operation, Function<byte[], T> decoder, String preferredMediaType) {
        RunHandleSource<T> source = new RunHandleSource<>();
        if (closed.get()) {
            source.tryFail(new IllegalStateException("A2A client is closed."));
            return source.handle();
        }
        if (!concurrentRequests.tryAcquire()) {
            source.tryFail(new A2ATransportException("A2A client concurrent-request limit is exhausted."));
            return source.handle();
        }
        AtomicReference<InputStream> bodyRef = new AtomicReference<>();
        CompletableFuture<HttpResponse<InputStream>> future;
        try {
            future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (RuntimeException failure) {
            concurrentRequests.release();
            source.tryFail(new A2ATransportException(operation + " could not start.", failure));
            return source.handle();
        }
        activeRequests.add(future);
        RunCancellationRegistration registration = RunCancellations.register(source.cancellation(), () -> {
            future.cancel(true);
            close(bodyRef.get());
        });
        future.whenComplete((response, failure) -> {
            if (failure != null) {
                activeRequests.remove(future);
                registration.close();
                concurrentRequests.release();
                if (!source.isTerminal()) {
                    source.tryFail(new A2ATransportException(operation + " HTTP request failed.", unwrap(failure)));
                }
                return;
            }
            bodyRef.set(response.body());
            Thread.startVirtualThread(() -> {
                try (InputStream input = response.body()) {
                    validateFiniteResponse(response, preferredMediaType);
                    byte[] bytes = readBounded(input, options.limits().maxResponseBytes());
                    T value = Objects.requireNonNull(decoder.apply(bytes), "decoded result");
                    source.tryComplete(value);
                } catch (Throwable decodeFailure) {
                    if (!source.isTerminal()) {
                        source.tryFail(decodeFailure);
                    }
                } finally {
                    activeRequests.remove(future);
                    registration.close();
                    concurrentRequests.release();
                }
            });
        });
        return source.handle();
    }

    private Flow.Publisher<A2AStreamEvent> stream(
            String method, StateValue params, RunCancellation cancellation, boolean subscription) {
        Objects.requireNonNull(cancellation, "cancellation");
        String id = nextRequestId();
        byte[] body = codec.write(requestEnvelope(method, id, params));
        HttpRequest request = postRequest(method, body, true);
        return new A2AStreamingPublisher(
                () -> {
                    if (closed.get()) {
                        throw new IllegalStateException("A2A client is closed.");
                    }
                    CompletableFuture<HttpResponse<InputStream>> future =
                            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
                    activeRequests.add(future);
                    future.whenComplete((ignored, failure) -> activeRequests.remove(future));
                    return future;
                },
                this::validateStreamResponse,
                eventBytes -> codec.streamEventFromValue(decodeRpcResult(eventBytes, id)),
                new A2AEventValidator(subscription),
                cancellation,
                options.limits(),
                concurrentRequests::tryAcquire,
                concurrentRequests::release);
    }

    private HttpRequest postRequest(String method, byte[] body, boolean streaming) {
        URI uri = options.endpoint();
        validateTarget(uri);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(options.requestTimeout())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Content-Type", "application/a2a+json")
                .header("Accept", streaming ? "text/event-stream" : "application/a2a+json, application/json")
                .header("A2A-Version", A2AProtocol.VERSION);
        addProvidedHeaders(builder, method, uri, streaming);
        return builder.build();
    }

    private void addProvidedHeaders(HttpRequest.Builder builder, String method, URI uri, boolean streaming) {
        Map<String, String> headers = Objects.requireNonNull(
                options.headerProvider().headers(new A2ARequestContext(method, uri, streaming)),
                "headerProvider result");
        headers.forEach((name, value) -> {
            String safeName = A2AValidation.nonBlank(name, "header name");
            String lower = safeName.toLowerCase(Locale.ROOT);
            if (RESERVED_HEADERS.contains(lower) || !safeName.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                throw new com.microsoft.agents.core.ValidationException(
                        "Header provider returned a reserved or invalid header name.");
            }
            Objects.requireNonNull(value, "header value");
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new com.microsoft.agents.core.ValidationException(
                        "Header provider returned a value containing a line break.");
            }
            builder.header(safeName, value);
        });
    }

    private StateValue.ObjectValue requestEnvelope(String method, String id, StateValue params) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        values.put("jsonrpc", StateValue.string(A2AProtocol.JSON_RPC_VERSION));
        values.put("id", StateValue.string(id));
        values.put("method", StateValue.string(method));
        values.put("params", params);
        return StateValue.object(values);
    }

    private StateValue decodeRpcResult(byte[] bytes, String expectedId) {
        StateValue.ObjectValue response = requireObject(codec.parse(bytes), "JSON-RPC response");
        String version = requireString(response, "jsonrpc");
        if (!A2AProtocol.JSON_RPC_VERSION.equals(version)) {
            throw new A2AProtocolException(
                    A2AErrorCode.INVALID_AGENT_RESPONSE, "JSON-RPC response version must be 2.0.");
        }
        String id = requireString(response, "id");
        if (!expectedId.equals(id)) {
            throw new A2AProtocolException(
                    A2AErrorCode.INVALID_AGENT_RESPONSE, "JSON-RPC response id does not match the request.");
        }
        boolean hasResult = response.values().containsKey("result");
        boolean hasError = response.values().containsKey("error");
        if (hasResult == hasError) {
            throw new A2AProtocolException(
                    A2AErrorCode.INVALID_AGENT_RESPONSE,
                    "JSON-RPC response must contain exactly one of result or error.");
        }
        if (hasError) {
            throw decodeRpcError(response.values().get("error"));
        }
        return response.values().get("result");
    }

    private A2AProtocolException decodeRpcError(StateValue value) {
        StateValue.ObjectValue error = requireObject(value, "JSON-RPC error");
        int code = requireInt(error, "code");
        String message = requireString(error, "message");
        StateValue data = error.values().get("data");
        return new A2AProtocolException(A2AErrorCode.fromCode(code), message, data, null);
    }

    private void validateFiniteResponse(HttpResponse<?> response, String preferredMediaType) {
        validateStatus(response);
        String contentType =
                response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (!contentType.startsWith(preferredMediaType)
                && !contentType.startsWith("application/a2a+json")
                && !contentType.startsWith("application/json")) {
            throw new A2ATransportException("A2A response has unsupported media type.");
        }
    }

    private void validateStreamResponse(HttpResponse<InputStream> response) {
        validateStatus(response);
        String contentType =
                response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (!contentType.startsWith("text/event-stream")) {
            throw new A2ATransportException("A2A streaming response must use text/event-stream.");
        }
    }

    private static void validateStatus(HttpResponse<?> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new A2ATransportException("A2A HTTP request failed with status " + response.statusCode() + ".");
        }
    }

    private void validateTarget(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!options.allowedHosts().contains(host)) {
            throw new com.microsoft.agents.core.ValidationException("A2A request host is not allowlisted.");
        }
    }

    private URI publicCardUri() {
        URI endpoint = options.endpoint();
        if (A2AProtocol.AGENT_CARD_PATH.equals(endpoint.getPath())) {
            return endpoint;
        }
        try {
            return new URI(
                    endpoint.getScheme(),
                    null,
                    endpoint.getHost(),
                    endpoint.getPort(),
                    A2AProtocol.AGENT_CARD_PATH,
                    null,
                    null);
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalStateException("Validated A2A endpoint could not form card URI.", exception);
        }
    }

    private String nextRequestId() {
        return "af-java-" + requestIds.incrementAndGet();
    }

    private static byte[] readBounded(InputStream input, int maximum) throws IOException {
        byte[] bytes = input.readNBytes(maximum + 1);
        if (bytes.length > maximum) {
            throw new A2ATransportException("A2A response exceeds maxResponseBytes.");
        }
        return bytes;
    }

    private static StateValue.ObjectValue requireObject(StateValue value, String name) {
        if (value instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw new A2AProtocolException(A2AErrorCode.INVALID_AGENT_RESPONSE, name + " must be an object.");
    }

    private static String requireString(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw new A2AProtocolException(
                A2AErrorCode.INVALID_AGENT_RESPONSE, "JSON-RPC member '" + name + "' must be a string.");
    }

    private static int requireInt(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value instanceof StateValue.NumberValue number) {
            try {
                return number.value().intValueExact();
            } catch (ArithmeticException exception) {
                throw new A2AProtocolException(
                        A2AErrorCode.INVALID_AGENT_RESPONSE,
                        "JSON-RPC member '" + name + "' must be an integer.",
                        null,
                        exception);
            }
        }
        throw new A2AProtocolException(
                A2AErrorCode.INVALID_AGENT_RESPONSE, "JSON-RPC member '" + name + "' must be a number.");
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }

    private static Throwable unwrapExecution(Throwable failure) {
        if ((failure instanceof java.util.concurrent.ExecutionException
                        || failure instanceof java.util.concurrent.CompletionException)
                && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private static void close(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // Cancellation already owns the terminal outcome.
        }
    }
}
