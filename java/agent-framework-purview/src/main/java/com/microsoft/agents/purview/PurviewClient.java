// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

import com.microsoft.agents.azure.AzureTokenRequest;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.internal.StrictJsonCodec;
import com.microsoft.agents.core.internal.http.BoundedBodyHandlers;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Calls Microsoft Graph Purview dataSecurityAndGovernance policy APIs.
 *
 * <p>The client sends content to Purview for policy enforcement; it does not extract Purview data
 * or analytics. Redirects are disabled and errors never retain response bodies, content, identities,
 * or tokens.
 */
public final class PurviewClient implements AutoCloseable {
    private final PurviewSettings settings;
    private final java.net.http.HttpClient httpClient;
    private final ExecutorService ownedExecutor;
    private final ScheduledExecutorService scheduler;
    private final ScheduledExecutorService ownedScheduler;
    private final StrictJsonCodec json;
    private final Set<HttpOperation> requests = ConcurrentHashMap.newKeySet();
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Creates a Purview client from immutable settings. */
    public PurviewClient(PurviewSettings settings) {
        this(settings, null);
    }

    PurviewClient(PurviewSettings settings, java.net.http.HttpClient injectedHttp) {
        this.settings = Objects.requireNonNull(settings, "settings");
        Executor executor = settings.backgroundExecutor();
        if (executor == null) {
            ownedExecutor = Executors.newVirtualThreadPerTaskExecutor();
            executor = ownedExecutor;
        } else {
            ownedExecutor = null;
        }
        httpClient = injectedHttp == null
                ? java.net.http.HttpClient.newBuilder()
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                        .connectTimeout(settings.requestTimeout())
                        .executor(executor)
                        .build()
                : injectedHttp;
        if (settings.scheduler() == null) {
            ScheduledThreadPoolExecutor created = new ScheduledThreadPoolExecutor(
                    1,
                    Thread.ofPlatform()
                            .daemon(true)
                            .name("agent-framework-purview-retry-", 0)
                            .factory());
            created.setRemoveOnCancelPolicy(true);
            scheduler = created;
            ownedScheduler = created;
        } else {
            scheduler = settings.scheduler();
            ownedScheduler = null;
        }
        int maximumJson = Math.max(settings.maxRequestBytes(), settings.maxResponseBytes());
        json = new StrictJsonCodec(maximumJson, maximumJson, 64, Math.min(maximumJson, 1_048_576), 256, 100_000);
    }

    /** Returns immutable client settings. */
    public PurviewSettings settings() {
        return settings;
    }

    /** Computes applicable protection scopes for one user activity. */
    public java.util.concurrent.CompletionStage<PurviewProtectionScopes> computeProtectionScopesAsync(
            PurviewContentRequest request, RunCancellation cancellation) {
        ensureOpen();
        LinkedHashMap<String, StateValue> body = new LinkedHashMap<>();
        body.put("activities", StateValue.string(request.activity().graphValue()));
        body.put("locations", StateValue.array(List.of(location(request.location()))));
        body.put("deviceMetadata", deviceMetadata());
        body.put("integratedAppMetadata", appMetadata(request.appName(), request.appVersion()));
        URI uri = userUri(request.userId(), "/dataSecurityAndGovernance/protectionScopes/compute");
        return sendAsync(
                        "protectionScopes.compute",
                        "POST",
                        uri,
                        StateValue.object(body),
                        request.tenantId(),
                        Map.of("client-request-id", request.correlationId()),
                        cancellation)
                .thenApply(payload -> protectionScopes(payload));
    }

    /** Processes content inline or offline according to a computed protection scope. */
    public java.util.concurrent.CompletionStage<PurviewDecision> processContentAsync(
            PurviewContentRequest request,
            PurviewProtectionScopes scopes,
            boolean evaluateInline,
            RunCancellation cancellation) {
        ensureOpen();
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("client-request-id", request.correlationId());
        if (scopes != null && scopes.etag() != null) {
            headers.put("If-None-Match", scopes.etag());
        }
        if (evaluateInline) {
            headers.put("Prefer", "evaluateInline");
        }
        URI uri = userUri(request.userId(), "/dataSecurityAndGovernance/processContent");
        return sendAsync(
                        "processContent",
                        "POST",
                        uri,
                        StateValue.object(Map.of("contentToProcess", contentToProcess(request))),
                        request.tenantId(),
                        headers,
                        cancellation)
                .thenApply(PurviewClient::decision);
    }

    /** Records a content activity when no protection scope requires processContent. */
    public java.util.concurrent.CompletionStage<PurviewDecision> recordContentActivityAsync(
            PurviewContentRequest request, RunCancellation cancellation) {
        ensureOpen();
        LinkedHashMap<String, StateValue> body = new LinkedHashMap<>();
        body.put("id", StateValue.string(UUID.randomUUID().toString()));
        body.put("userId", StateValue.string(request.userId()));
        body.put("contentMetadata", contentToProcess(request));
        URI uri = userUri(request.userId(), "/dataSecurityAndGovernance/activities/contentActivities");
        return sendAsync(
                        "contentActivities",
                        "POST",
                        uri,
                        StateValue.object(body),
                        request.tenantId(),
                        Map.of("client-request-id", request.correlationId()),
                        cancellation)
                .thenApply(payload -> new PurviewDecision(false, false, List.of(), payload.requestId()));
    }

    java.util.concurrent.CompletionStage<TokenIdentity> resolveIdentityAsync(RunCancellation cancellation) {
        ensureOpen();
        return settings.authenticationProvider()
                .getTokenAsync(new AzureTokenRequest(List.of(graphScope()), settings.tenantId()), cancellation)
                .thenApply(token -> tokenIdentity(token.token()));
    }

    @Override
    public void close() {
        List<HttpOperation> activeRequests;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            activeRequests = List.copyOf(requests);
        }
        activeRequests.forEach(HttpOperation::cancel);
        requests.clear();
        if (ownedScheduler != null) {
            ownedScheduler.shutdownNow();
        }
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
        awaitTermination(ownedScheduler, "scheduler");
        awaitTermination(ownedExecutor, "executor");
    }

    private java.util.concurrent.CompletionStage<HttpPayload> sendAsync(
            String operation,
            String method,
            URI uri,
            StateValue body,
            String tenantId,
            Map<String, String> headers,
            RunCancellation cancellation) {
        HttpOperation request;
        synchronized (lifecycleLock) {
            if (closed.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("PurviewClient is closed."));
            }
            request = new HttpOperation(operation, method, uri, body, tenantId, headers, cancellation);
            requests.add(request);
        }
        request.result.whenComplete((ignored, failure) -> {
            requests.remove(request);
            request.close();
        });
        request.sendAttempt(0);
        return request.result.minimalCompletionStage();
    }

    private final class HttpOperation implements AutoCloseable {
        private final String operation;
        private final String method;
        private final URI uri;
        private final StateValue body;
        private final String tenantId;
        private final Map<String, String> headers;
        private final RunCancellation cancellation;
        private final Instant started = Instant.now();
        private final CompletableFuture<HttpPayload> result = new CompletableFuture<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicReference<ScheduledFuture<?>> retryTask = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<HttpResponse<byte[]>>> upstream = new AtomicReference<>();
        private final Object requestDispatchLock = new Object();
        private final RunCancellationRegistration registration;

        private HttpOperation(
                String operation,
                String method,
                URI uri,
                StateValue body,
                String tenantId,
                Map<String, String> headers,
                RunCancellation cancellation) {
            this.operation = operation;
            this.method = method;
            this.uri = uri;
            this.body = body;
            this.tenantId = tenantId;
            this.headers = Map.copyOf(headers);
            this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
            registration = RunCancellations.register(cancellation, this::cancel);
        }

        private void sendAttempt(int attempt) {
            if (finished.get()) {
                return;
            }
            if (cancellation.isCancellationRequested()) {
                cancel();
                return;
            }
            settings.authenticationProvider()
                    .getTokenAsync(new AzureTokenRequest(List.of(graphScope()), tenantId), cancellation)
                    .whenComplete((token, authenticationFailure) -> {
                        if (finished.get()) {
                            return;
                        }
                        if (authenticationFailure != null) {
                            record(operation, null, false, started, null);
                            fail(new PurviewException(
                                    "Purview authentication failed.",
                                    unwrap(authenticationFailure),
                                    PurviewException.Kind.AUTHENTICATION,
                                    null,
                                    null,
                                    "authentication_failed",
                                    null));
                            return;
                        }
                        try {
                            byte[] bytes = body == null ? null : json.write(body);
                            if (bytes != null && bytes.length > settings.maxRequestBytes()) {
                                fail(protocol("request_too_large"));
                                return;
                            }
                            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                                    .timeout(settings.requestTimeout())
                                    .header("Authorization", "Bearer " + token.token())
                                    .header("Accept", "application/json")
                                    .header("User-Agent", "agent-framework-java-purview");
                            headers.forEach(request::header);
                            if (bytes == null) {
                                request.method(method, HttpRequest.BodyPublishers.noBody());
                            } else {
                                request.header("Content-Type", "application/json")
                                        .method(method, HttpRequest.BodyPublishers.ofByteArray(bytes));
                            }
                            CompletableFuture<HttpResponse<byte[]>> pending;
                            synchronized (requestDispatchLock) {
                                if (finished.get()) {
                                    return;
                                }
                                pending = httpClient.sendAsync(
                                        request.build(),
                                        BoundedBodyHandlers.byteArray(
                                                settings.maxResponseBytes(), () -> protocol("response_too_large")));
                                upstream.set(pending);
                            }
                            pending.whenComplete((response, transportFailure) ->
                                    handleResponse(pending, response, transportFailure, attempt));
                        } catch (RuntimeException failure) {
                            fail(failure);
                        }
                    });
        }

        private void handleResponse(
                CompletableFuture<HttpResponse<byte[]>> pending,
                HttpResponse<byte[]> response,
                Throwable transportFailure,
                int attempt) {
            upstream.compareAndSet(pending, null);
            if (finished.get()) {
                return;
            }
            if (transportFailure != null) {
                record(operation, null, false, started, null);
                Throwable cause = unwrap(transportFailure);
                if (cause instanceof PurviewException) {
                    fail(cause);
                    return;
                }
                fail(new PurviewException(
                        "Purview transport failed.",
                        cause,
                        PurviewException.Kind.TRANSPORT,
                        null,
                        null,
                        "transport_failed",
                        null));
                return;
            }
            byte[] responseBytes = response.body() == null ? new byte[0] : response.body();
            String responseRequestId = requestId(response);
            if (responseBytes.length > settings.maxResponseBytes()) {
                record(operation, response.statusCode(), false, started, responseRequestId);
                fail(protocol("response_too_large"));
                return;
            }
            Duration retryAfter = retryAfter(response);
            int status = response.statusCode();
            if ((status == 429 || status >= 500) && attempt < settings.maxRetries()) {
                long delay = retryAfter == null ? Math.min(5000L, 200L << attempt) : cappedRetryDelayMillis(retryAfter);
                try {
                    scheduleRetry(attempt + 1, delay);
                } catch (RuntimeException failure) {
                    fail(failure);
                }
                return;
            }
            if (status < 200 || status >= 300) {
                record(operation, status, false, started, responseRequestId);
                fail(serviceFailure(status, responseRequestId, retryAfter, responseBytes));
                return;
            }
            try {
                StateValue.ObjectValue responseBody = responseBytes.length == 0
                        ? StateValue.object(Map.of())
                        : requireObject(json.parse(responseBytes), "response");
                record(operation, status, true, started, responseRequestId);
                succeed(new HttpPayload(
                        responseBody, response.headers().firstValue("etag").orElse(null), responseRequestId));
            } catch (RuntimeException failure) {
                record(operation, status, false, started, responseRequestId);
                fail(failure);
            }
        }

        private void scheduleRetry(int attempt, long delay) {
            AtomicReference<ScheduledFuture<?>> holder = new AtomicReference<>();
            ScheduledFuture<?> next = scheduler.schedule(
                    () -> {
                        retryTask.compareAndSet(holder.get(), null);
                        sendAttempt(attempt);
                    },
                    Math.max(1, delay),
                    TimeUnit.MILLISECONDS);
            holder.set(next);
            ScheduledFuture<?> prior = retryTask.getAndSet(next);
            if (prior != null) {
                prior.cancel(false);
            }
            if (finished.get() && retryTask.compareAndSet(next, null)) {
                next.cancel(false);
            }
        }

        private void succeed(HttpPayload payload) {
            if (finished.compareAndSet(false, true)) {
                result.complete(payload);
            }
        }

        private void fail(Throwable failure) {
            if (finished.compareAndSet(false, true)) {
                result.completeExceptionally(failure);
            }
        }

        private void cancel() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            cancelPending();
            result.completeExceptionally(new RunCancelledException());
        }

        private void cancelPending() {
            ScheduledFuture<?> scheduled = retryTask.getAndSet(null);
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            CompletableFuture<HttpResponse<byte[]>> pending;
            synchronized (requestDispatchLock) {
                pending = upstream.getAndSet(null);
            }
            if (pending != null) {
                pending.cancel(true);
            }
        }

        @Override
        public void close() {
            registration.close();
            cancelPending();
        }
    }

    private PurviewProtectionScopes protectionScopes(HttpPayload payload) {
        StateValue value = payload.body().values().get("value");
        if (!(value instanceof StateValue.ArrayValue array)) {
            throw protocol("missing_protection_scope_value");
        }
        List<PurviewProtectionScope> scopes = array.values().stream()
                .map(item -> requireObject(item, "protection scope"))
                .map(PurviewClient::scope)
                .toList();
        return new PurviewProtectionScopes(scopes, payload.etag(), payload.requestId());
    }

    private static PurviewProtectionScope scope(StateValue.ObjectValue value) {
        Set<PurviewActivity> activities = activities(string(value, "activities", true));
        PurviewExecutionMode mode = PurviewExecutionMode.fromGraphValue(string(value, "executionMode", true));
        List<PurviewAppLocation> locations = locations(value.values().get("locations"));
        List<PurviewPolicyAction> actions = actions(value.values().get("policyActions"));
        return new PurviewProtectionScope(activities, mode, locations, actions);
    }

    private static PurviewDecision decision(HttpPayload payload) {
        StateValue processingErrors = payload.body().values().get("processingErrors");
        if (processingErrors != null
                && (!(processingErrors instanceof StateValue.ArrayValue errors)
                        || !errors.values().isEmpty())) {
            throw protocol("content_processing_failed");
        }
        List<PurviewPolicyAction> actions = actions(payload.body().values().get("policyActions"));
        String state = string(payload.body(), "protectionScopeState", false);
        return new PurviewDecision(
                actions.stream().anyMatch(PurviewPolicyAction::blocksAccess),
                "modified".equalsIgnoreCase(state),
                actions,
                payload.requestId());
    }

    private static StateValue.ObjectValue contentToProcess(PurviewContentRequest request) {
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(request.createdAt());
        StateValue.ObjectValue content = StateValue.object(Map.of(
                "@odata.type", StateValue.string("microsoft.graph.textContent"),
                "data", StateValue.string(request.text())));
        LinkedHashMap<String, StateValue> entry = new LinkedHashMap<>();
        entry.put("@odata.type", StateValue.string("microsoft.graph.processConversationMetadata"));
        entry.put("identifier", StateValue.string(request.messageId()));
        entry.put("content", content);
        entry.put("name", StateValue.string("Agent Framework Message " + request.messageId()));
        entry.put("correlationId", StateValue.string(request.correlationId()));
        entry.put("sequenceNumber", StateValue.integer(request.sequenceNumber()));
        entry.put("isTruncated", StateValue.bool(false));
        entry.put("createdDateTime", StateValue.string(timestamp));
        entry.put("modifiedDateTime", StateValue.string(timestamp));
        return StateValue.object(Map.of(
                "contentEntries", StateValue.array(List.of(StateValue.object(entry))),
                "activityMetadata",
                        StateValue.object(Map.of(
                                "activity", StateValue.string(request.activity().graphValue()))),
                "deviceMetadata", deviceMetadata(),
                "integratedAppMetadata", appMetadata(request.appName(), request.appVersion()),
                "protectedAppMetadata",
                        StateValue.object(Map.of(
                                "name", StateValue.string(request.appName()),
                                "version", StateValue.string(request.appVersion()),
                                "applicationLocation", location(request.location())))));
    }

    private static StateValue.ObjectValue deviceMetadata() {
        return StateValue.object(Map.of(
                "deviceType", StateValue.string("Unmanaged"),
                "operatingSystemSpecifications",
                        StateValue.object(Map.of(
                                "operatingSystemPlatform", StateValue.string("Unknown"),
                                "operatingSystemVersion", StateValue.string("Unknown")))));
    }

    private static StateValue.ObjectValue appMetadata(String name, String version) {
        return StateValue.object(Map.of(
                "name", StateValue.string(name),
                "version", StateValue.string(version)));
    }

    private static StateValue.ObjectValue location(PurviewAppLocation value) {
        return StateValue.object(Map.of(
                "@odata.type", StateValue.string(value.type().odataType()),
                "value", StateValue.string(value.value())));
    }

    private static Set<PurviewActivity> activities(String value) {
        LinkedHashSet<PurviewActivity> activities = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            try {
                activities.add(PurviewActivity.fromGraphValue(part.trim()));
            } catch (IllegalArgumentException ignored) {
                // Unknown future activity never contributes to an allow decision.
            }
        }
        return Set.copyOf(activities);
    }

    private static List<PurviewAppLocation> locations(StateValue value) {
        if (!(value instanceof StateValue.ArrayValue array)) {
            return List.of();
        }
        ArrayList<PurviewAppLocation> result = new ArrayList<>();
        for (StateValue item : array.values()) {
            StateValue.ObjectValue object = requireObject(item, "location");
            String type = string(object, "@odata.type", true).replace("#", "");
            String locationValue = string(object, "value", true);
            PurviewLocationType locationType = null;
            for (PurviewLocationType candidate : PurviewLocationType.values()) {
                if (type.endsWith(
                        candidate.odataType().substring(candidate.odataType().lastIndexOf('.') + 1))) {
                    locationType = candidate;
                    break;
                }
            }
            if (locationType != null) {
                result.add(new PurviewAppLocation(locationType, locationValue));
            }
        }
        return List.copyOf(result);
    }

    private static List<PurviewPolicyAction> actions(StateValue value) {
        if (!(value instanceof StateValue.ArrayValue array)) {
            return List.of();
        }
        ArrayList<PurviewPolicyAction> result = new ArrayList<>();
        for (StateValue item : array.values()) {
            StateValue.ObjectValue object = requireObject(item, "policy action");
            String action = string(object, "action", false);
            if (action == null) {
                String odataType = string(object, "@odata.type", false);
                String normalizedType =
                        odataType != null && odataType.startsWith("#") ? odataType.substring(1) : odataType;
                action = "microsoft.graph.restrictAccessAction".equals(normalizedType)
                        ? "restrictAccessAction"
                        : normalizedType;
            }
            String restrictionAction = string(object, "restrictionAction", false);
            if (action != null || restrictionAction != null) {
                result.add(new PurviewPolicyAction(action, restrictionAction));
            }
        }
        return List.copyOf(result);
    }

    private PurviewException serviceFailure(int status, String requestId, Duration retryAfter, byte[] body) {
        String code = "http_" + status;
        try {
            StateValue.ObjectValue object = requireObject(json.parse(body), "error response");
            StateValue errorValue = object.values().get("error");
            if (errorValue instanceof StateValue.ObjectValue error) {
                code = fallback(string(error, "code", false), code);
            }
        } catch (RuntimeException ignored) {
            // Raw error bodies are never retained.
        }
        PurviewException.Kind kind =
                switch (status) {
                    case 401, 403 -> PurviewException.Kind.AUTHENTICATION;
                    case 402 -> PurviewException.Kind.PAYMENT_REQUIRED;
                    case 429 -> PurviewException.Kind.RATE_LIMIT;
                    default -> PurviewException.Kind.SERVICE;
                };
        return new PurviewException(
                "Purview request failed with HTTP " + status + ".", null, kind, status, requestId, code, retryAfter);
    }

    private TokenIdentity tokenIdentity(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || parts[1].isBlank()) {
                throw protocol("invalid_token_shape");
            }
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            StateValue.ObjectValue claims = requireObject(json.parse(payload), "token claims");
            String objectId = string(claims, "oid", false);
            String idType = string(claims, "idtyp", false);
            String scopes = string(claims, "scp", false);
            String tenant = fallback(string(claims, "tid", false), settings.tenantId());
            String clientId = fallback(string(claims, "appid", false), string(claims, "azp", false));
            boolean applicationToken = "app".equalsIgnoreCase(idType);
            boolean delegatedToken = scopes != null && !scopes.isBlank();
            String userId = !applicationToken && delegatedToken && isGuid(objectId) ? objectId : null;
            return new TokenIdentity(userId, tenant, clientId);
        } catch (RuntimeException failure) {
            throw protocol("invalid_token_claims");
        }
    }

    private static boolean isGuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private URI userUri(String userId, String suffix) {
        return URI.create(settings.graphBaseUri() + "/users/" + encode(userId) + suffix);
    }

    private String graphScope() {
        return "https://" + settings.graphBaseUri().getHost() + "/.default";
    }

    private static String requestId(HttpResponse<?> response) {
        return response.headers()
                .firstValue("request-id")
                .or(() -> response.headers().firstValue("client-request-id"))
                .orElse(null);
    }

    private static Duration retryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("retry-after").orElse(null);
        if (value == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static long cappedRetryDelayMillis(Duration retryAfter) {
        return retryAfter.compareTo(Duration.ofSeconds(30)) >= 0 ? 30_000L : retryAfter.toMillis();
    }

    private void record(String operation, Integer status, boolean succeeded, Instant started, String requestId) {
        try {
            settings.telemetryListener()
                    .record(new PurviewTelemetryEvent(
                            operation, status, succeeded, Duration.between(started, Instant.now()), requestId));
        } catch (RuntimeException ignored) {
            // Telemetry must not alter policy enforcement.
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("PurviewClient is closed.");
        }
    }

    private static StateValue.ObjectValue requireObject(StateValue value, String name) {
        if (!(value instanceof StateValue.ObjectValue object)) {
            throw protocol(name.replace(' ', '_') + "_not_object");
        }
        return object;
    }

    private static String string(StateValue.ObjectValue object, String name, boolean required) {
        StateValue value = object.values().get(name);
        if (value == null || value instanceof StateValue.NullValue) {
            if (required) {
                throw protocol("missing_" + name);
            }
            return null;
        }
        if (!(value instanceof StateValue.StringValue string)) {
            throw protocol("invalid_" + name);
        }
        return string.value();
    }

    private static String fallback(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.replaceAll("(?i)(bearer|token|secret|api[-_ ]?key)\\s*[:=]?\\s*\\S+", "$1=[REDACTED]")
                .replaceAll("[\\r\\n\\t]", " ")
                .trim();
        return clean.substring(0, Math.min(clean.length(), 512));
    }

    private static PurviewException protocol(String code) {
        return new PurviewException(
                "Purview protocol mapping failed.", null, PurviewException.Kind.PROTOCOL, null, null, code, null);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void awaitTermination(ExecutorService executor, String name) {
        if (executor == null) {
            return;
        }
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Purview " + name + " did not terminate.");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Purview " + name + " close was interrupted.", failure);
        }
    }

    record TokenIdentity(String userId, String tenantId, String clientId) {}

    private record HttpPayload(StateValue.ObjectValue body, String etag, String requestId) {}
}
