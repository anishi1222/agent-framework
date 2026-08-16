// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.hosting.HostingAuthentication;
import com.microsoft.agents.hosting.HostingAuthenticationStatus;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingError;
import com.microsoft.agents.hosting.HostingErrorCode;
import com.microsoft.agents.hosting.HostingException;
import com.microsoft.agents.hosting.HostingJsonCodec;
import com.microsoft.agents.hosting.HostingOutcome;
import com.microsoft.agents.hosting.HostingRequestContext;
import com.microsoft.agents.hosting.HostingResumeRequest;
import com.microsoft.agents.hosting.HostingRouteKind;
import com.microsoft.agents.hosting.HostingRun;
import com.microsoft.agents.hosting.HostingRunRequest;
import com.microsoft.agents.hosting.HostingTransportRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Implements the stable Java-hosting v1 HTTP routing and media contract independently of a server
 * library.
 */
public final class HostingHttpHandler implements AutoCloseable {
    /** Stable API base path. */
    public static final String BASE_PATH = "/v1";

    /** Stable WebSocket path. */
    public static final String WEBSOCKET_PATH = "/v1/ws";

    private static final Pattern CORRELATION = Pattern.compile("[A-Za-z0-9._:/-]{1,256}");

    private static final Map<String, List<String>> JSON_HEADERS =
            Map.of("Content-Type", List.of("application/json; charset=utf-8"));

    private static final Map<String, List<String>> SSE_HEADERS = Map.of(
            "Content-Type",
            List.of("text/event-stream; charset=utf-8"),
            "Cache-Control",
            List.of("no-cache, no-store"),
            "Content-Encoding",
            List.of("identity"),
            "X-Accel-Buffering",
            List.of("no"));

    private final HostingDispatcher dispatcher;

    private final HostingHttpServerOptions options;

    private final HostingJsonCodec codec;

    private final HostingHttpSecurity security;

    private final Semaphore requestPermits;

    private final ScheduledThreadPoolExecutor authenticationScheduler;

    private final Set<RequestAdmission> activeAdmissions = ConcurrentHashMap.newKeySet();

    private final Set<AuthenticationAttempt> pendingAuthentications = ConcurrentHashMap.newKeySet();

    private final Map<HostingErrorCode, byte[]> fallbackErrorBodies;

    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a reusable route handler.
     *
     * @param dispatcher hosting dispatcher
     * @param options transport options
     */
    public HostingHttpHandler(HostingDispatcher dispatcher, HostingHttpServerOptions options) {
        this(
                dispatcher,
                options,
                new HostingJsonCodec(Objects.requireNonNull(options, "options").limits()));
    }

    /**
     * Creates a reusable route handler with an explicit shared codec.
     *
     * @param dispatcher hosting dispatcher
     * @param options transport options
     * @param codec strict shared codec
     */
    public HostingHttpHandler(HostingDispatcher dispatcher, HostingHttpServerOptions options, HostingJsonCodec codec) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.options = Objects.requireNonNull(options, "options");
        this.codec = Objects.requireNonNull(codec, "codec");
        security = new HostingHttpSecurity(options);
        requestPermits = new Semaphore(options.limits().maxConcurrentRequests(), true);
        authenticationScheduler = newAuthenticationScheduler();
        fallbackErrorBodies = createFallbackErrorBodies(options.limits().maxResponseBytes());
    }

    /**
     * Authenticates and handles one complete HTTP request.
     *
     * @param request request
     * @return response stage
     */
    public CompletionStage<HostingHttpResponse> handleAsync(HostingHttpRequest request) {
        Objects.requireNonNull(request, "request");
        return handleValidatedAsync(
                request, () -> authenticateAsync(request).thenCompose(context -> routeAsync(request, context)));
    }

    private CompletionStage<HostingHttpResponse> handleValidatedAsync(
            HostingHttpRequest request, Supplier<CompletionStage<HostingHttpResponse>> route) {
        RequestAdmission admission = acquireAdmission();
        if (admission == null) {
            HostingError error = closed.get()
                    ? HostingError.of(HostingErrorCode.CLIENT_CANCELLED, "Hosting HTTP handler is closed.")
                    : new HostingError(
                            HostingErrorCode.TOO_MANY_REQUESTS,
                            "Concurrent request capacity is exhausted.",
                            true,
                            Map.of());
            return CompletableFuture.completedFuture(decorate(request, errorResponse(error)));
        }
        CompletableFuture<HostingHttpResponse> result = new CompletableFuture<>();
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                request.cancellation().cancel();
            }
            if (failure != null) {
                admission.close();
            }
        });
        try {
            admission.attachCancellation(RunCancellations.register(
                    request.cancellation(),
                    () -> completeError(
                            request,
                            result,
                            admission,
                            HostingError.of(HostingErrorCode.CLIENT_CANCELLED, "Hosting request was cancelled."))));
            if (result.isDone()) {
                return result;
            }
            security.validate(request, false);
            if (request.body().length > options.limits().maxRequestBytes()) {
                throw new HostingException(
                        HostingErrorCode.PAYLOAD_TOO_LARGE,
                        "Request exceeds maxRequestBytes " + options.limits().maxRequestBytes() + ".");
            }
            if (request.firstHeader("last-event-id") != null) {
                throw new HostingException(
                        HostingErrorCode.UNPROCESSABLE, "Last-Event-ID replay is not implemented by Java hosting.");
            }
            Objects.requireNonNull(route.get(), "route stage")
                    .whenComplete((response, failure) -> completeRouted(request, result, admission, response, failure));
        } catch (Throwable failure) {
            completeError(request, result, admission, error(failure));
        }
        return result;
    }

    /**
     * Authenticates a security-validated request for a WebSocket handshake.
     *
     * @param request handshake request
     * @return trusted request context stage
     */
    public CompletionStage<HostingRequestContext> authenticateWebSocketAsync(HostingHttpRequest request) {
        Objects.requireNonNull(request, "request");
        RequestAdmission admission = acquireAdmission();
        if (admission == null) {
            return CompletableFuture.failedFuture(new HostingException(
                    closed.get() ? HostingErrorCode.CLIENT_CANCELLED : HostingErrorCode.TOO_MANY_REQUESTS,
                    closed.get() ? "Hosting HTTP handler is closed." : "Concurrent request capacity is exhausted."));
        }
        CompletableFuture<HostingRequestContext> result = new CompletableFuture<>();
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                request.cancellation().cancel();
            }
            admission.close();
        });
        try {
            security.validate(request, true);
            authenticateAsync(request).whenComplete((context, failure) -> {
                if (failure == null) {
                    result.complete(context);
                } else {
                    result.completeExceptionally(RunHandles.unwrap(failure));
                }
            });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    /**
     * Authenticates one bounded security-validated HTTP request for an attached protocol route.
     *
     * <p>This method lets protocol adapters reuse the generic Host, Origin, proxy, body, replay,
     * authentication-timeout, principal, isolation, and cancellation semantics without routing the
     * request through the generic {@code /v1} wire contract.
     *
     * @param request attached protocol request
     * @return trusted request context stage
     */
    public CompletionStage<HostingRequestContext> authenticateHttpAsync(HostingHttpRequest request) {
        Objects.requireNonNull(request, "request");
        RequestAdmission admission = acquireAdmission();
        if (admission == null) {
            return CompletableFuture.failedFuture(new HostingException(
                    closed.get() ? HostingErrorCode.CLIENT_CANCELLED : HostingErrorCode.TOO_MANY_REQUESTS,
                    closed.get() ? "Hosting HTTP handler is closed." : "Concurrent request capacity is exhausted."));
        }
        CompletableFuture<HostingRequestContext> result = new CompletableFuture<>();
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                request.cancellation().cancel();
            }
            admission.close();
        });
        try {
            admission.attachCancellation(RunCancellations.register(
                    request.cancellation(),
                    () -> result.completeExceptionally(new HostingException(
                            HostingErrorCode.CLIENT_CANCELLED, "Hosting request was cancelled."))));
            security.validate(request, false);
            if (request.body().length > options.limits().maxRequestBytes()) {
                throw new HostingException(
                        HostingErrorCode.PAYLOAD_TOO_LARGE,
                        "Request exceeds maxRequestBytes " + options.limits().maxRequestBytes() + ".");
            }
            if (request.firstHeader("last-event-id") != null) {
                throw new HostingException(
                        HostingErrorCode.UNPROCESSABLE, "Last-Event-ID replay is not implemented by Java hosting.");
            }
            authenticateAsync(request).whenComplete((context, failure) -> {
                if (failure == null) {
                    result.complete(context);
                } else {
                    result.completeExceptionally(RunHandles.unwrap(failure));
                }
            });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    /**
     * Creates trusted request context from a transport-validated principal.
     *
     * <p>This overload supports application-framework adapters that resolve identity from their own
     * authenticated principal without reinterpreting run or session identifiers.
     *
     * @param request validated request
     * @param authentication trusted authentication result
     * @return request context
     */
    public HostingRequestContext createContext(HostingHttpRequest request, HostingAuthentication authentication) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(authentication, "authentication");
        if (authentication.status() == HostingAuthenticationStatus.UNAUTHENTICATED) {
            throw new HostingException(HostingErrorCode.UNAUTHENTICATED, "Authentication is required.");
        }
        if (authentication.status() == HostingAuthenticationStatus.FORBIDDEN) {
            throw new HostingException(HostingErrorCode.FORBIDDEN, "Authentication is forbidden.");
        }
        String requestId = correlation(request.firstHeader("x-request-id"), "req-");
        String correlationId = correlation(request.firstHeader("traceparent"), "corr-");
        LinkedHashMap<String, List<String>> trusted = new LinkedHashMap<>();
        options.trustedHeaderNames().forEach(name -> {
            List<String> values = request.headers().get(name);
            if (values != null) {
                trusted.put(name, values);
            }
        });
        Map<String, StateValue> metadata = Map.of(
                "transport.remoteAddress",
                StateValue.string(request.remoteAddress().getAddress().getHostAddress()),
                "transport.scheme",
                StateValue.string(
                        options.transportSecurity() == HostingTransportSecurity.TRUSTED_TLS_PROXY ? "https" : "http"));
        return new HostingRequestContext(
                requestId, correlationId, authentication.principal(), trusted, metadata, request.cancellation());
    }

    /**
     * Handles a request using context resolved by an application framework.
     *
     * @param request validated request
     * @param context trusted context
     * @return response stage
     */
    public CompletionStage<HostingHttpResponse> handleAuthenticatedAsync(
            HostingHttpRequest request, HostingRequestContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        return handleValidatedAsync(request, () -> routeAsync(request, context));
    }

    /**
     * Returns the shared strict codec.
     *
     * @return codec
     */
    public HostingJsonCodec codec() {
        return codec;
    }

    /**
     * Encodes a terminal outcome and discards any continuation if encoding fails.
     *
     * @param outcome terminal outcome
     * @return encoded outcome
     */
    public byte[] encodeOutcome(HostingOutcome outcome) {
        try {
            return codec.encodeOutcome(Objects.requireNonNull(outcome, "outcome"));
        } catch (RuntimeException failure) {
            dispatcher.discardUndeliveredOutcome(outcome);
            throw failure;
        }
    }

    /**
     * Encodes an SSE terminal outcome through its delivery tracker.
     *
     * <p>This overload prevents an encoding failure and a concurrent network failure from discarding
     * the same process-local continuation twice.
     *
     * @param outcome terminal outcome
     * @param response owning tracked SSE response
     * @return encoded outcome
     */
    public byte[] encodeOutcome(HostingOutcome outcome, HostingHttpResponse response) {
        Objects.requireNonNull(response, "response");
        try {
            return codec.encodeOutcome(Objects.requireNonNull(outcome, "outcome"));
        } catch (RuntimeException failure) {
            response.discardUndeliveredOutcome();
            throw failure;
        }
    }

    /**
     * Encodes an error, using the bounded minimal protocol envelope if ordinary encoding exceeds the
     * configured response bound.
     *
     * @param error sanitized error
     * @return bounded encoded error envelope
     */
    public byte[] encodeError(HostingError error) {
        Objects.requireNonNull(error, "error");
        try {
            byte[] body = codec.encodeError(error);
            if (body.length > options.limits().maxResponseBytes()) {
                throw new HostingException(
                        HostingErrorCode.OVERFLOW,
                        "Encoded error exceeds maxResponseBytes "
                                + options.limits().maxResponseBytes() + ".");
            }
            return body;
        } catch (RuntimeException encodingFailure) {
            return Objects.requireNonNull(fallbackErrorBodies.get(error.code()), "fallback error body")
                    .clone();
        }
    }

    /**
     * Returns immutable transport options.
     *
     * @return options
     */
    public HostingHttpServerOptions options() {
        return options;
    }

    private CompletionStage<HostingRequestContext> authenticateAsync(HostingHttpRequest request) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new HostingException(
                    HostingErrorCode.CLIENT_CANCELLED, "Hosting HTTP handler closed during authentication."));
        }
        HostingTransportRequest transport = new HostingTransportRequest(
                request.method(), request.uri(), request.remoteAddress(), request.headers());
        CompletionStage<HostingAuthentication> authentication;
        try {
            authentication = options.authenticator().authenticateAsync(transport);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(
                    new HostingException(HostingErrorCode.UNAUTHENTICATED, "Authentication failed.", failure));
        }
        if (authentication == null) {
            return CompletableFuture.failedFuture(
                    new HostingException(HostingErrorCode.UNAUTHENTICATED, "Authentication failed."));
        }
        AuthenticationAttempt attempt = new AuthenticationAttempt();
        pendingAuthentications.add(attempt);
        if (closed.get()) {
            attempt.fail(new HostingException(
                    HostingErrorCode.CLIENT_CANCELLED, "Hosting HTTP handler closed during authentication."));
        }
        try {
            attempt.attachCancellation(RunCancellations.register(
                    request.cancellation(),
                    () -> attempt.fail(
                            new HostingException(HostingErrorCode.CLIENT_CANCELLED, "Authentication was cancelled."))));
            if (!attempt.isTerminal()) {
                attempt.attachTimeout(authenticationScheduler.schedule(
                        () -> attempt.fail(new HostingException(
                                HostingErrorCode.RUN_TIMEOUT, "Authentication exceeded the transport timeout.")),
                        Math.max(1L, options.limits().idleTimeout().toMillis()),
                        TimeUnit.MILLISECONDS));
            }
        } catch (RuntimeException failure) {
            attempt.fail(
                    closed.get()
                            ? new HostingException(
                                    HostingErrorCode.CLIENT_CANCELLED,
                                    "Hosting HTTP handler closed during authentication.",
                                    failure)
                            : new HostingException(
                                    HostingErrorCode.INTERNAL_ERROR,
                                    "Authentication timeout could not be scheduled.",
                                    failure));
        }
        if (!attempt.isTerminal()) {
            try {
                authentication.whenComplete((result, failure) -> {
                    if (failure != null) {
                        Throwable cause = RunHandles.unwrap(failure);
                        attempt.fail(
                                cause instanceof HostingException
                                        ? cause
                                        : new HostingException(
                                                HostingErrorCode.UNAUTHENTICATED, "Authentication failed.", cause));
                    } else if (result == null) {
                        attempt.fail(new HostingException(HostingErrorCode.UNAUTHENTICATED, "Authentication failed."));
                    } else {
                        attempt.complete(result);
                    }
                });
            } catch (RuntimeException failure) {
                attempt.fail(new HostingException(HostingErrorCode.UNAUTHENTICATED, "Authentication failed.", failure));
            }
        }
        CompletableFuture<HostingRequestContext> context = new CompletableFuture<>();
        attempt.result.whenComplete((result, failure) -> {
            if (failure != null) {
                context.completeExceptionally(RunHandles.unwrap(failure));
                return;
            }
            try {
                context.complete(createContext(request, result));
            } catch (Throwable mappingFailure) {
                context.completeExceptionally(mappingFailure);
            }
        });
        context.whenComplete((ignored, failure) -> {
            if (context.isCancelled()) {
                attempt.fail(new HostingException(HostingErrorCode.CLIENT_CANCELLED, "Authentication was cancelled."));
            }
        });
        return context;
    }

    private CompletionStage<HostingHttpResponse> routeAsync(HostingHttpRequest request, HostingRequestContext context) {
        List<String> segments = segments(request.uri());
        if (segments.size() == 1) {
            requireMethod(request, "GET");
            requireAccept(request, false);
            return CompletableFuture.completedFuture(json(
                    200,
                    StateValue.object(Map.of(
                            "version",
                            StateValue.string(HostingJsonCodec.WIRE_VERSION),
                            "type",
                            StateValue.string("api"),
                            "basePath",
                            StateValue.string(BASE_PATH),
                            "webSocketPath",
                            StateValue.string(WEBSOCKET_PATH),
                            "webSocketSubprotocol",
                            StateValue.string(HostingWebSocketProtocol.SUBPROTOCOL),
                            "lastEventIdReplay",
                            StateValue.bool(false),
                            "crossProcessResume",
                            StateValue.bool(false)))));
        }
        if (segments.size() >= 2 && "ws".equals(segments.get(1))) {
            throw new HostingException(
                    HostingErrorCode.UPGRADE_REQUIRED, "This endpoint requires a WebSocket upgrade.");
        }
        if (segments.size() < 2) {
            throw new HostingException(HostingErrorCode.NOT_FOUND, "Hosting route was not found.");
        }
        HostingRouteKind kind = HostingRouteKind.fromPathSegment(segments.get(1));
        if (segments.size() == 2) {
            requireMethod(request, "GET");
            requireAccept(request, false);
            return dispatcher
                    .listAsync(context, kind)
                    .thenApply(descriptors -> json(200, codec.descriptorsValue(kind, descriptors)));
        }
        String routeId = segments.get(2);
        if (segments.size() == 3) {
            requireMethod(request, "GET");
            requireAccept(request, false);
            return dispatcher
                    .descriptorAsync(context, kind, routeId)
                    .thenApply(descriptor -> json(200, codec.descriptorValue(descriptor)));
        }
        if (segments.size() < 4 || !"runs".equals(segments.get(3))) {
            throw new HostingException(HostingErrorCode.NOT_FOUND, "Hosting route was not found.");
        }
        if (segments.size() == 4) {
            requireMethod(request, "POST");
            requireJsonRequest(request);
            requireAccept(request, false);
            HostingRunRequest runRequest = codec.decodeRunRequest(request.body());
            return dispatcher.runAsync(context, kind, routeId, runRequest).thenApply(this::outcomeResponse);
        }
        if (segments.size() == 5 && "stream".equals(segments.get(4))) {
            requireMethod(request, "POST");
            requireJsonRequest(request);
            requireAccept(request, true);
            HostingRunRequest runRequest = codec.decodeRunRequest(request.body());
            return dispatcher
                    .startStreamingAsync(context, kind, routeId, runRequest)
                    .thenApply(this::sse);
        }
        String runId = segments.get(4);
        if (segments.size() == 5) {
            requireMethod(request, "DELETE");
            requireEmptyBody(request);
            return dispatcher
                    .cancelAsync(context, kind, routeId, runId)
                    .thenApply(ignored -> HostingHttpResponse.finite(204, Map.of(), new byte[0]));
        }
        if (segments.size() == 6 && "resume".equals(segments.get(5))) {
            requireMethod(request, "POST");
            requireJsonRequest(request);
            requireAccept(request, false);
            HostingResumeRequest resume = codec.decodeResumeRequest(request.body());
            return dispatcher.resumeAsync(context, kind, routeId, runId, resume).thenApply(this::outcomeResponse);
        }
        if (segments.size() == 7 && "resume".equals(segments.get(5)) && "stream".equals(segments.get(6))) {
            requireMethod(request, "POST");
            requireJsonRequest(request);
            requireAccept(request, true);
            HostingResumeRequest resume = codec.decodeResumeRequest(request.body());
            return dispatcher
                    .resumeStreamingAsync(context, kind, routeId, runId, resume)
                    .thenApply(this::sse);
        }
        throw new HostingException(HostingErrorCode.NOT_FOUND, "Hosting route was not found.");
    }

    private HostingHttpResponse outcomeResponse(HostingOutcome outcome) {
        int status =
                switch (outcome.status()) {
                    case COMPLETED, INPUT_REQUIRED, APPROVAL_REQUIRED -> 200;
                    case FAILED, CANCELLED, OVERFLOW -> outcome.error().code().httpStatus();
                };
        return HostingHttpResponse.finiteOutcome(
                status, JSON_HEADERS, encodeOutcome(outcome), outcome, dispatcher::discardUndeliveredOutcome);
    }

    private HostingHttpResponse sse(HostingRun run) {
        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>(SSE_HEADERS);
        headers.put("X-Agent-Run-Id", List.of(run.runId()));
        return HostingHttpResponse.trackedSse(headers, run, dispatcher::discardUndeliveredOutcome);
    }

    private HostingHttpResponse json(int status, StateValue value) {
        return HostingHttpResponse.finite(status, JSON_HEADERS, codec.encodeValue(value));
    }

    private HostingHttpResponse errorResponse(HostingError error) {
        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>(JSON_HEADERS);
        if (error.code() == HostingErrorCode.UNAUTHENTICATED) {
            headers.put("WWW-Authenticate", List.of("Bearer"));
        } else if (error.code() == HostingErrorCode.METHOD_NOT_ALLOWED) {
            headers.put("Allow", List.of("GET, POST, DELETE"));
        } else if (error.code() == HostingErrorCode.UPGRADE_REQUIRED) {
            headers.put("Upgrade", List.of("websocket"));
        }
        return HostingHttpResponse.finite(error.code().httpStatus(), headers, encodeError(error));
    }

    private HostingHttpResponse decorate(HostingHttpRequest request, HostingHttpResponse response) {
        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>(response.headers());
        headers.put("Cache-Control", List.of("no-store"));
        headers.put("Content-Security-Policy", List.of("default-src 'none'; frame-ancestors 'none'"));
        headers.put("Referrer-Policy", List.of("no-referrer"));
        headers.put("X-Content-Type-Options", List.of("nosniff"));
        headers.put("X-Frame-Options", List.of("DENY"));
        String corsOrigin = security.corsOrigin(request);
        if (corsOrigin != null) {
            headers.put("Access-Control-Allow-Origin", List.of(corsOrigin));
            headers.put("Vary", List.of("Origin"));
        }
        return response.withHeaders(headers);
    }

    private static HostingError error(Throwable failure) {
        Throwable cause = RunHandles.unwrap(failure);
        if (cause instanceof HostingException hosting) {
            return hosting.error();
        }
        return HostingError.of(HostingErrorCode.INTERNAL_ERROR, "Hosting request failed.");
    }

    private static List<String> segments(URI uri) {
        String path = uri.getRawPath();
        if (path == null || !path.startsWith(BASE_PATH)) {
            throw new HostingException(HostingErrorCode.NOT_FOUND, "Hosting route was not found.");
        }
        if (path.contains("%")) {
            throw new HostingException(
                    HostingErrorCode.MALFORMED_REQUEST, "Percent-encoded hosting paths are not supported.");
        }
        String[] split = path.substring(1).split("/", -1);
        ArrayList<String> result = new ArrayList<>(List.of(split));
        if (result.stream().anyMatch(String::isBlank)) {
            throw new HostingException(HostingErrorCode.MALFORMED_REQUEST, "Hosting path contains an empty segment.");
        }
        if (!"v1".equals(result.getFirst())) {
            throw new HostingException(HostingErrorCode.NOT_FOUND, "Hosting API version was not found.");
        }
        return List.copyOf(result);
    }

    private static void requireMethod(HostingHttpRequest request, String expected) {
        if (!expected.equals(request.method())) {
            throw new HostingException(
                    HostingErrorCode.METHOD_NOT_ALLOWED, "Request method is not allowed for this route.");
        }
    }

    private static void requireJsonRequest(HostingHttpRequest request) {
        if (request.body().length == 0) {
            throw new HostingException(HostingErrorCode.MALFORMED_REQUEST, "JSON request body is required.");
        }
        String contentType = request.firstHeader("content-type");
        if (contentType == null) {
            throw new HostingException(
                    HostingErrorCode.UNSUPPORTED_MEDIA_TYPE, "Content-Type application/json is required.");
        }
        String normalized = contentType.toLowerCase(Locale.ROOT).replace(" ", "");
        if (!"application/json".equals(normalized) && !"application/json;charset=utf-8".equals(normalized)) {
            throw new HostingException(
                    HostingErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "Content-Type must be application/json with optional UTF-8 charset.");
        }
    }

    private static void requireEmptyBody(HostingHttpRequest request) {
        if (request.body().length != 0) {
            throw new HostingException(
                    HostingErrorCode.MALFORMED_REQUEST, "This route does not accept a request body.");
        }
    }

    private static void requireAccept(HostingHttpRequest request, boolean streaming) {
        String accept = request.firstHeader("accept");
        if (accept == null || accept.isBlank() || "*/*".equals(accept.trim())) {
            return;
        }
        Set<String> accepted = java.util.Arrays.stream(
                        accept.toLowerCase(Locale.ROOT).split(","))
                .map(String::trim)
                .map(value -> value.split(";", 2)[0])
                .collect(java.util.stream.Collectors.toSet());
        String required = streaming ? "text/event-stream" : "application/json";
        if (!accepted.contains(required) && !accepted.contains("*/*")) {
            throw new HostingException(
                    HostingErrorCode.NOT_ACCEPTABLE, "Accept does not include the required response media type.");
        }
    }

    private static String correlation(String candidate, String prefix) {
        return candidate != null && CORRELATION.matcher(candidate).matches() ? candidate : prefix + UUID.randomUUID();
    }

    /**
     * Releases active admissions and authentication deadlines, then stops the owned deadline
     * scheduler.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List.copyOf(pendingAuthentications)
                .forEach(attempt -> attempt.fail(new HostingException(
                        HostingErrorCode.CLIENT_CANCELLED, "Hosting HTTP handler closed during authentication.")));
        List.copyOf(activeAdmissions).forEach(RequestAdmission::close);
        authenticationScheduler.shutdownNow();
    }

    int availableRequestPermits() {
        return requestPermits.availablePermits();
    }

    int pendingAuthenticationCount() {
        return pendingAuthentications.size();
    }

    int scheduledAuthenticationTimeoutCount() {
        return authenticationScheduler.getQueue().size();
    }

    boolean isAuthenticationSchedulerShutdown() {
        return authenticationScheduler.isShutdown();
    }

    private RequestAdmission acquireAdmission() {
        if (closed.get() || !requestPermits.tryAcquire()) {
            return null;
        }
        RequestAdmission admission = new RequestAdmission();
        activeAdmissions.add(admission);
        if (closed.get()) {
            admission.close();
            return null;
        }
        return admission;
    }

    private void completeRouted(
            HostingHttpRequest request,
            CompletableFuture<HostingHttpResponse> result,
            RequestAdmission admission,
            HostingHttpResponse response,
            Throwable failure) {
        if (failure != null) {
            completeError(request, result, admission, error(failure));
            return;
        }
        try {
            HostingHttpResponse routed = Objects.requireNonNull(response, "route response");
            HostingHttpResponse decorated = decorate(request, routed);
            if (!routed.isStreaming()) {
                admission.close();
                result.complete(decorated);
                return;
            }
            routed.streamingRun().terminalAsync().whenComplete((ignored, terminalFailure) -> admission.close());
            if (!result.complete(decorated)) {
                routed.streamingRun().cancel();
                admission.close();
            }
        } catch (Throwable completionFailure) {
            completeError(request, result, admission, error(completionFailure));
        }
    }

    private void completeError(
            HostingHttpRequest request,
            CompletableFuture<HostingHttpResponse> result,
            RequestAdmission admission,
            HostingError error) {
        try {
            result.complete(decorate(request, errorResponse(error)));
        } catch (Throwable encodingFailure) {
            result.completeExceptionally(encodingFailure);
        } finally {
            admission.close();
        }
    }

    private static ScheduledThreadPoolExecutor newAuthenticationScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(
                1,
                Thread.ofPlatform()
                        .daemon(true)
                        .name("agent-framework-hosting-auth-deadlines")
                        .factory());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    private static Map<HostingErrorCode, byte[]> createFallbackErrorBodies(long maxResponseBytes) {
        EnumMap<HostingErrorCode, byte[]> bodies = new EnumMap<>(HostingErrorCode.class);
        for (HostingErrorCode code : HostingErrorCode.values()) {
            String json = "{\"version\":\""
                    + HostingJsonCodec.WIRE_VERSION
                    + "\",\"type\":\"error\",\"error\":{\"code\":\""
                    + code.value()
                    + "\",\"message\":\"Request failed.\",\"retryable\":false,\"details\":{}}}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            if (body.length > maxResponseBytes) {
                throw new com.microsoft.agents.core.ValidationException(
                        "maxResponseBytes must fit every minimal Java-hosting error envelope; required at least "
                                + body.length
                                + " bytes.");
            }
            bodies.put(code, body);
        }
        return Map.copyOf(bodies);
    }

    private final class RequestAdmission implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();

        private final AtomicReference<RunCancellationRegistration> cancellationRegistration = new AtomicReference<>();

        private void attachCancellation(RunCancellationRegistration registration) {
            Objects.requireNonNull(registration, "registration");
            if (!cancellationRegistration.compareAndSet(null, registration)) {
                registration.close();
                throw new IllegalStateException("Request cancellation registration is already attached.");
            }
            if (released.get() && cancellationRegistration.compareAndSet(registration, null)) {
                registration.close();
            }
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            RunCancellationRegistration registration = cancellationRegistration.getAndSet(null);
            if (registration != null) {
                registration.close();
            }
            activeAdmissions.remove(this);
            requestPermits.release();
        }
    }

    private final class AuthenticationAttempt {
        private final CompletableFuture<HostingAuthentication> result = new CompletableFuture<>();

        private final AtomicBoolean terminal = new AtomicBoolean();

        private final AtomicReference<RunCancellationRegistration> cancellationRegistration = new AtomicReference<>();

        private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>();

        private void attachCancellation(RunCancellationRegistration registration) {
            Objects.requireNonNull(registration, "registration");
            if (!cancellationRegistration.compareAndSet(null, registration)) {
                registration.close();
                throw new IllegalStateException("Authentication cancellation registration is already attached.");
            }
            if (terminal.get() && cancellationRegistration.compareAndSet(registration, null)) {
                registration.close();
            }
        }

        private void attachTimeout(ScheduledFuture<?> scheduled) {
            Objects.requireNonNull(scheduled, "scheduled");
            if (!timeout.compareAndSet(null, scheduled)) {
                scheduled.cancel(false);
                throw new IllegalStateException("Authentication timeout is already attached.");
            }
            if (terminal.get() && timeout.compareAndSet(scheduled, null)) {
                scheduled.cancel(false);
            }
        }

        private boolean isTerminal() {
            return terminal.get();
        }

        private void complete(HostingAuthentication authentication) {
            finish(authentication, null);
        }

        private void fail(Throwable failure) {
            finish(null, Objects.requireNonNull(failure, "failure"));
        }

        private void finish(HostingAuthentication authentication, Throwable failure) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            pendingAuthentications.remove(this);
            ScheduledFuture<?> scheduled = timeout.getAndSet(null);
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            RunCancellationRegistration registration = cancellationRegistration.getAndSet(null);
            if (registration != null) {
                registration.close();
            }
            if (failure == null) {
                result.complete(authentication);
            } else {
                result.completeExceptionally(failure);
            }
        }
    }
}
