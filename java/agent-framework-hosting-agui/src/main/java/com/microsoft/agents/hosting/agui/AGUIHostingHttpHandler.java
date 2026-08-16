// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.ContentStateCodec;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StorageConflictException;
import com.microsoft.agents.core.VersionedSnapshot;
import com.microsoft.agents.hosting.HostingApprovalDecision;
import com.microsoft.agents.hosting.HostingApprovalRequest;
import com.microsoft.agents.hosting.HostingAuthentication;
import com.microsoft.agents.hosting.HostingContinuationDescriptor;
import com.microsoft.agents.hosting.HostingContinuationType;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingErrorCode;
import com.microsoft.agents.hosting.HostingException;
import com.microsoft.agents.hosting.HostingOutcome;
import com.microsoft.agents.hosting.HostingOutcomeStatus;
import com.microsoft.agents.hosting.HostingResumeRequest;
import com.microsoft.agents.hosting.HostingRun;
import com.microsoft.agents.hosting.HostingRunRequest;
import com.microsoft.agents.hosting.HostingTransportRequest;
import com.microsoft.agents.hosting.http.HostingHttpHandler;
import com.microsoft.agents.hosting.http.HostingHttpRequest;
import com.microsoft.agents.hosting.http.HostingHttpSecurity;
import com.microsoft.agents.hosting.http.HostingHttpServerOptions;
import com.microsoft.agents.protocols.agui.AGUIErrorCode;
import com.microsoft.agents.protocols.agui.AGUIEvent;
import com.microsoft.agents.protocols.agui.AGUIEvents;
import com.microsoft.agents.protocols.agui.AGUIInterrupt;
import com.microsoft.agents.protocols.agui.AGUIJsonCodec;
import com.microsoft.agents.protocols.agui.AGUIMessageConverter;
import com.microsoft.agents.protocols.agui.AGUIProtocol;
import com.microsoft.agents.protocols.agui.AGUIProtocolException;
import com.microsoft.agents.protocols.agui.AGUIResumeEntry;
import com.microsoft.agents.protocols.agui.AGUIResumeStatus;
import com.microsoft.agents.protocols.agui.AGUIRunOutcomes;
import com.microsoft.agents.protocols.agui.RunAgentInput;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Handles exact AG-UI POST and capability routes while reusing generic hosting security,
 * authentication, dispatch, cancellation, limits, and continuations.
 */
public final class AGUIHostingHttpHandler implements AutoCloseable {
    /** Framework-extension capability response media type. */
    public static final String CAPABILITY_MEDIA_TYPE =
            "application/vnd.microsoft.agent-framework.agui-capabilities+json";

    private static final SecureRandom TOKENS = new SecureRandom();

    private final HostingDispatcher dispatcher;

    private final AGUIHostingRegistry registry;

    private final AGUIThreadStore threadStore;

    private final HostingHttpServerOptions transportOptions;

    private final AGUIHostingOptions options;

    private final AGUIJsonCodec codec;

    private final AGUIMessageConverter messageConverter;

    private final HostingHttpHandler transportHandler;

    private final HostingHttpSecurity security;

    private final boolean ownsTransportHandler;

    /**
     * Creates a reusable AG-UI route handler.
     *
     * @param dispatcher shared generic dispatcher
     * @param registry AG-UI route registry
     * @param threadStore principal-scoped thread store
     * @param transportOptions generic hosting transport options
     * @param options protocol options
     * @param codec strict AG-UI codec
     */
    public AGUIHostingHttpHandler(
            HostingDispatcher dispatcher,
            AGUIHostingRegistry registry,
            AGUIThreadStore threadStore,
            HostingHttpServerOptions transportOptions,
            AGUIHostingOptions options,
            AGUIJsonCodec codec) {
        this.dispatcher = java.util.Objects.requireNonNull(dispatcher, "dispatcher");
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
        this.threadStore = java.util.Objects.requireNonNull(threadStore, "threadStore");
        this.transportOptions = java.util.Objects.requireNonNull(transportOptions, "transportOptions");
        this.options = java.util.Objects.requireNonNull(options, "options");
        this.codec = java.util.Objects.requireNonNull(codec, "codec");
        messageConverter = new AGUIMessageConverter(codec);
        transportHandler = new HostingHttpHandler(dispatcher, transportOptions);
        ownsTransportHandler = true;
        security = new HostingHttpSecurity(transportOptions);
    }

    /**
     * Creates a route handler that reuses an existing generic HTTP handler.
     *
     * <p>The supplied handler remains caller-owned. This overload is used by the Spring adapter so
     * authentication schedulers and generic transport semantics are not duplicated.
     *
     * @param dispatcher shared generic dispatcher
     * @param registry AG-UI route registry
     * @param threadStore principal-scoped thread store
     * @param transportOptions generic hosting transport options
     * @param options protocol options
     * @param codec strict AG-UI codec
     * @param transportHandler shared caller-owned generic HTTP handler
     */
    public AGUIHostingHttpHandler(
            HostingDispatcher dispatcher,
            AGUIHostingRegistry registry,
            AGUIThreadStore threadStore,
            HostingHttpServerOptions transportOptions,
            AGUIHostingOptions options,
            AGUIJsonCodec codec,
            HostingHttpHandler transportHandler) {
        this.dispatcher = java.util.Objects.requireNonNull(dispatcher, "dispatcher");
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
        this.threadStore = java.util.Objects.requireNonNull(threadStore, "threadStore");
        this.transportOptions = java.util.Objects.requireNonNull(transportOptions, "transportOptions");
        this.options = java.util.Objects.requireNonNull(options, "options");
        this.codec = java.util.Objects.requireNonNull(codec, "codec");
        messageConverter = new AGUIMessageConverter(codec);
        this.transportHandler = java.util.Objects.requireNonNull(transportHandler, "transportHandler");
        ownsTransportHandler = false;
        security = new HostingHttpSecurity(transportOptions);
    }

    /**
     * Authenticates and handles one complete bounded HTTP request.
     *
     * @param request request
     * @return response stage
     */
    public CompletionStage<AGUIHttpResponse> handleAsync(HostingHttpRequest request) {
        java.util.Objects.requireNonNull(request, "request");
        try {
            String path = request.uri().getPath();
            Optional<AGUIHostingRoute> route = registry.find(path);
            if (route.isEmpty()) {
                return CompletableFuture.completedFuture(
                        errorResponse(request, 404, "NOT_FOUND", "AG-UI endpoint was not found."));
            }
            if ("OPTIONS".equals(request.method())) {
                return CompletableFuture.completedFuture(preflight(request, route.orElseThrow()));
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
    public CompletionStage<AGUIHttpResponse> handleAuthenticatedAsync(
            HostingHttpRequest request, HostingAuthentication authentication) {
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(authentication, "authentication");
        try {
            security.validate(request, false);
            if (request.body().length > transportOptions.limits().maxRequestBytes()) {
                throw new HostingException(HostingErrorCode.PAYLOAD_TOO_LARGE, "Request exceeds maxRequestBytes.");
            }
            if (request.firstHeader("last-event-id") != null) {
                throw new HostingException(HostingErrorCode.UNPROCESSABLE, "Last-Event-ID replay is not implemented.");
            }
            Optional<AGUIHostingRoute> route = registry.find(request.uri().getPath());
            if (route.isEmpty()) {
                return CompletableFuture.completedFuture(
                        errorResponse(request, 404, "NOT_FOUND", "AG-UI endpoint was not found."));
            }
            var context = transportHandler.createContext(request, authentication);
            return routeAuthenticated(request, route.orElseThrow(), context)
                    .exceptionally(failure -> errorResponse(request, failure));
        } catch (Throwable failure) {
            return CompletableFuture.completedFuture(errorResponse(request, failure));
        }
    }

    /**
     * Resolves trusted application identity after generic transport security validation.
     *
     * @param request request
     * @param authenticatedName application-framework principal name, or {@code null}
     * @param resolver principal and isolation resolver
     * @return response stage
     */
    public CompletionStage<AGUIHttpResponse> handleResolvedAsync(
            HostingHttpRequest request, String authenticatedName, AGUIPrincipalResolver resolver) {
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(resolver, "resolver");
        try {
            security.validate(request, false);
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
     * Returns the strict AG-UI codec.
     *
     * @return codec
     */
    public AGUIJsonCodec codec() {
        return codec;
    }

    /** Releases authentication timeout resources owned by this handler. */
    @Override
    public void close() {
        if (ownsTransportHandler) {
            transportHandler.close();
        }
    }

    private CompletionStage<AGUIHttpResponse> routeAuthenticated(
            HostingHttpRequest request,
            AGUIHostingRoute route,
            com.microsoft.agents.hosting.HostingRequestContext context) {
        if ("GET".equals(request.method())
                && route.capabilitiesPath().equals(request.uri().getPath())) {
            return CompletableFuture.completedFuture(capabilities(request, route));
        }
        if (!"POST".equals(request.method())
                || !route.path().equals(request.uri().getPath())) {
            return CompletableFuture.completedFuture(
                    errorResponse(request, 405, "METHOD_NOT_ALLOWED", "HTTP method is not allowed."));
        }
        requireJsonContentType(request);
        requireSseAccept(request);
        RunAgentInput input = codec.decodeRunAgentInput(request.body());
        AGUIThreadKey key = new AGUIThreadKey(
                context.principalId(), context.isolationId(), route.kind(), route.routeId(), input.threadId());
        return acquire(key, input, 0).thenCompose(acquired -> start(request, context, route, key, input, acquired));
    }

    private CompletionStage<AGUIHttpResponse> start(
            HostingHttpRequest request,
            com.microsoft.agents.hosting.HostingRequestContext context,
            AGUIHostingRoute route,
            AGUIThreadKey key,
            RunAgentInput input,
            AcquiredThread acquired) {
        CompletionStage<HostingRun> runStage;
        try {
            if (acquired.pending() == null) {
                LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
                metadata.put("agui.threadId", StateValue.string(input.threadId()));
                metadata.put("agui.runId", StateValue.string(input.runId()));
                metadata.put("agui.input", codec.decodeValue(codec.encodeRunAgentInput(input)));
                HostingRunRequest runRequest = new HostingRunRequest(
                        messageConverter.toCoreMessages(input.messages()),
                        input.state(),
                        new RunOptions(null, null, metadata),
                        Map.of());
                runStage = dispatcher.startStreamingAsync(context, route.kind(), route.routeId(), runRequest);
            } else {
                runStage = dispatcher.resumeStreamingAsync(
                        context,
                        route.kind(),
                        route.routeId(),
                        acquired.pending().hostRunId(),
                        resumeRequest(acquired.pending(), input.resume()));
            }
        } catch (RuntimeException failure) {
            releaseAfterStartFailure(key, input, acquired);
            return CompletableFuture.failedFuture(failure);
        }
        return runStage.thenApply(run -> responseForRun(request, route, key, input, acquired, run));
    }

    private AGUIHttpResponse responseForRun(
            HostingHttpRequest request,
            AGUIHostingRoute route,
            AGUIThreadKey key,
            RunAgentInput input,
            AcquiredThread acquired,
            HostingRun run) {
        AGUIThreadAccumulator accumulator = new AGUIThreadAccumulator(input.messages(), input.state());
        AGUITerminalMapper terminalMapper =
                outcome -> persistTerminal(key, route, input, acquired, outcome, accumulator);
        AGUIHostingPublisher publisher = new AGUIHostingPublisher(
                run,
                route.kind(),
                input,
                options.includeRunInput(),
                codec,
                terminalMapper,
                dispatcher::discardUndeliveredOutcome,
                accumulator::accept);
        AGUIHostedRun hosted = new AGUIHostedRun(
                run.runId(),
                publisher,
                publisher.completion().minimalCompletionStage(),
                run.cancellation(),
                dispatcher::discardUndeliveredOutcome);
        publisher.outcomeTracker(hosted::trackOutcome);
        publisher.completion().whenComplete((ignored, failure) -> {
            if (failure != null) {
                releaseAfterCancellation(key, input, acquired, accumulator);
            }
        });
        return AGUIHttpResponse.sse(
                corsHeaders(
                        request,
                        Map.of(
                                "Content-Type",
                                List.of(AGUIProtocol.SSE_MEDIA_TYPE),
                                "Cache-Control",
                                List.of("no-cache, no-transform"),
                                "X-Accel-Buffering",
                                List.of("no"))),
                hosted);
    }

    private CompletionStage<List<AGUIEvent>> persistTerminal(
            AGUIThreadKey key,
            AGUIHostingRoute route,
            RunAgentInput input,
            AcquiredThread acquired,
            HostingOutcome outcome,
            AGUIThreadAccumulator accumulator) {
        AGUIPendingContinuation pending = null;
        List<AGUIEvent> terminal;
        if (outcome.status() == HostingOutcomeStatus.COMPLETED) {
            List<AGUIEvent> projected = projectAgentResult(outcome.result(), input);
            projected.forEach(accumulator::accept);
            ArrayList<AGUIEvent> completed = new ArrayList<>(projected);
            completed.add(new AGUIEvents.RunFinished(
                    input.threadId(), input.runId(), outcome.result(), new AGUIRunOutcomes.Success(), now(), null));
            terminal = List.copyOf(completed);
        } else if (outcome.status() == HostingOutcomeStatus.INPUT_REQUIRED
                || outcome.status() == HostingOutcomeStatus.APPROVAL_REQUIRED) {
            pending = pending(input, route.kind(), outcome);
            ArrayList<AGUIEvent> interrupted = new ArrayList<>(missingApprovalToolCalls(outcome, accumulator));
            interrupted.forEach(accumulator::accept);
            interrupted.add(new AGUIEvents.RunFinished(
                    input.threadId(),
                    input.runId(),
                    null,
                    new AGUIRunOutcomes.Interrupt(pending.interrupts()),
                    now(),
                    null));
            terminal = List.copyOf(interrupted);
        } else {
            terminal = List.of(new AGUIEvents.RunError(
                    outcome.error() == null
                            ? "Hosted execution failed."
                            : outcome.error().message(),
                    outcome.error() == null
                            ? outcome.status().name()
                            : outcome.error().code().value(),
                    now(),
                    null));
        }
        AGUIThreadState replacement =
                new AGUIThreadState(accumulator.messages(), accumulator.state(), null, pending, Instant.now());
        return threadStore
                .compareAndSetAsync(key, replacement, acquired.revision())
                .thenApply(ignored -> terminal);
    }

    private List<AGUIEvent> missingApprovalToolCalls(HostingOutcome outcome, AGUIThreadAccumulator accumulator) {
        HostingContinuationDescriptor continuation = outcome.continuation();
        if (continuation == null || continuation.type() != HostingContinuationType.APPROVAL) {
            return List.of();
        }
        ArrayList<AGUIEvent> events = new ArrayList<>();
        for (HostingApprovalRequest approval : continuation.approvalRequests()) {
            if (accumulator.hasToolCall(approval.callId())) {
                continue;
            }
            events.add(new AGUIEvents.ToolCallStart(approval.callId(), approval.toolName(), null, now(), null));
            events.add(new AGUIEvents.ToolCallArgs(
                    approval.callId(),
                    new String(codec.encodeValue(approval.arguments()), java.nio.charset.StandardCharsets.UTF_8),
                    now(),
                    null));
            events.add(new AGUIEvents.ToolCallEnd(approval.callId(), now(), null));
        }
        return List.copyOf(events);
    }

    private List<AGUIEvent> projectAgentResult(StateValue result, RunAgentInput input) {
        if (!(result instanceof StateValue.ObjectValue object)
                || !(object.values().get("messages") instanceof StateValue.ArrayValue messages)) {
            return List.of();
        }
        ArrayList<AGUIEvent> events = new ArrayList<>();
        HashSet<String> priorToolCalls = input.messages().stream()
                .filter(com.microsoft.agents.protocols.agui.AGUIMessages.Assistant.class::isInstance)
                .map(com.microsoft.agents.protocols.agui.AGUIMessages.Assistant.class::cast)
                .flatMap(message -> message.toolCalls().stream())
                .map(com.microsoft.agents.protocols.agui.AGUIMessages.ToolCall::id)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        int index = 0;
        for (StateValue value : messages.values()) {
            Message message = decodeHostedMessage(value, index++);
            com.microsoft.agents.protocols.agui.AGUIMessage converted =
                    messageConverter.toAGUIMessage(message, "response-" + index);
            switch (converted) {
                case com.microsoft.agents.protocols.agui.AGUIMessages.Assistant assistant -> {
                    if (assistant.content() != null) {
                        events.add(new AGUIEvents.TextMessageStart(
                                assistant.id(),
                                com.microsoft.agents.protocols.agui.AGUIRole.ASSISTANT,
                                assistant.name(),
                                now(),
                                null));
                        events.add(new AGUIEvents.TextMessageContent(assistant.id(), assistant.content(), now(), null));
                        events.add(new AGUIEvents.TextMessageEnd(assistant.id(), now(), null));
                    }
                    assistant.toolCalls().stream()
                            .filter(call -> !priorToolCalls.contains(call.id()))
                            .forEach(call -> {
                                events.add(new AGUIEvents.ToolCallStart(
                                        call.id(), call.function().name(), assistant.id(), now(), null));
                                events.add(new AGUIEvents.ToolCallArgs(
                                        call.id(), call.function().arguments(), now(), null));
                                events.add(new AGUIEvents.ToolCallEnd(call.id(), now(), null));
                            });
                }
                case com.microsoft.agents.protocols.agui.AGUIMessages.Tool tool ->
                    events.add(new AGUIEvents.ToolCallResult(
                            tool.id(),
                            tool.toolCallId(),
                            tool.content(),
                            com.microsoft.agents.protocols.agui.AGUIRole.TOOL,
                            now(),
                            null));
                default -> {
                    // Non-assistant response roles are preserved only in the terminal result.
                }
            }
        }
        return List.copyOf(events);
    }

    private static Message decodeHostedMessage(StateValue value, int index) {
        StateValue.ObjectValue object = requireObject(value, "hosted response message");
        StateValue role = object.values().get("role");
        StateValue contents = object.values().get("contents");
        if (!(role instanceof StateValue.StringValue roleValue)
                || !(contents instanceof StateValue.ArrayValue contentValues)) {
            throw new HostingException(HostingErrorCode.INTERNAL_ERROR, "Hosted response message is malformed.");
        }
        ContentStateCodec contentCodec = new ContentStateCodec();
        List<Content> decoded = contentValues.values().stream()
                .map(content -> contentCodec.decode(content, ContentStateCodec.VERSION))
                .toList();
        String authorName = stateString(object.values().get("authorName"));
        String messageId = stateString(object.values().get("messageId"));
        StateValue metadata = object.values().get("metadata");
        Map<String, StateValue> metadataValues =
                metadata instanceof StateValue.ObjectValue metadataObject ? metadataObject.values() : Map.of();
        return new Message(
                Role.of(roleValue.value()),
                decoded,
                authorName,
                messageId == null ? "response-" + index : messageId,
                metadataValues);
    }

    private CompletionStage<AcquiredThread> acquire(AGUIThreadKey key, RunAgentInput input, int attempt) {
        return threadStore.loadAsync(key).thenCompose(loaded -> {
            long revision = loaded.map(VersionedSnapshot::revision).orElse(AGUIThreadStore.CREATE_ONLY);
            AGUIThreadState current = loaded.map(VersionedSnapshot::snapshot)
                    .orElseGet(() -> AGUIThreadState.initial(input.messages(), input.state(), Instant.now()));
            if (current.activeClientRunId() != null) {
                return CompletableFuture.failedFuture(new HostingException(
                        HostingErrorCode.CONFLICT, "Another run is active on this principal-scoped thread."));
            }
            AGUIPendingContinuation pending = current.pendingContinuation();
            validateResumeBoundary(pending, input.resume());
            AGUIThreadState active =
                    new AGUIThreadState(input.messages(), input.state(), input.runId(), pending, Instant.now());
            return threadStore
                    .compareAndSetAsync(key, active, revision)
                    .handle((stored, failure) -> {
                        if (failure == null) {
                            return CompletableFuture.completedFuture(new AcquiredThread(stored.revision(), pending));
                        }
                        Throwable cause = com.microsoft.agents.core.RunHandles.unwrap(failure);
                        if (cause instanceof StorageConflictException && attempt + 1 < options.maxStoreRetries()) {
                            return acquire(key, input, attempt + 1).toCompletableFuture();
                        }
                        return CompletableFuture.<AcquiredThread>failedFuture(cause);
                    })
                    .thenCompose(stage -> stage);
        });
    }

    private static void validateResumeBoundary(AGUIPendingContinuation pending, List<AGUIResumeEntry> resume) {
        if (pending == null) {
            if (!resume.isEmpty()) {
                throw new HostingException(HostingErrorCode.CONFLICT, "Resume entries do not match an open interrupt.");
            }
            return;
        }
        if (Instant.now().isAfter(pending.expiresAt())) {
            throw new HostingException(HostingErrorCode.CONFLICT, "AG-UI continuation has expired.");
        }
        if (resume.isEmpty()) {
            throw new HostingException(HostingErrorCode.CONFLICT, "Open interrupts must be resolved before new input.");
        }
        HashSet<String> expected = pending.interrupts().stream()
                .map(AGUIInterrupt::id)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        HashSet<String> supplied = resume.stream()
                .map(AGUIResumeEntry::interruptId)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        if (supplied.size() != resume.size() || !expected.equals(supplied)) {
            throw new HostingException(
                    HostingErrorCode.UNPROCESSABLE, "Resume entries must address every open interrupt exactly.");
        }
    }

    private static HostingResumeRequest resumeRequest(AGUIPendingContinuation pending, List<AGUIResumeEntry> entries) {
        if (pending.type() == HostingContinuationType.APPROVAL) {
            ArrayList<HostingApprovalDecision> decisions = new ArrayList<>();
            for (AGUIResumeEntry entry : entries) {
                String approvalId = pending.approvalIdsByInterruptId().get(entry.interruptId());
                if (approvalId == null) {
                    throw new HostingException(
                            HostingErrorCode.UNPROCESSABLE, "Approval resume identifier is invalid.");
                }
                if (entry.status() == AGUIResumeStatus.CANCELLED) {
                    decisions.add(new HostingApprovalDecision(approvalId, false, "cancelled"));
                    continue;
                }
                StateValue.ObjectValue payload = requireObject(entry.payload(), "approval payload");
                boolean approved = requireBoolean(payload, "approved");
                String reason = approved ? null : optionalString(payload, "reason");
                decisions.add(new HostingApprovalDecision(
                        approvalId, approved, reason == null && !approved ? "rejected" : reason));
            }
            return new HostingResumeRequest(pending.token(), pending.type(), decisions, null);
        }
        AGUIResumeEntry entry = entries.getFirst();
        StateValue input = entry.status() == AGUIResumeStatus.CANCELLED
                ? StateValue.object(Map.of("cancelled", StateValue.bool(true)))
                : entry.payload();
        return new HostingResumeRequest(pending.token(), pending.type(), List.of(), input);
    }

    private static AGUIPendingContinuation pending(
            RunAgentInput input, com.microsoft.agents.hosting.HostingRouteKind kind, HostingOutcome outcome) {
        HostingContinuationDescriptor continuation =
                java.util.Objects.requireNonNull(outcome.continuation(), "continuation");
        ArrayList<AGUIInterrupt> interrupts = new ArrayList<>();
        LinkedHashMap<String, String> approvals = new LinkedHashMap<>();
        if (continuation.type() == HostingContinuationType.APPROVAL) {
            for (HostingApprovalRequest approval : continuation.approvalRequests()) {
                String id = opaqueId();
                approvals.put(id, approval.approvalId());
                interrupts.add(new AGUIInterrupt(
                        id,
                        "tool_call",
                        "Approval required for " + approval.toolName() + ".",
                        approval.callId(),
                        approvalSchema(),
                        continuation.expiresAt(),
                        extensionMetadata(continuation.type())));
            }
        } else {
            interrupts.add(new AGUIInterrupt(
                    opaqueId(),
                    "input_required",
                    "Additional input is required to continue.",
                    null,
                    null,
                    continuation.expiresAt(),
                    extensionMetadata(continuation.type())));
        }
        return new AGUIPendingContinuation(
                input.runId(),
                outcome.runId(),
                kind,
                continuation.token(),
                continuation.type(),
                interrupts,
                approvals,
                continuation.expiresAt());
    }

    private static StateValue.ObjectValue approvalSchema() {
        return StateValue.object(Map.of(
                "type",
                StateValue.string("object"),
                "properties",
                StateValue.object(Map.of(
                        "approved", StateValue.object(Map.of("type", StateValue.string("boolean"))),
                        "reason", StateValue.object(Map.of("type", StateValue.string("string"))))),
                "required",
                StateValue.array(List.of(StateValue.string("approved")))));
    }

    private static Map<String, StateValue> extensionMetadata(HostingContinuationType type) {
        return Map.of(
                "microsoft.agent-framework",
                StateValue.object(Map.of(
                        "processLocal",
                        StateValue.bool(true),
                        "oneTime",
                        StateValue.bool(true),
                        "continuationType",
                        StateValue.string(type.value()),
                        "crossProcess",
                        StateValue.bool(false))));
    }

    private AGUIHttpResponse capabilities(HostingHttpRequest request, AGUIHostingRoute route) {
        if (!options.capabilitiesEnabled()) {
            return errorResponse(request, 404, "NOT_FOUND", "Capability document is disabled.");
        }
        StateValue value = StateValue.object(Map.of(
                "protocol",
                StateValue.string("ag-ui"),
                "schemaVersion",
                StateValue.string(AGUIProtocol.TYPESCRIPT_CORE_VERSION),
                "endpoint",
                StateValue.string(route.path()),
                "transport",
                StateValue.object(Map.of(
                        "http", StateValue.bool(true),
                        "sse", StateValue.bool(true),
                        "websocket", StateValue.bool(false),
                        "lastEventIdReplay", StateValue.bool(false))),
                "resume",
                StateValue.object(Map.of(
                        "supported", StateValue.bool(route.descriptor().resumeSupported()),
                        "processLocal", StateValue.bool(true),
                        "oneTime", StateValue.bool(true),
                        "crossProcess", StateValue.bool(false)))));
        return AGUIHttpResponse.finite(
                200,
                corsHeaders(
                        request,
                        Map.of("Content-Type", List.of(CAPABILITY_MEDIA_TYPE), "Cache-Control", List.of("no-store"))),
                codec.encodeValue(value));
    }

    private AGUIHttpResponse preflight(HostingHttpRequest request, AGUIHostingRoute route) {
        security.validate(request, false);
        if (!transportOptions.corsEnabled()) {
            return errorResponse(request, 405, "CORS_DISABLED", "CORS preflight is disabled.");
        }
        String requestedMethod = request.firstHeader("access-control-request-method");
        if (!"POST".equalsIgnoreCase(requestedMethod)
                && !("GET".equalsIgnoreCase(requestedMethod)
                        && route.capabilitiesPath().equals(request.uri().getPath()))) {
            return errorResponse(request, 405, "METHOD_NOT_ALLOWED", "CORS method is not allowed.");
        }
        return AGUIHttpResponse.finite(
                204,
                corsHeaders(
                        request,
                        Map.of(
                                "Access-Control-Allow-Methods",
                                List.of("POST, GET, OPTIONS"),
                                "Access-Control-Allow-Headers",
                                List.of("authorization, content-type, traceparent, x-request-id"),
                                "Vary",
                                List.of("Origin"))),
                new byte[0]);
    }

    private Map<String, List<String>> corsHeaders(HostingHttpRequest request, Map<String, List<String>> headers) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>(headers);
        String origin = security.corsOrigin(request);
        if (origin != null) {
            result.put("Access-Control-Allow-Origin", List.of(origin));
            result.putIfAbsent("Vary", List.of("Origin"));
        }
        return Map.copyOf(result);
    }

    private AGUIHttpResponse errorResponse(HostingHttpRequest request, Throwable failure) {
        Throwable cause = com.microsoft.agents.core.RunHandles.unwrap(failure);
        if (cause instanceof HostingException hosting) {
            return errorResponse(
                    request,
                    status(hosting.error().code()),
                    hosting.error().code().value(),
                    hosting.error().message());
        }
        if (cause instanceof AGUIProtocolException protocol) {
            int status = protocol.code() == AGUIErrorCode.LIMIT_EXCEEDED ? 413 : 400;
            return errorResponse(request, status, protocol.code().name(), protocol.getMessage());
        }
        if (cause instanceof StorageConflictException) {
            return errorResponse(request, 409, "CONFLICT", "AG-UI thread state changed concurrently.");
        }
        return errorResponse(request, 500, "INTERNAL_ERROR", "AG-UI hosting failed.");
    }

    private AGUIHttpResponse errorResponse(HostingHttpRequest request, int status, String code, String message) {
        StateValue body = StateValue.object(Map.of(
                "error",
                StateValue.object(Map.of("code", StateValue.string(code), "message", StateValue.string(message)))));
        return AGUIHttpResponse.finite(
                status,
                corsHeaders(
                        request,
                        Map.of(
                                "Content-Type",
                                List.of(AGUIProtocol.JSON_MEDIA_TYPE),
                                "Cache-Control",
                                List.of("no-store"))),
                codec.encodeValue(body));
    }

    private void releaseAfterStartFailure(AGUIThreadKey key, RunAgentInput input, AcquiredThread acquired) {
        AGUIThreadState replacement =
                new AGUIThreadState(input.messages(), input.state(), null, acquired.pending(), Instant.now());
        threadStore.compareAndSetAsync(key, replacement, acquired.revision());
    }

    private void releaseAfterCancellation(
            AGUIThreadKey key, RunAgentInput input, AcquiredThread acquired, AGUIThreadAccumulator accumulator) {
        AGUIThreadState replacement = new AGUIThreadState(
                accumulator.messages(), accumulator.state(), null, acquired.pending(), Instant.now());
        threadStore.compareAndSetAsync(key, replacement, acquired.revision());
    }

    private static void requireJsonContentType(HostingHttpRequest request) {
        String value = request.firstHeader("content-type");
        if (value == null || !AGUIProtocol.JSON_MEDIA_TYPE.equalsIgnoreCase(value.split(";", 2)[0].trim())) {
            throw new HostingException(
                    HostingErrorCode.UNSUPPORTED_MEDIA_TYPE, "AG-UI POST requires application/json.");
        }
    }

    private static void requireSseAccept(HostingHttpRequest request) {
        String value = request.firstHeader("accept");
        if (value == null
                || java.util.Arrays.stream(value.split(","))
                        .map(String::trim)
                        .map(part -> part.split(";", 2)[0])
                        .noneMatch(AGUIProtocol.SSE_MEDIA_TYPE::equalsIgnoreCase)) {
            throw new HostingException(
                    HostingErrorCode.NOT_ACCEPTABLE, "AG-UI POST requires Accept: text/event-stream.");
        }
    }

    private static int status(HostingErrorCode code) {
        return code.httpStatus();
    }

    private static StateValue.ObjectValue requireObject(StateValue value, String name) {
        if (value instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw new HostingException(HostingErrorCode.UNPROCESSABLE, name + " must be an object.");
    }

    private static boolean requireBoolean(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value instanceof StateValue.BooleanValue bool) {
            return bool.value();
        }
        throw new HostingException(HostingErrorCode.UNPROCESSABLE, "Approval payload requires Boolean approved.");
    }

    private static String optionalString(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null) {
            return null;
        }

        if (value instanceof StateValue.StringValue string && !string.value().isBlank()) {
            return string.value();
        }
        throw new HostingException(HostingErrorCode.UNPROCESSABLE, "Approval reason must be a non-blank string.");
    }

    private static String stateString(StateValue value) {
        return value instanceof StateValue.StringValue string ? string.value() : null;
    }

    private static String opaqueId() {
        byte[] bytes = new byte[24];
        TOKENS.nextBytes(bytes);
        return "interrupt-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static BigDecimal now() {
        return BigDecimal.valueOf(Instant.now().toEpochMilli());
    }

    private record AcquiredThread(long revision, AGUIPendingContinuation pending) {}
}
