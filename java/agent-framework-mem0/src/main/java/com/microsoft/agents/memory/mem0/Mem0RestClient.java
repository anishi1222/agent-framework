// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.memory.mem0;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class Mem0RestClient implements AutoCloseable {
    private static final String ADD_PATH = "v3/memories/add/";

    private static final String SEARCH_PATH = "v3/memories/search/";

    private static final String LIST_PATH = "v3/memories/";

    private static final String CLEAR_PATH = "v1/memories/";

    private static final String EVENT_PATH = "v1/event/";

    private final Mem0ApiKey apiKey;

    private final Mem0ClientOptions options;

    private final Mem0Json json;

    private final HttpClient httpClient;

    private final ExecutorService executor;

    private final ExecutorService ownedExecutor;

    private final ScheduledExecutorService scheduler;

    private final ScheduledExecutorService ownedScheduler;

    private final java.util.concurrent.Semaphore concurrentRequests;

    private final Set<HttpOperation> activeRequests = ConcurrentHashMap.newKeySet();

    private final Set<EventPoll> activePolls = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean closed = new AtomicBoolean();

    Mem0RestClient(Mem0ApiKey apiKey, Mem0ClientOptions options) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.options = Objects.requireNonNull(options, "options");
        json = new Mem0Json(options.limitOptions());
        if (options.executor() == null) {
            ownedExecutor = Executors.newVirtualThreadPerTaskExecutor();
            executor = ownedExecutor;
        } else {
            ownedExecutor = null;
            executor = options.executor();
        }
        if (options.scheduler() == null) {
            ScheduledThreadPoolExecutor created = new ScheduledThreadPoolExecutor(
                    1,
                    Thread.ofPlatform()
                            .daemon(true)
                            .name("agent-framework-mem0-", 0)
                            .factory());
            created.setRemoveOnCancelPolicy(true);
            ownedScheduler = created;
            scheduler = created;
        } else {
            ownedScheduler = null;
            scheduler = options.scheduler();
        }
        httpClient = HttpClient.newBuilder()
                .connectTimeout(options.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_2)
                .executor(executor)
                .build();
        concurrentRequests =
                new java.util.concurrent.Semaphore(options.limitOptions().maxConcurrentRequests());
    }

    CompletionStage<List<Mem0Memory>> searchAsync(Mem0Scope scope, String query, RunCancellation cancellation) {
        Objects.requireNonNull(scope, "scope");
        String safeQuery = requireText(query, "query", options.limitOptions().maxQueryCharacters());
        Deadline deadline = Deadline.start(options.operationTimeout());
        List<Mem0Scope> partitions = scope.partitions();
        if (partitions.size() == 1) {
            return searchPartitionAsync(partitions.getFirst(), safeQuery, cancellation, deadline);
        }
        return searchPartitionAsync(partitions.get(0), safeQuery, cancellation, deadline)
                .thenCompose(first -> searchPartitionAsync(partitions.get(1), safeQuery, cancellation, deadline)
                        .thenApply(second -> mergeSearchResults(List.of(first, second))));
    }

    private CompletionStage<List<Mem0Memory>> searchPartitionAsync(
            Mem0Scope scope, String query, RunCancellation cancellation, Deadline deadline) {
        LinkedHashMap<String, StateValue> body = new LinkedHashMap<>();
        body.put("query", StateValue.string(query));
        body.put("filters", scope.filters());
        body.put("top_k", StateValue.integer(options.limitOptions().topK()));
        return sendJsonAsync(
                        "search",
                        "POST",
                        uri(SEARCH_PATH),
                        json.writeRequest(StateValue.object(body)),
                        cancellation,
                        deadline,
                        true,
                        false)
                .thenApply(payload -> parseSearch(payload.body()));
    }

    CompletionStage<List<Mem0Memory>> listAsync(Mem0Scope scope, int page, int pageSize, RunCancellation cancellation) {
        Objects.requireNonNull(scope, "scope");
        if (scope.partitions().size() != 1) {
            throw new ValidationException(
                    "Mem0 list requires a single user or agent partition when both identities are configured.");
        }
        if (page < 1) {
            throw new ValidationException("page must be greater than zero.");
        }
        if (pageSize < 1 || pageSize > 200) {
            throw new ValidationException("pageSize must be between 1 and 200.");
        }
        Deadline deadline = Deadline.start(options.operationTimeout());
        StateValue body = StateValue.object(Map.of("filters", scope.filters()));
        URI target = uri(LIST_PATH + "?page=" + page + "&page_size=" + pageSize);
        return sendJsonAsync("list", "POST", target, json.writeRequest(body), cancellation, deadline, false, false)
                .thenApply(payload -> parseList(payload.body()));
    }

    CompletionStage<Void> clearAsync(Mem0Scope scope, RunCancellation cancellation) {
        Objects.requireNonNull(scope, "scope");
        Deadline deadline = Deadline.start(options.operationTimeout());
        List<Mem0Scope> partitions = scope.partitions();
        if (partitions.size() == 1) {
            return clearPartitionAsync(partitions.getFirst(), cancellation, deadline);
        }
        return clearPartitionAsync(partitions.get(0), cancellation, deadline)
                .thenCompose(ignored -> clearPartitionAsync(partitions.get(1), cancellation, deadline));
    }

    private CompletionStage<Void> clearPartitionAsync(
            Mem0Scope scope, RunCancellation cancellation, Deadline deadline) {
        StringBuilder query = new StringBuilder();
        appendQuery(query, "app_id", scope.appId());
        appendQuery(query, "user_id", scope.userId());
        appendQuery(query, "agent_id", scope.agentId());
        appendQuery(query, "run_id", scope.runId());
        if (query.isEmpty()) {
            throw new ValidationException("Mem0 clear requires at least one explicit identity.");
        }
        URI target = uri(CLEAR_PATH + "?" + query);
        return sendJsonAsync("clear", "DELETE", target, null, cancellation, deadline, true, true)
                .thenCompose(payload -> {
                    if (payload.body().length == 0) {
                        return CompletableFuture.completedStage(null);
                    }
                    StateValue.ObjectValue response =
                            Mem0Json.object(json.parseResponse(payload.body(), "clear"), "clear", "response");
                    String eventId = Mem0Json.optionalString(response, "event_id", "clear");
                    if (eventId == null) {
                        return CompletableFuture.completedStage(null);
                    }
                    return pollEvent(eventId, "clear", cancellation, deadline);
                });
    }

    CompletionStage<Void> addAsync(Mem0Scope scope, List<Mem0StoredMessage> messages, RunCancellation cancellation) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            return CompletableFuture.completedStage(null);
        }
        if (messages.size() > options.limitOptions().maxStoredMessages()) {
            throw new ValidationException("Mem0 add exceeds maxStoredMessages.");
        }
        ArrayList<StateValue> encodedMessages = new ArrayList<>(messages.size());
        for (Mem0StoredMessage message : messages) {
            String role = requireRole(message.role());
            String content = requireText(
                    message.content(), "message content", options.limitOptions().maxMessageCharacters());
            encodedMessages.add(
                    StateValue.object(Map.of("role", StateValue.string(role), "content", StateValue.string(content))));
        }
        LinkedHashMap<String, StateValue> body = new LinkedHashMap<>();
        body.put("messages", StateValue.array(encodedMessages));
        putIdentity(body, "app_id", scope.appId());
        putIdentity(body, "user_id", scope.userId());
        putIdentity(body, "agent_id", scope.agentId());
        putIdentity(body, "run_id", scope.runId());
        Deadline deadline = Deadline.start(options.operationTimeout());
        return sendJsonAsync(
                        "add",
                        "POST",
                        uri(ADD_PATH),
                        json.writeRequest(StateValue.object(body)),
                        cancellation,
                        deadline,
                        false,
                        false)
                .thenCompose(payload -> handleAddResponse(payload.body(), cancellation, deadline));
    }

    private CompletionStage<Void> handleAddResponse(byte[] bytes, RunCancellation cancellation, Deadline deadline) {
        StateValue.ObjectValue response = Mem0Json.object(json.parseResponse(bytes, "add"), "add", "response");
        String status = Mem0Json.optionalString(response, "status", "add");
        String eventId = Mem0Json.optionalString(response, "event_id", "add");
        FailureSummary summary = resultSummary(response, "add");
        if (summary.failures() > 0) {
            return CompletableFuture.failedStage(partial("add"));
        }
        if ("FAILED".equals(status)) {
            return CompletableFuture.failedStage(service("add", null, null, null));
        }
        if (status != null && !"PENDING".equals(status) && !"RUNNING".equals(status) && !"SUCCEEDED".equals(status)) {
            return CompletableFuture.failedStage(Mem0Json.failure("add"));
        }
        if (eventId != null && !"SUCCEEDED".equals(status)) {
            return pollEvent(eventId, "add", cancellation, deadline);
        }
        if ("SUCCEEDED".equals(status) || summary.entries() > 0) {
            return CompletableFuture.completedStage(null);
        }
        if (eventId != null) {
            return pollEvent(eventId, "add", cancellation, deadline);
        }
        return CompletableFuture.failedStage(Mem0Json.failure("add"));
    }

    private CompletionStage<Void> pollEvent(
            String eventId, String originOperation, RunCancellation cancellation, Deadline deadline) {
        String safeEventId = boundedNonBlank(eventId, options.limitOptions().maxMemoryIdCharacters(), "event");
        EventPoll poll = new EventPoll(safeEventId, originOperation, cancellation, deadline);
        if (closed.get()) {
            return CompletableFuture.failedStage(closed(originOperation));
        }
        activePolls.add(poll);
        poll.result.whenComplete((ignored, failure) -> {
            activePolls.remove(poll);
            poll.close();
        });
        poll.poll();
        return poll.result.minimalCompletionStage();
    }

    private CompletionStage<HttpPayload> sendJsonAsync(
            String operation,
            String method,
            URI target,
            byte[] body,
            RunCancellation cancellation,
            Deadline deadline,
            boolean retryable,
            boolean allowEmptySuccess) {
        Objects.requireNonNull(cancellation, "cancellation");
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedStage(new RunCancelledException());
        }
        if (closed.get()) {
            return CompletableFuture.failedStage(closed(operation));
        }
        HttpOperation request = new HttpOperation(
                operation, method, target, body, cancellation, deadline, retryable, allowEmptySuccess);
        activeRequests.add(request);
        request.result.whenComplete((ignored, failure) -> {
            activeRequests.remove(request);
            request.close();
        });
        request.sendAttempt(0);
        return request.result.minimalCompletionStage();
    }

    private List<Mem0Memory> parseSearch(byte[] bytes) {
        StateValue root = json.parseResponse(bytes, "search");
        StateValue.ArrayValue results;
        if (root instanceof StateValue.ArrayValue array) {
            results = array;
        } else if (root instanceof StateValue.ObjectValue object) {
            results = Mem0Json.requiredArray(object, "results", "search");
        } else {
            throw Mem0Json.failure("search");
        }
        ArrayList<Mem0Memory> memories = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < results.values().size(); index++) {
            StateValue.ObjectValue item = Mem0Json.object(results.values().get(index), "search", "result");
            Mem0Memory memory = parseMemory(item, "search", index + 1);
            if (seen.add(memory.id())
                    && memories.size() < options.limitOptions().topK()) {
                memories.add(memory);
            }
        }
        return List.copyOf(memories);
    }

    private List<Mem0Memory> mergeSearchResults(List<List<Mem0Memory>> searches) {
        ArrayList<Mem0Memory> merged = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (List<Mem0Memory> search : searches) {
            for (Mem0Memory memory : search) {
                if (!seen.add(memory.id())) {
                    continue;
                }
                merged.add(withRank(memory, merged.size() + 1));
                if (merged.size() == options.limitOptions().topK()) {
                    return List.copyOf(merged);
                }
            }
        }
        return List.copyOf(merged);
    }

    private static Mem0Memory withRank(Mem0Memory memory, int rank) {
        return new Mem0Memory(
                memory.id(),
                memory.memory(),
                memory.score(),
                rank,
                memory.appId(),
                memory.userId(),
                memory.agentId(),
                memory.runId(),
                memory.metadata(),
                memory.categories(),
                memory.createdAt(),
                memory.updatedAt());
    }

    private List<Mem0Memory> parseList(byte[] bytes) {
        StateValue.ObjectValue response = Mem0Json.object(json.parseResponse(bytes, "list"), "list", "response");
        StateValue.ArrayValue results = Mem0Json.requiredArray(response, "results", "list");
        ArrayList<Mem0Memory> memories = new ArrayList<>(results.values().size());
        for (StateValue value : results.values()) {
            memories.add(parseMemory(Mem0Json.object(value, "list", "result"), "list", 0));
        }
        return List.copyOf(memories);
    }

    private Mem0Memory parseMemory(StateValue.ObjectValue item, String operation, int rank) {
        try {
            String id = bounded(
                    Mem0Json.requiredString(item, "id", operation),
                    options.limitOptions().maxMemoryIdCharacters(),
                    operation);
            String memory = Mem0Json.requiredString(item, "memory", operation);
            Double score = Mem0Json.optionalDouble(item, "score", operation);
            if (score != null && (!Double.isFinite(score) || score < 0.0 || score > 1.0)) {
                throw Mem0Json.failure(operation);
            }
            StateValue.ObjectValue metadata = Mem0Json.optionalObject(item, "metadata", operation);
            StateValue.ArrayValue categories = Mem0Json.optionalArray(item, "categories", operation);
            String runId = optionalIdentity(item, "run_id", operation);
            String sessionId = optionalIdentity(item, "session_id", operation);
            if (runId != null && sessionId != null && !runId.equals(sessionId)) {
                throw Mem0Json.failure(operation);
            }
            return new Mem0Memory(
                    id,
                    memory,
                    score,
                    rank,
                    optionalIdentity(item, "app_id", operation),
                    optionalIdentity(item, "user_id", operation),
                    optionalIdentity(item, "agent_id", operation),
                    runId == null ? sessionId : runId,
                    metadata == null ? Map.of() : metadata.values(),
                    categories(categories, operation),
                    optionalInstant(item, "created_at", operation),
                    optionalInstant(item, "updated_at", operation));
        } catch (Mem0StorageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw Mem0Json.failure(operation);
        }
    }

    private String optionalIdentity(StateValue.ObjectValue item, String member, String operation) {
        String value = Mem0Json.optionalString(item, member, operation);
        if (value == null) {
            return null;
        }
        return boundedNonBlank(value, options.limitOptions().maxStringLength(), operation);
    }

    private List<String> categories(StateValue.ArrayValue values, String operation) {
        if (values == null) {
            return List.of();
        }
        ArrayList<String> categories = new ArrayList<>(values.values().size());
        for (StateValue value : values.values()) {
            if (!(value instanceof StateValue.StringValue string)
                    || string.value().isBlank()) {
                throw Mem0Json.failure(operation);
            }
            categories.add(string.value());
        }
        return List.copyOf(categories);
    }

    private static Instant optionalInstant(StateValue.ObjectValue item, String member, String operation) {
        String value = Mem0Json.optionalString(item, member, operation);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw Mem0Json.failure(operation);
        }
    }

    private void validateOptionalJson(HttpPayload payload, String operation) {
        if (payload.body().length > 0) {
            json.parseResponse(payload.body(), operation);
        }
    }

    private FailureSummary resultSummary(StateValue.ObjectValue event, String operation) {
        StateValue.ArrayValue results = Mem0Json.optionalArray(event, "results", operation);
        if (results == null) {
            return new FailureSummary(0, 0, 0);
        }
        int successes = 0;
        int failures = 0;
        for (StateValue value : results.values()) {
            if (!(value instanceof StateValue.ObjectValue result)) {
                continue;
            }
            String status = Mem0Json.optionalString(result, "status", operation);
            StateValue error = result.values().get("error");
            boolean hasError = error != null
                    && !(error instanceof StateValue.NullValue)
                    && (!(error instanceof StateValue.StringValue text)
                            || !text.value().isBlank());
            if ("FAILED".equals(status) || hasError) {
                failures++;
            } else if ("SUCCEEDED".equals(status)) {
                successes++;
            }
        }
        return new FailureSummary(results.values().size(), successes, failures);
    }

    private URI uri(String path) {
        return options.endpoint().resolve(path);
    }

    private HttpRequest request(String operation, String method, URI target, byte[] body, Deadline deadline) {
        Duration timeout = deadline.requestTimeout(options.requestTimeout(), operation);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(timeout)
                .header("Authorization", "Token " + apiKey.value())
                .header("Accept", "application/json")
                .header("User-Agent", "agent-framework-java/mem0");
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        }
        return builder.build();
    }

    private HttpPayload successfulPayload(String operation, HttpResponse<InputStream> response) {
        long contentLength =
                response.headers().firstValueAsLong("content-length").orElse(-1);
        if (contentLength > options.limitOptions().maxResponseBytes()) {
            throw Mem0Json.failure(operation);
        }
        byte[] body = readBounded(response.body(), operation);
        return new HttpPayload(body, requestId(response));
    }

    private byte[] readBounded(InputStream input, String operation) {
        try {
            byte[] bytes = input.readNBytes(options.limitOptions().maxResponseBytes() + 1);
            if (bytes.length > options.limitOptions().maxResponseBytes()) {
                throw Mem0Json.failure(operation);
            }
            return bytes;
        } catch (IOException exception) {
            throw transport(operation);
        }
    }

    private void validateSuccess(String operation, HttpResponse<?> response, byte[] body, boolean allowEmptySuccess) {
        if (body.length == 0) {
            if (allowEmptySuccess) {
                return;
            }
            throw Mem0Json.failure(operation);
        }
        String contentType =
                response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
        if (!contentType.startsWith("application/json")) {
            throw Mem0Json.failure(operation);
        }
    }

    private Duration retryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("retry-after").orElse(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        Duration parsed;
        try {
            long seconds = Long.parseLong(value.trim());
            if (seconds < 0) {
                return null;
            }
            parsed = Duration.ofSeconds(seconds);
        } catch (NumberFormatException exception) {
            try {
                Instant target = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
                parsed = Duration.between(Instant.now(), target);
                if (parsed.isNegative()) {
                    parsed = Duration.ZERO;
                }
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        return parsed.compareTo(options.retryOptions().maxRetryAfter()) > 0
                ? options.retryOptions().maxRetryAfter()
                : parsed;
    }

    private long retryDelayMillis(int attempt, Duration retryAfter) {
        if (retryAfter != null) {
            return Math.max(1, retryAfter.toMillis());
        }
        long initial = options.retryOptions().initialDelay().toMillis();
        long maximum = options.retryOptions().maxDelay().toMillis();
        return Math.max(1, Math.min(maximum, initial << Math.min(attempt, 20)));
    }

    private static boolean retryableStatus(int status) {
        return status == 408 || status == 429 || status >= 500 && status < 600;
    }

    private static String requestId(HttpResponse<?> response) {
        String value = response.headers()
                .firstValue("x-request-id")
                .or(() -> response.headers().firstValue("request-id"))
                .orElse(null);
        if (value == null) {
            return null;
        }
        StringBuilder safe = new StringBuilder(Math.min(value.length(), 128));
        for (int index = 0; index < value.length() && safe.length() < 128; index++) {
            char character = value.charAt(index);
            if (!Character.isISOControl(character)) {
                safe.append(character);
            }
        }
        return safe.isEmpty() ? null : safe.toString();
    }

    private static void putIdentity(Map<String, StateValue> body, String name, String value) {
        if (value != null) {
            body.put(name, StateValue.string(value));
        }
    }

    private static void appendQuery(StringBuilder query, String name, String value) {
        if (value == null) {
            return;
        }
        if (!query.isEmpty()) {
            query.append('&');
        }
        query.append(name).append('=').append(encode(value));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String requireRole(String role) {
        return switch (Objects.requireNonNull(role, "role")) {
            case "user", "assistant", "system" -> role;
            default -> throw new ValidationException("Mem0 add role must be user, assistant, or system.");
        };
    }

    private static String requireText(String value, String name, int maximum) {
        Objects.requireNonNull(value, name);
        String checked = value.trim();
        if (checked.isEmpty()) {
            throw new ValidationException(name + " must not be blank.");
        }
        if (checked.length() > maximum) {
            throw new ValidationException(name + " exceeds the configured character limit.");
        }
        return value;
    }

    private static String bounded(String value, int maximum, String operation) {
        if (value.length() > maximum) {
            throw Mem0Json.failure(operation);
        }
        return value;
    }

    private static String boundedNonBlank(String value, int maximum, String operation) {
        if (value.isBlank() || value.length() > maximum) {
            throw Mem0Json.failure(operation);
        }
        return value;
    }

    private Mem0StorageException service(String operation, Integer status, String requestId, Duration retryAfter) {
        return new Mem0StorageException(Mem0StorageException.Kind.SERVICE, operation, status, requestId, retryAfter);
    }

    private Mem0StorageException authentication(String operation, int status, String requestId) {
        return new Mem0StorageException(Mem0StorageException.Kind.AUTHENTICATION, operation, status, requestId, null);
    }

    private Mem0StorageException partial(String operation) {
        return new Mem0StorageException(Mem0StorageException.Kind.PARTIAL_FAILURE, operation, null, null, null);
    }

    private Mem0StorageException transport(String operation) {
        return new Mem0StorageException(Mem0StorageException.Kind.TRANSPORT, operation, null, null, null);
    }

    private Mem0StorageException timeout(String operation) {
        return new Mem0StorageException(Mem0StorageException.Kind.TIMEOUT, operation, null, null, null);
    }

    private Mem0StorageException closed(String operation) {
        return new Mem0StorageException(Mem0StorageException.Kind.CLOSED, operation, null, null, null);
    }

    private Mem0StorageException concurrency(String operation) {
        return new Mem0StorageException(Mem0StorageException.Kind.CONCURRENCY_LIMIT, operation, null, null, null);
    }

    private RuntimeException normalizeTransport(
            String operation, Throwable failure, RunCancellation cancellation, Deadline deadline) {
        if (cancellation.isCancellationRequested()) {
            return new RunCancelledException();
        }
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof RunCancelledException cancelled) {
            return cancelled;
        }
        if (current instanceof Mem0StorageException storage) {
            return storage;
        }
        if (deadline.expired() || current instanceof HttpTimeoutException) {
            return timeout(operation);
        }
        return transport(operation);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        activePolls.forEach(EventPoll::failClosed);
        activeRequests.forEach(HttpOperation::failClosed);
        activePolls.clear();
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
        }
        if (ownedScheduler != null) {
            ownedScheduler.shutdownNow();
        }
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
        }
    }

    private final class HttpOperation implements AutoCloseable {
        private final String operation;

        private final String method;

        private final URI target;

        private final byte[] body;

        private final RunCancellation cancellation;

        private final Deadline deadline;

        private final boolean retryable;

        private final boolean allowEmptySuccess;

        private final CompletableFuture<HttpPayload> result = new CompletableFuture<>();

        private final AtomicBoolean finished = new AtomicBoolean();

        private final Object lifecycleLock = new Object();

        private final AtomicReference<CompletableFuture<HttpResponse<InputStream>>> upstream = new AtomicReference<>();

        private final AtomicReference<InputStream> responseBody = new AtomicReference<>();

        private final AtomicReference<ScheduledFuture<?>> retryTask = new AtomicReference<>();

        private final ScheduledFuture<?> deadlineTask;

        private final RunCancellationRegistration registration;

        private HttpOperation(
                String operation,
                String method,
                URI target,
                byte[] body,
                RunCancellation cancellation,
                Deadline deadline,
                boolean retryable,
                boolean allowEmptySuccess) {
            this.operation = operation;
            this.method = method;
            this.target = target;
            this.body = body;
            this.cancellation = cancellation;
            this.deadline = deadline;
            this.retryable = retryable;
            this.allowEmptySuccess = allowEmptySuccess;
            deadlineTask =
                    scheduler.schedule(this::timeout, Math.max(1, deadline.remainingNanos()), TimeUnit.NANOSECONDS);
            registration = RunCancellations.register(cancellation, this::cancel);
        }

        private void sendAttempt(int attempt) {
            if (finished.get()) {
                return;
            }
            if (closed.get()) {
                failClosed();
                return;
            }
            if (cancellation.isCancellationRequested()) {
                cancel();
                return;
            }
            if (deadline.expired()) {
                timeout();
                return;
            }
            if (!concurrentRequests.tryAcquire()) {
                fail(concurrency(operation));
                return;
            }
            CompletableFuture<HttpResponse<InputStream>> pending = null;
            RuntimeException startupFailure = null;
            try {
                synchronized (lifecycleLock) {
                    if (finished.get()) {
                        // Cancellation, timeout, or close won before request initiation.
                    } else if (closed.get()) {
                        startupFailure = closed(operation);
                    } else if (cancellation.isCancellationRequested()) {
                        startupFailure = new RunCancelledException();
                    } else if (finished.get()) {
                        // A re-entrant cancellation listener completed while checking the signal.
                    } else if (deadline.expired()) {
                        startupFailure = Mem0RestClient.this.timeout(operation);
                    } else {
                        pending = httpClient.sendAsync(
                                request(operation, method, target, body, deadline),
                                HttpResponse.BodyHandlers.ofInputStream());
                        upstream.set(pending);
                    }
                }
            } catch (RuntimeException failure) {
                startupFailure = normalizeTransport(operation, failure, cancellation, deadline);
            }
            if (pending == null) {
                concurrentRequests.release();
                if (startupFailure != null) {
                    fail(startupFailure);
                }
                return;
            }
            CompletableFuture<HttpResponse<InputStream>> started = pending;
            AtomicBoolean permitReleased = new AtomicBoolean();
            CompletableFuture<Void> dispatched;
            try {
                dispatched = started.handleAsync(
                        (response, transportFailure) -> {
                            handleAttempt(started, response, transportFailure, attempt, permitReleased);
                            return null;
                        },
                        executor);
            } catch (RuntimeException failure) {
                handleDispatchFailure(started, permitReleased, failure);
                return;
            }
            dispatched.whenComplete((ignored, dispatchFailure) -> {
                if (dispatchFailure != null) {
                    handleDispatchFailure(started, permitReleased, dispatchFailure);
                }
            });
        }

        private void handleAttempt(
                CompletableFuture<HttpResponse<InputStream>> pending,
                HttpResponse<InputStream> response,
                Throwable transportFailure,
                int attempt,
                AtomicBoolean permitReleased) {
            upstream.compareAndSet(pending, null);
            RetryPlan retry = null;
            HttpPayload success = null;
            Throwable completionFailure = null;
            try {
                if (response != null) {
                    responseBody.set(response.body());
                }
                if (!finished.get()) {
                    if (transportFailure != null) {
                        completionFailure = normalizeTransport(operation, transportFailure, cancellation, deadline);
                    } else if (response == null) {
                        completionFailure = transport(operation);
                    } else {
                        int status = response.statusCode();
                        Duration retryAfter = retryAfter(response);
                        if (retryable
                                && retryableStatus(status)
                                && attempt < options.retryOptions().maxRetries()) {
                            retry = new RetryPlan(attempt + 1, retryDelayMillis(attempt, retryAfter));
                        } else if (status < 200 || status >= 300) {
                            String id = requestId(response);
                            completionFailure = status == 401 || status == 403
                                    ? authentication(operation, status, id)
                                    : service(operation, status, id, retryAfter);
                        } else {
                            HttpPayload payload = successfulPayload(operation, response);
                            validateSuccess(operation, response, payload.body(), allowEmptySuccess);
                            success = payload;
                        }
                    }
                }
            } catch (RuntimeException failure) {
                completionFailure = failure;
            } finally {
                closeQuietly(responseBody.getAndSet(null));
                releasePermit(permitReleased);
            }
            if (completionFailure != null) {
                fail(completionFailure);
            } else if (success != null) {
                succeed(success);
            } else if (retry != null && !finished.get()) {
                scheduleRetry(retry);
            }
        }

        private void handleDispatchFailure(
                CompletableFuture<HttpResponse<InputStream>> pending,
                AtomicBoolean permitReleased,
                Throwable dispatchFailure) {
            if (!releasePermit(permitReleased)) {
                return;
            }
            upstream.compareAndSet(pending, null);
            try {
                HttpResponse<InputStream> response = pending.getNow(null);
                if (response != null) {
                    closeQuietly(response.body());
                }
            } catch (RuntimeException ignored) {
                // An exceptional HTTP future has no response body to close.
            }
            fail(normalizeTransport(operation, dispatchFailure, cancellation, deadline));
        }

        private boolean releasePermit(AtomicBoolean permitReleased) {
            if (!permitReleased.compareAndSet(false, true)) {
                return false;
            }
            concurrentRequests.release();
            return true;
        }

        private void scheduleRetry(RetryPlan retry) {
            if (!deadline.canDelay(retry.delayMillis())) {
                timeout();
                return;
            }
            try {
                AtomicReference<ScheduledFuture<?>> holder = new AtomicReference<>();
                ScheduledFuture<?> scheduled = scheduler.schedule(
                        () -> {
                            retryTask.compareAndSet(holder.get(), null);
                            sendAttempt(retry.attempt());
                        },
                        retry.delayMillis(),
                        TimeUnit.MILLISECONDS);
                holder.set(scheduled);
                ScheduledFuture<?> prior = retryTask.getAndSet(scheduled);
                if (prior != null) {
                    prior.cancel(false);
                }
                if (finished.get() && retryTask.compareAndSet(scheduled, null)) {
                    scheduled.cancel(false);
                }
            } catch (RuntimeException failure) {
                fail(transport(operation));
            }
        }

        private void succeed(HttpPayload payload) {
            if (markFinished()) {
                result.complete(payload);
                cancelPending();
            }
        }

        private void fail(Throwable failure) {
            if (markFinished()) {
                result.completeExceptionally(failure);
                cancelPending();
            }
        }

        private void cancel() {
            if (markFinished()) {
                result.completeExceptionally(new RunCancelledException());
                cancelPending();
            }
        }

        private void timeout() {
            if (markFinished()) {
                result.completeExceptionally(Mem0RestClient.this.timeout(operation));
                cancelPending();
            }
        }

        private boolean markFinished() {
            synchronized (lifecycleLock) {
                return finished.compareAndSet(false, true);
            }
        }

        private void failClosed() {
            fail(closed(operation));
        }

        private void cancelPending() {
            ScheduledFuture<?> retry = retryTask.getAndSet(null);
            if (retry != null) {
                retry.cancel(false);
            }
            CompletableFuture<?> request = upstream.getAndSet(null);
            if (request != null) {
                request.cancel(true);
            }
            closeQuietly(responseBody.getAndSet(null));
        }

        @Override
        public void close() {
            registration.close();
            deadlineTask.cancel(false);
            cancelPending();
        }
    }

    private final class EventPoll implements AutoCloseable {
        private final String eventId;

        private final String originOperation;

        private final RunCancellation cancellation;

        private final Deadline deadline;

        private final CompletableFuture<Void> result = new CompletableFuture<>();

        private final AtomicBoolean finished = new AtomicBoolean();

        private final AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();

        private final ScheduledFuture<?> deadlineTask;

        private final RunCancellationRegistration registration;

        private int attempt;

        private EventPoll(String eventId, String originOperation, RunCancellation cancellation, Deadline deadline) {
            this.eventId = eventId;
            this.originOperation = originOperation;
            this.cancellation = cancellation;
            this.deadline = deadline;
            deadlineTask =
                    scheduler.schedule(this::timeout, Math.max(1, deadline.remainingNanos()), TimeUnit.NANOSECONDS);
            registration = RunCancellations.register(cancellation, this::cancel);
        }

        private void poll() {
            if (finished.get()) {
                return;
            }
            if (closed.get()) {
                failClosed();
                return;
            }
            sendJsonAsync(
                            "event",
                            "GET",
                            uri(EVENT_PATH + encode(eventId) + "/"),
                            null,
                            cancellation,
                            deadline,
                            true,
                            false)
                    .whenComplete((payload, failure) -> {
                        if (failure != null) {
                            fail(unwrap(failure));
                            return;
                        }
                        try {
                            StateValue.ObjectValue event =
                                    Mem0Json.object(json.parseResponse(payload.body(), "event"), "event", "response");
                            String status = Mem0Json.requiredString(event, "status", "event");
                            FailureSummary summary = resultSummary(event, "event");
                            if ("SUCCEEDED".equals(status)) {
                                if (summary.failures() > 0) {
                                    fail(partial(originOperation));
                                } else {
                                    succeed();
                                }
                            } else if ("FAILED".equals(status)) {
                                fail(
                                        summary.successes() > 0 && summary.failures() > 0
                                                ? partial(originOperation)
                                                : service(originOperation, null, null, null));
                            } else if ("PENDING".equals(status) || "RUNNING".equals(status)) {
                                scheduleNext();
                            } else {
                                fail(Mem0Json.failure("event"));
                            }
                        } catch (RuntimeException exception) {
                            fail(exception);
                        }
                    });
        }

        private void scheduleNext() {
            long initial = options.initialEventPollDelay().toMillis();
            long maximum = options.maxEventPollDelay().toMillis();
            long delay = Math.max(1, Math.min(maximum, initial << Math.min(attempt++, 20)));
            if (!deadline.canDelay(delay)) {
                timeout();
                return;
            }
            try {
                ScheduledFuture<?> next = scheduler.schedule(this::poll, delay, TimeUnit.MILLISECONDS);
                ScheduledFuture<?> prior = scheduled.getAndSet(next);
                if (prior != null && !prior.isDone()) {
                    prior.cancel(false);
                }
            } catch (RuntimeException failure) {
                fail(transport(originOperation));
            }
        }

        private void succeed() {
            if (finished.compareAndSet(false, true)) {
                result.complete(null);
            }
        }

        private void fail(Throwable failure) {
            if (finished.compareAndSet(false, true)) {
                result.completeExceptionally(failure);
            }
        }

        private void cancel() {
            if (finished.compareAndSet(false, true)) {
                result.completeExceptionally(new RunCancelledException());
            }
        }

        private void timeout() {
            fail(Mem0RestClient.this.timeout(originOperation));
        }

        private void failClosed() {
            fail(closed(originOperation));
        }

        @Override
        public void close() {
            registration.close();
            deadlineTask.cancel(false);
            ScheduledFuture<?> next = scheduled.getAndSet(null);
            if (next != null) {
                next.cancel(false);
            }
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // Best effort during cancellation and cleanup.
        }
    }

    private record HttpPayload(byte[] body, String requestId) {}

    private record RetryPlan(int attempt, long delayMillis) {}

    private record FailureSummary(int entries, int successes, int failures) {}

    private static final class Deadline {
        private final long endNanos;

        private Deadline(long endNanos) {
            this.endNanos = endNanos;
        }

        private static Deadline start(Duration timeout) {
            long now = System.nanoTime();
            long duration;
            try {
                duration = timeout.toNanos();
            } catch (ArithmeticException exception) {
                duration = Long.MAX_VALUE;
            }
            long end = duration >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + duration;
            return new Deadline(end);
        }

        private boolean expired() {
            return remainingNanos() <= 0;
        }

        private long remainingNanos() {
            return endNanos - System.nanoTime();
        }

        private boolean canDelay(long milliseconds) {
            if (milliseconds < 0) {
                return false;
            }
            long nanos;
            try {
                nanos = Math.multiplyExact(milliseconds, 1_000_000L);
            } catch (ArithmeticException exception) {
                return false;
            }
            return remainingNanos() > nanos;
        }

        private Duration requestTimeout(Duration configured, String operation) {
            long remaining = remainingNanos();
            if (remaining <= 0) {
                throw new Mem0StorageException(Mem0StorageException.Kind.TIMEOUT, operation, null, null, null);
            }
            Duration available = Duration.ofNanos(Math.max(1, remaining));
            return configured.compareTo(available) <= 0 ? configured : available;
        }
    }
}
