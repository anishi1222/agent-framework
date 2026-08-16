// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.openai;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StorageConflictException;
import com.microsoft.agents.core.VersionedSnapshot;
import com.microsoft.agents.hosting.HostingAuthentication;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingError;
import com.microsoft.agents.hosting.HostingErrorCode;
import com.microsoft.agents.hosting.HostingException;
import com.microsoft.agents.hosting.HostingOutcome;
import com.microsoft.agents.hosting.HostingRequestContext;
import com.microsoft.agents.hosting.HostingRouteKind;
import com.microsoft.agents.hosting.HostingRun;
import com.microsoft.agents.hosting.HostingRunRequest;
import com.microsoft.agents.hosting.HostingTransportRequest;
import com.microsoft.agents.hosting.http.HostingHttpHandler;
import com.microsoft.agents.hosting.http.HostingHttpRequest;
import com.microsoft.agents.hosting.http.HostingHttpSecurity;
import com.microsoft.agents.hosting.http.HostingHttpServerOptions;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles strict OpenAI Responses POST and SSE routes while reusing generic hosting security,
 * authentication, authorization, dispatch, cancellation, and execution bounds.
 */
public final class OpenAIResponsesHttpHandler implements AutoCloseable {
    private static final SecureRandom TOKENS = new SecureRandom();

    private static final Map<String, List<String>> JSON_HEADERS =
            Map.of("Content-Type", List.of(OpenAIResponsesJsonCodec.JSON_MEDIA_TYPE));

    private static final Map<String, List<String>> SSE_HEADERS = Map.of(
            "Content-Type",
            List.of(OpenAIResponsesJsonCodec.SSE_MEDIA_TYPE),
            "Cache-Control",
            List.of("no-cache, no-store"),
            "Content-Encoding",
            List.of("identity"),
            "X-Accel-Buffering",
            List.of("no"));

    private final HostingDispatcher dispatcher;

    private final OpenAIResponsesHostingRegistry registry;

    private final OpenAIResponsesConversationStore conversationStore;

    private final HostingHttpServerOptions transportOptions;

    private final OpenAIResponsesHostingOptions options;

    private final OpenAIResponsesJsonCodec codec;

    private final HostingHttpHandler transportHandler;

    private final HostingHttpSecurity security;

    private final Clock clock;

    private final boolean ownsTransportHandler;

    private final boolean ownsConversationStore;

    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a handler with secure defaults and an owned bounded in-memory conversation store.
     *
     * @param dispatcher shared generic dispatcher
     * @param registry OpenAI Responses route registry
     * @param transportOptions generic HTTP transport options
     */
    public OpenAIResponsesHttpHandler(
            HostingDispatcher dispatcher,
            OpenAIResponsesHostingRegistry registry,
            HostingHttpServerOptions transportOptions) {
        this(dispatcher, registry, transportOptions, OpenAIResponsesHostingOptions.defaults());
    }

    /**
     * Creates a handler with an owned bounded in-memory conversation store.
     *
     * @param dispatcher shared generic dispatcher
     * @param registry OpenAI Responses route registry
     * @param transportOptions generic HTTP transport options
     * @param options OpenAI Responses behavior
     */
    public OpenAIResponsesHttpHandler(
            HostingDispatcher dispatcher,
            OpenAIResponsesHostingRegistry registry,
            HostingHttpServerOptions transportOptions,
            OpenAIResponsesHostingOptions options) {
        this(
                dispatcher,
                registry,
                new InMemoryOpenAIResponsesConversationStore(
                        java.util.Objects.requireNonNull(options, "options").maxConversationEntries(),
                        options.conversationTimeToLive()),
                transportOptions,
                options,
                new OpenAIResponsesJsonCodec(
                        java.util.Objects.requireNonNull(transportOptions, "transportOptions")
                                .limits(),
                        options),
                null,
                true,
                true,
                Clock.systemUTC());
    }

    /**
     * Creates a handler with caller-owned storage and a new generic transport handler.
     *
     * @param dispatcher shared generic dispatcher
     * @param registry OpenAI Responses route registry
     * @param conversationStore principal-scoped transcript store
     * @param transportOptions generic HTTP transport options
     * @param options OpenAI Responses behavior
     * @param codec strict OpenAI Responses codec
     */
    public OpenAIResponsesHttpHandler(
            HostingDispatcher dispatcher,
            OpenAIResponsesHostingRegistry registry,
            OpenAIResponsesConversationStore conversationStore,
            HostingHttpServerOptions transportOptions,
            OpenAIResponsesHostingOptions options,
            OpenAIResponsesJsonCodec codec) {
        this(
                dispatcher,
                registry,
                conversationStore,
                transportOptions,
                options,
                codec,
                null,
                true,
                false,
                Clock.systemUTC());
    }

    /**
     * Creates a handler that reuses a caller-owned generic HTTP handler.
     *
     * @param dispatcher shared generic dispatcher
     * @param registry OpenAI Responses route registry
     * @param conversationStore principal-scoped transcript store
     * @param transportOptions generic HTTP transport options
     * @param options OpenAI Responses behavior
     * @param codec strict OpenAI Responses codec
     * @param transportHandler caller-owned generic transport handler
     */
    public OpenAIResponsesHttpHandler(
            HostingDispatcher dispatcher,
            OpenAIResponsesHostingRegistry registry,
            OpenAIResponsesConversationStore conversationStore,
            HostingHttpServerOptions transportOptions,
            OpenAIResponsesHostingOptions options,
            OpenAIResponsesJsonCodec codec,
            HostingHttpHandler transportHandler) {
        this(
                dispatcher,
                registry,
                conversationStore,
                transportOptions,
                options,
                codec,
                transportHandler,
                false,
                false,
                Clock.systemUTC());
    }

    OpenAIResponsesHttpHandler(
            HostingDispatcher dispatcher,
            OpenAIResponsesHostingRegistry registry,
            OpenAIResponsesConversationStore conversationStore,
            HostingHttpServerOptions transportOptions,
            OpenAIResponsesHostingOptions options,
            OpenAIResponsesJsonCodec codec,
            HostingHttpHandler transportHandler,
            boolean ownsTransportHandler,
            boolean ownsConversationStore,
            Clock clock) {
        this.dispatcher = java.util.Objects.requireNonNull(dispatcher, "dispatcher");
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
        this.conversationStore = java.util.Objects.requireNonNull(conversationStore, "conversationStore");
        this.transportOptions = java.util.Objects.requireNonNull(transportOptions, "transportOptions");
        this.options = java.util.Objects.requireNonNull(options, "options");
        this.codec = java.util.Objects.requireNonNull(codec, "codec");
        this.transportHandler =
                transportHandler == null ? new HostingHttpHandler(dispatcher, transportOptions) : transportHandler;
        this.ownsTransportHandler = ownsTransportHandler;
        this.ownsConversationStore = ownsConversationStore;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        security = new HostingHttpSecurity(transportOptions);
    }

    /**
     * Authenticates and handles one complete bounded HTTP request.
     *
     * @param request request
     * @return response stage
     */
    public CompletionStage<OpenAIResponsesHttpResponse> handleAsync(HostingHttpRequest request) {
        java.util.Objects.requireNonNull(request, "request");
        try {
            requireOpen();
            validateTransportRequest(request);
            Optional<OpenAIResponsesHostingRoute> route =
                    registry.find(request.uri().getPath());
            if (route.isEmpty()) {
                return CompletableFuture.completedFuture(errorResponse(
                        request,
                        HostingError.of(HostingErrorCode.NOT_FOUND, "OpenAI Responses endpoint was not found.")));
            }
            if ("OPTIONS".equals(request.method())) {
                return CompletableFuture.completedFuture(preflight(request));
            }
            return transportHandler
                    .authenticateHttpAsync(request)
                    .thenCompose(context -> routeAuthenticated(request, route.orElseThrow(), context))
                    .exceptionally(failure -> errorResponse(request, failure));
        } catch (Throwable failure) {
            return CompletableFuture.completedFuture(errorResponse(request, failure));
        }
    }

    /**
     * Handles a request with trusted application-framework authentication.
     *
     * @param request request
     * @param authentication trusted authentication result
     * @return response stage
     */
    public CompletionStage<OpenAIResponsesHttpResponse> handleAuthenticatedAsync(
            HostingHttpRequest request, HostingAuthentication authentication) {
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(authentication, "authentication");
        try {
            requireOpen();
            validateTransportRequest(request);
            Optional<OpenAIResponsesHostingRoute> route =
                    registry.find(request.uri().getPath());
            if (route.isEmpty()) {
                return CompletableFuture.completedFuture(errorResponse(
                        request,
                        HostingError.of(HostingErrorCode.NOT_FOUND, "OpenAI Responses endpoint was not found.")));
            }
            if ("OPTIONS".equals(request.method())) {
                return CompletableFuture.completedFuture(preflight(request));
            }
            HostingRequestContext context = transportHandler.createContext(request, authentication);
            return routeAuthenticated(request, route.orElseThrow(), context)
                    .exceptionally(failure -> errorResponse(request, failure));
        } catch (Throwable failure) {
            return CompletableFuture.completedFuture(errorResponse(request, failure));
        }
    }

    /**
     * Resolves trusted application identity after generic transport validation.
     *
     * @param request request
     * @param authenticatedName application-framework principal name, or {@code null}
     * @param resolver principal and isolation resolver
     * @return response stage
     */
    public CompletionStage<OpenAIResponsesHttpResponse> handleResolvedAsync(
            HostingHttpRequest request, String authenticatedName, OpenAIResponsesPrincipalResolver resolver) {
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(resolver, "resolver");
        try {
            requireOpen();
            validateTransportRequest(request);
            HostingTransportRequest transport = new HostingTransportRequest(
                    request.method(), request.uri(), request.remoteAddress(), request.headers());
            return resolver.resolveAsync(authenticatedName, transport)
                    .thenCompose(authentication -> handleAuthenticatedAsync(request, authentication))
                    .exceptionally(failure -> errorResponse(request, failure));
        } catch (Throwable failure) {
            return CompletableFuture.completedFuture(errorResponse(request, failure));
        }
    }

    /**
     * Returns the underlying transport options.
     *
     * @return transport options
     */
    public HostingHttpServerOptions transportOptions() {
        return transportOptions;
    }

    /**
     * Returns the strict OpenAI Responses codec.
     *
     * @return codec
     */
    public OpenAIResponsesJsonCodec codec() {
        return codec;
    }

    /**
     * Returns the configured principal-scoped conversation store.
     *
     * @return conversation store
     */
    public OpenAIResponsesConversationStore conversationStore() {
        return conversationStore;
    }

    /** Releases handler-owned transport and store resources. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (ownsTransportHandler) {
            transportHandler.close();
        }
        if (ownsConversationStore) {
            conversationStore.close();
        }
    }

    OpenAIResponsesHttpResponse transportError(HostingError error) {
        return errorResponse(null, error);
    }

    private CompletionStage<OpenAIResponsesHttpResponse> routeAuthenticated(
            HostingHttpRequest httpRequest, OpenAIResponsesHostingRoute route, HostingRequestContext context) {
        if (!"POST".equals(httpRequest.method())) {
            return CompletableFuture.completedFuture(errorResponse(
                    httpRequest, HostingError.of(HostingErrorCode.METHOD_NOT_ALLOWED, "HTTP method is not allowed.")));
        }
        requireJsonContentType(httpRequest);
        OpenAIResponsesRunRequest request = codec.decodeRunRequest(httpRequest.body());
        requireAccept(httpRequest, request.streaming());
        String responseId = newResponseId();
        OpenAIResponsesResponseMapper mapper =
                new OpenAIResponsesResponseMapper(codec, route, request, responseId, clock);
        return prepareConversation(context, route, request, responseId)
                .thenCompose(prepared -> request.streaming()
                        ? startStreaming(httpRequest, context, route, request, responseId, mapper, prepared)
                        : startFinite(httpRequest, context, route, request, responseId, mapper, prepared));
    }

    private CompletionStage<OpenAIResponsesHttpResponse> startFinite(
            HostingHttpRequest httpRequest,
            HostingRequestContext context,
            OpenAIResponsesHostingRoute route,
            OpenAIResponsesRunRequest request,
            String responseId,
            OpenAIResponsesResponseMapper mapper,
            PreparedConversation prepared) {
        CompletionStage<HostingOutcome> stage;
        try {
            stage = dispatcher.runAsync(
                    context, HostingRouteKind.AGENT, route.routeId(), hostingRequest(request, responseId, prepared));
        } catch (Throwable failure) {
            return releaseThenFail(prepared, failure);
        }
        CompletableFuture<OpenAIResponsesHttpResponse> result = new CompletableFuture<>();
        stage.whenComplete((outcome, failure) -> {
            if (failure != null) {
                completeAfterRelease(result, prepared, failure);
                return;
            }
            OpenAIResponsesResponseMapper.MappedResponse mapped;
            byte[] body;
            try {
                mapped = mapper.mapFinite(outcome);
                body = codec.encodeValue(mapped.value());
            } catch (Throwable mappingFailure) {
                completeAfterRelease(result, prepared, mappingFailure);
                return;
            }
            CompletionStage<Void> persistStage;
            try {
                persistStage = persist(context, route, request, responseId, prepared, mapped.messages());
            } catch (Throwable persistFailure) {
                completeAfterRelease(result, prepared, persistFailure);
                return;
            }
            persistStage.whenComplete((ignored, persistFailure) -> {
                if (persistFailure != null) {
                    completeAfterRelease(result, prepared, persistFailure);
                } else {
                    result.complete(
                            OpenAIResponsesHttpResponse.finite(200, corsHeaders(httpRequest, JSON_HEADERS), body));
                }
            });
        });
        return result.exceptionally(failure -> errorResponse(httpRequest, failure));
    }

    private CompletionStage<OpenAIResponsesHttpResponse> startStreaming(
            HostingHttpRequest httpRequest,
            HostingRequestContext context,
            OpenAIResponsesHostingRoute route,
            OpenAIResponsesRunRequest request,
            String responseId,
            OpenAIResponsesResponseMapper mapper,
            PreparedConversation prepared) {
        CompletionStage<HostingRun> runStage;
        try {
            runStage = dispatcher.startStreamingAsync(
                    context, HostingRouteKind.AGENT, route.routeId(), hostingRequest(request, responseId, prepared));
        } catch (Throwable failure) {
            return releaseThenFail(prepared, failure);
        }
        CompletableFuture<OpenAIResponsesHttpResponse> response = new CompletableFuture<>();
        runStage.whenComplete((run, failure) -> {
            if (failure != null) {
                completeAfterRelease(response, prepared, failure);
                return;
            }
            OpenAIResponsesStreamingPublisher publisher = new OpenAIResponsesStreamingPublisher(
                    run,
                    mapper,
                    codec,
                    messages -> persist(context, route, request, responseId, prepared, messages),
                    () -> release(prepared),
                    dispatcher::discardUndeliveredOutcome);
            OpenAIResponsesHostedRun hosted = new OpenAIResponsesHostedRun(
                    responseId,
                    run.runId(),
                    publisher,
                    publisher.completion().minimalCompletionStage(),
                    run.cancellation(),
                    publisher::discardUndelivered);
            response.complete(OpenAIResponsesHttpResponse.sse(corsHeaders(httpRequest, SSE_HEADERS), hosted));
        });
        return response.exceptionally(failure -> errorResponse(httpRequest, failure));
    }

    private HostingRunRequest hostingRequest(
            OpenAIResponsesRunRequest request, String responseId, PreparedConversation prepared) {
        ArrayList<Message> messages =
                new ArrayList<>(prepared.history().size() + request.messages().size());
        messages.addAll(prepared.history());
        messages.addAll(request.messages());
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        metadata.put("openai.responseId", StateValue.string(responseId));
        if (request.previousResponseId() != null) {
            metadata.put("openai.previousResponseId", StateValue.string(request.previousResponseId()));
        }
        if (request.conversationId() != null) {
            metadata.put("openai.conversationId", StateValue.string(request.conversationId()));
        }
        return new HostingRunRequest(messages, null, request.options(), metadata);
    }

    private CompletionStage<PreparedConversation> prepareConversation(
            HostingRequestContext context,
            OpenAIResponsesHostingRoute route,
            OpenAIResponsesRunRequest request,
            String responseId) {
        if (request.previousResponseId() != null) {
            OpenAIResponsesConversationKey key =
                    key(context, route, OpenAIResponsesReferenceType.RESPONSE, request.previousResponseId());
            return conversationStore.loadAsync(key).thenApply(snapshot -> {
                if (snapshot.isEmpty()) {
                    throw new HostingException(
                            HostingErrorCode.NOT_FOUND,
                            "previous_response_id was not found in the authenticated partition.");
                }
                return PreparedConversation.detached(
                        snapshot.orElseThrow().snapshot().messages());
            });
        }
        if (request.conversationId() == null) {
            return CompletableFuture.completedFuture(PreparedConversation.detached(List.of()));
        }
        OpenAIResponsesConversationKey key =
                key(context, route, OpenAIResponsesReferenceType.CONVERSATION, request.conversationId());
        return acquireConversation(key, responseId, 0);
    }

    private CompletionStage<PreparedConversation> acquireConversation(
            OpenAIResponsesConversationKey key, String responseId, int attempt) {
        return conversationStore
                .loadAsync(key)
                .thenCompose(optional -> {
                    VersionedSnapshot<OpenAIResponsesConversationState> current = optional.orElse(null);
                    long expected = current == null ? OpenAIResponsesConversationStore.CREATE_ONLY : current.revision();
                    OpenAIResponsesConversationState base = current == null
                            ? OpenAIResponsesConversationState.inactive(List.of(), clock.instant())
                            : current.snapshot();
                    if (base.activeRequestId() != null) {
                        return CompletableFuture.failedFuture(new HostingException(
                                HostingErrorCode.CONFLICT,
                                "The OpenAI Responses conversation already has an active run."));
                    }
                    OpenAIResponsesConversationState active =
                            new OpenAIResponsesConversationState(base.messages(), responseId, clock.instant());
                    return conversationStore
                            .compareAndSetAsync(key, active, expected)
                            .thenApply(
                                    stored -> new PreparedConversation(base.messages(), key, stored.revision(), base));
                })
                .exceptionallyCompose(failure -> {
                    Throwable cause = RunHandles.unwrap(failure);
                    if (cause instanceof StorageConflictException && attempt + 1 < options.maxStoreRetries()) {
                        return acquireConversation(key, responseId, attempt + 1);
                    }
                    return CompletableFuture.failedFuture(cause);
                });
    }

    private CompletionStage<Void> persist(
            HostingRequestContext context,
            OpenAIResponsesHostingRoute route,
            OpenAIResponsesRunRequest request,
            String responseId,
            PreparedConversation prepared,
            List<Message> outputMessages) {
        ArrayList<Message> transcript =
                new ArrayList<>(prepared.history().size() + request.messages().size() + outputMessages.size());
        transcript.addAll(prepared.history());
        transcript.addAll(persistentInputMessages(request));
        transcript.addAll(outputMessages);
        OpenAIResponsesConversationState completed =
                OpenAIResponsesConversationState.inactive(transcript, clock.instant());
        boolean retainResponse =
                request.store() || request.previousResponseId() != null || request.conversationId() != null;
        CompletionStage<VersionedSnapshot<OpenAIResponsesConversationState>> responseSave = retainResponse
                ? conversationStore.compareAndSetAsync(
                        key(context, route, OpenAIResponsesReferenceType.RESPONSE, responseId),
                        completed,
                        OpenAIResponsesConversationStore.CREATE_ONLY)
                : CompletableFuture.completedFuture(null);
        return responseSave.thenCompose(responseSnapshot -> {
            if (prepared.activeKey() == null) {
                return CompletableFuture.completedFuture(null);
            }
            return conversationStore
                    .compareAndSetAsync(prepared.activeKey(), completed, prepared.activeRevision())
                    .handle((stored, failure) -> new ConversationPersistResult(responseSnapshot, failure))
                    .thenCompose(persisted -> {
                        if (persisted.failure() == null) {
                            return CompletableFuture.completedFuture(null);
                        }
                        Throwable cause = RunHandles.unwrap(persisted.failure());
                        if (persisted.responseSnapshot() == null) {
                            return CompletableFuture.failedFuture(cause);
                        }
                        OpenAIResponsesConversationKey responseKey =
                                key(context, route, OpenAIResponsesReferenceType.RESPONSE, responseId);
                        return conversationStore
                                .deleteAsync(
                                        responseKey,
                                        persisted.responseSnapshot().revision())
                                .handle((ignored, deleteFailure) -> {
                                    if (deleteFailure != null) {
                                        cause.addSuppressed(RunHandles.unwrap(deleteFailure));
                                    }
                                    throw new java.util.concurrent.CompletionException(cause);
                                });
                    });
        });
    }

    private CompletionStage<Void> release(PreparedConversation prepared) {
        if (prepared.activeKey() == null) {
            return CompletableFuture.completedFuture(null);
        }
        OpenAIResponsesConversationState released =
                OpenAIResponsesConversationState.inactive(prepared.baseState().messages(), clock.instant());
        return conversationStore
                .compareAndSetAsync(prepared.activeKey(), released, prepared.activeRevision())
                .thenApply(ignored -> null);
    }

    private static List<Message> persistentInputMessages(OpenAIResponsesRunRequest request) {
        if (request.requestInfo().instructions() == null) {
            return request.messages();
        }
        return request.messages().subList(1, request.messages().size());
    }

    private <T> CompletionStage<T> releaseThenFail(PreparedConversation prepared, Throwable failure) {
        CompletableFuture<T> result = new CompletableFuture<>();
        completeAfterRelease(result, prepared, failure);
        return result;
    }

    private <T> void completeAfterRelease(
            CompletableFuture<T> result, PreparedConversation prepared, Throwable failure) {
        Throwable cause = RunHandles.unwrap(failure);
        CompletionStage<Void> releaseStage;
        try {
            releaseStage = release(prepared);
        } catch (Throwable releaseFailure) {
            cause.addSuppressed(RunHandles.unwrap(releaseFailure));
            result.completeExceptionally(cause);
            return;
        }
        releaseStage.whenComplete((ignored, releaseFailure) -> {
            if (releaseFailure != null) {
                cause.addSuppressed(RunHandles.unwrap(releaseFailure));
            }
            result.completeExceptionally(cause);
        });
    }

    private OpenAIResponsesConversationKey key(
            HostingRequestContext context,
            OpenAIResponsesHostingRoute route,
            OpenAIResponsesReferenceType type,
            String identifier) {
        return new OpenAIResponsesConversationKey(
                context.principalId(), context.isolationId(), route.routeId(), type, identifier);
    }

    private OpenAIResponsesHttpResponse preflight(HostingHttpRequest request) {
        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Allow", List.of("POST, OPTIONS"));
        headers.put("Access-Control-Allow-Methods", List.of("POST, OPTIONS"));
        headers.put("Access-Control-Allow-Headers", List.of("authorization, content-type, traceparent, x-request-id"));
        headers.put("Access-Control-Max-Age", List.of("600"));
        return OpenAIResponsesHttpResponse.finite(204, corsHeaders(request, headers), new byte[0]);
    }

    private OpenAIResponsesHttpResponse errorResponse(HostingHttpRequest request, Throwable failure) {
        Throwable cause = RunHandles.unwrap(failure);
        HostingError error;
        if (cause instanceof HostingException hosting) {
            error = hosting.error();
        } else if (cause instanceof StorageConflictException) {
            error = HostingError.of(
                    HostingErrorCode.CONFLICT, "OpenAI Responses conversation state changed concurrently.");
        } else if (cause instanceof IllegalArgumentException) {
            error = HostingError.of(HostingErrorCode.MALFORMED_REQUEST, "OpenAI Responses request is invalid.");
        } else {
            error = HostingError.of(HostingErrorCode.INTERNAL_ERROR, "OpenAI Responses request failed.");
        }
        return errorResponse(request, error);
    }

    private OpenAIResponsesHttpResponse errorResponse(HostingHttpRequest request, HostingError error) {
        byte[] body;
        try {
            body = codec.encodeValue(codec.errorValue(error));
        } catch (RuntimeException encodingFailure) {
            body = ("{\"error\":{\"code\":\"internal_error\",\"message\":"
                            + "\"OpenAI Responses request failed.\",\"param\":null,"
                            + "\"type\":\"server_error\"}}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        return OpenAIResponsesHttpResponse.finite(
                httpStatus(error.code()), request == null ? JSON_HEADERS : corsHeaders(request, JSON_HEADERS), body);
    }

    private Map<String, List<String>> corsHeaders(HostingHttpRequest request, Map<String, List<String>> base) {
        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>(base);
        String origin;
        try {
            origin = security.corsOrigin(request);
        } catch (HostingException ignored) {
            origin = null;
        }
        if (origin != null) {
            headers.put("Access-Control-Allow-Origin", List.of(origin));
            headers.put("Vary", List.of("Origin"));
        }
        return Map.copyOf(headers);
    }

    private static int httpStatus(HostingErrorCode code) {
        return switch (code) {
            case UNPROCESSABLE, MALFORMED_REQUEST -> 400;
            default -> code.httpStatus();
        };
    }

    private static void requireJsonContentType(HostingHttpRequest request) {
        String contentType = request.firstHeader("content-type");
        if (contentType == null || !"application/json".equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
            throw new HostingException(
                    HostingErrorCode.UNSUPPORTED_MEDIA_TYPE, "OpenAI Responses requests require application/json.");
        }
    }

    private static void requireAccept(HostingHttpRequest request, boolean streaming) {
        String accept = request.firstHeader("accept");
        if (accept == null || accept.contains("*/*")) {
            return;
        }
        String normalized = accept.toLowerCase(Locale.ROOT);
        boolean accepted =
                streaming ? normalized.contains("text/event-stream") : normalized.contains("application/json");
        if (!accepted) {
            throw new HostingException(
                    HostingErrorCode.NOT_ACCEPTABLE,
                    streaming
                            ? "Streaming OpenAI Responses requires text/event-stream."
                            : "Finite OpenAI Responses requires application/json.");
        }
    }

    private static String newResponseId() {
        byte[] random = new byte[18];
        TOKENS.nextBytes(random);
        return "resp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new HostingException(HostingErrorCode.CLIENT_CANCELLED, "OpenAI Responses handler is closed.");
        }
    }

    private void validateTransportRequest(HostingHttpRequest request) {
        security.validate(request, false);
        if (request.body().length > transportOptions.limits().maxRequestBytes()) {
            throw new HostingException(HostingErrorCode.PAYLOAD_TOO_LARGE, "Request exceeds maxRequestBytes.");
        }
        if (request.firstHeader("last-event-id") != null) {
            throw new HostingException(HostingErrorCode.UNPROCESSABLE, "Last-Event-ID replay is not implemented.");
        }
    }

    private record PreparedConversation(
            List<Message> history,
            OpenAIResponsesConversationKey activeKey,
            long activeRevision,
            OpenAIResponsesConversationState baseState) {
        private PreparedConversation {
            history = List.copyOf(java.util.Objects.requireNonNull(history, "history"));
            boolean active = activeKey != null;
            if (active != (activeRevision > 0 && baseState != null)) {
                throw new IllegalArgumentException("Active conversation acquisition fields are inconsistent.");
            }
        }

        private static PreparedConversation detached(List<Message> history) {
            return new PreparedConversation(history, null, 0, null);
        }
    }

    private record ConversationPersistResult(
            VersionedSnapshot<OpenAIResponsesConversationState> responseSnapshot, Throwable failure) {}
}
