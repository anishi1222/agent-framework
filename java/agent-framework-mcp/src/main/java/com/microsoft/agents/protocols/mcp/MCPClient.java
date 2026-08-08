// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import com.microsoft.agents.tools.ToolUserException;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Mono;

/**
 * Provides a framework-owned asynchronous and synchronous MCP client facade.
 *
 * <p>The client owns its SDK client and transport. Closing it deterministically closes HTTP
 * sessions or terminates a configured stdio child process. SDK, Reactor, Jackson, and JSON-RPC types
 * never cross this public boundary.
 *
 * <p>Streamable HTTP reconnect behavior is transport-level and does not promise replay or retry of
 * application operations. This client disables event-id resumability because the pinned SDK does not
 * provide complete server replay semantics.
 */
public final class MCPClient implements AutoCloseable {
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {};

    private static final StateValue.ObjectValue OPEN_SCHEMA = StateValue.object(Map.of());

    private final MCPClientOptions options;

    private final McpJsonMapper jsonMapper;

    private final McpAsyncClient delegate;

    private final MCPEventPublisher events;

    private final Semaphore concurrency;

    private final AtomicReference<CompletableFuture<Void>> closeFuture = new AtomicReference<>();

    private final AtomicInteger samplingRequests = new AtomicInteger();

    private Runnable transportCleanup = () -> {};

    private MCPClient(MCPTransport transport, MCPClientOptions options) {
        Objects.requireNonNull(transport, "transport");
        this.options = Objects.requireNonNull(options, "options");
        jsonMapper = McpJsonDefaults.getMapper();
        events = new MCPEventPublisher(options.limits().maxEventBuffer());
        concurrency = new Semaphore(options.limits().maxConcurrentRequests());
        delegate = createDelegate(createTransport(transport));
    }

    /**
     * Creates a client with secure defaults.
     *
     * @param transport validated transport configuration
     * @return owned MCP client
     */
    public static MCPClient create(MCPTransport transport) {
        return new MCPClient(transport, MCPClientOptions.builder().build());
    }

    /**
     * Creates a client with explicit options.
     *
     * @param transport validated transport configuration
     * @param options client options
     * @return owned MCP client
     */
    public static MCPClient create(MCPTransport transport, MCPClientOptions options) {
        return new MCPClient(transport, options);
    }

    /**
     * Returns the single-subscriber bounded notification publisher.
     *
     * <p>When a subscriber cannot keep up and the configured event buffer fills, the publisher fails
     * explicitly instead of silently dropping notifications.
     *
     * @return hot notification publisher
     */
    public Flow.Publisher<MCPClientEvent> events() {
        return events;
    }

    /**
     * Explicitly initializes the connection and negotiates capabilities.
     *
     * @return initialization stage
     */
    public CompletionStage<MCPInitialization> initializeAsync() {
        return execute(
                "initialize",
                delegate.initialize(),
                this::toInitialization,
                options.initializationTimeout(),
                new DefaultRunCancellation());
    }

    /**
     * Initializes synchronously through the same execution path.
     *
     * @return initialization information
     */
    public MCPInitialization initialize() {
        return await(initializeAsync(), "MCP initialization");
    }

    /**
     * Reports whether capability negotiation has completed.
     *
     * @return initialization state
     */
    public boolean isInitialized() {
        return delegate.isInitialized();
    }

    /**
     * Sends an MCP ping.
     *
     * @return completion stage
     */
    public CompletionStage<Void> pingAsync() {
        return execute(
                        "ping",
                        delegate.ping(),
                        ignored -> Boolean.TRUE,
                        options.requestTimeout(),
                        new DefaultRunCancellation())
                .thenApply(ignored -> null);
    }

    /**
     * Lists one tool page.
     *
     * @param cursor opaque cursor, or {@code null} for the first page
     * @return page stage
     */
    public CompletionStage<MCPPage<MCPToolDescriptor>> listToolsAsync(String cursor) {
        return execute(
                "tools/list",
                delegate.listTools(cursor),
                result -> new MCPPage<>(
                        boundedMap(result.tools(), this::toToolDescriptor), cleanCursor(result.nextCursor())),
                options.requestTimeout(),
                new DefaultRunCancellation());
    }

    /**
     * Lists every tool with bounded cursor traversal.
     *
     * @return immutable tool list stage
     */
    public CompletionStage<List<MCPToolDescriptor>> listToolsAsync() {
        return collectPages(this::listToolsAsync);
    }

    /**
     * Lists every tool synchronously.
     *
     * @return immutable tools
     */
    public List<MCPToolDescriptor> listTools() {
        return await(listToolsAsync(), "MCP tools/list");
    }

    /**
     * Starts one explicitly cancellable MCP tool call.
     *
     * @param name exact remote tool name
     * @param arguments JSON-shaped arguments
     * @param callOptions timeout, cancellation, progress, and metadata
     * @return cancellable run handle
     */
    public RunHandle<MCPToolResult> startToolCall(
            String name, StateValue.ObjectValue arguments, MCPToolCallOptions callOptions) {
        String toolName = MCPValidation.nonBlank(name, "name");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(callOptions, "callOptions");
        MCPTypes.validateState(arguments, options.limits());

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(toJavaMap(callOptions.metadata()));
        if (callOptions.progressToken() != null) {
            metadata.put("progressToken", MCPTypes.toJava(callOptions.progressToken(), options.limits()));
        }
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder(toolName)
                .arguments(MCPTypes.toJavaMap(arguments, options.limits()))
                .meta(metadata.isEmpty() ? null : Map.copyOf(metadata))
                .build();
        return start(
                "tools/call",
                delegate.callTool(request),
                this::toToolResult,
                callOptions.timeout(),
                callOptions.cancellation());
    }

    /**
     * Calls a tool asynchronously with default timeout and cancellation.
     *
     * @param name exact remote tool name
     * @param arguments JSON-shaped arguments
     * @return tool-result stage
     */
    public CompletionStage<MCPToolResult> callToolAsync(String name, StateValue.ObjectValue arguments) {
        return startToolCall(
                        name,
                        arguments,
                        MCPToolCallOptions.builder(options.requestTimeout()).build())
                .resultAsync();
    }

    /**
     * Calls a tool asynchronously with explicit call options.
     *
     * @param name exact remote tool name
     * @param arguments JSON-shaped arguments
     * @param callOptions call options
     * @return tool-result stage
     */
    public CompletionStage<MCPToolResult> callToolAsync(
            String name, StateValue.ObjectValue arguments, MCPToolCallOptions callOptions) {
        return startToolCall(name, arguments, callOptions).resultAsync();
    }

    /**
     * Calls a tool synchronously.
     *
     * @param name exact remote tool name
     * @param arguments JSON-shaped arguments
     * @return terminal tool result
     */
    public MCPToolResult callTool(String name, StateValue.ObjectValue arguments) {
        return RunHandles.await(
                startToolCall(
                        name,
                        arguments,
                        MCPToolCallOptions.builder(options.requestTimeout()).build()),
                "MCP tools/call");
    }

    /**
     * Lists one prompt page.
     *
     * @param cursor opaque cursor, or {@code null}
     * @return prompt page stage
     */
    public CompletionStage<MCPPage<MCPPromptDescriptor>> listPromptsAsync(String cursor) {
        return execute(
                "prompts/list",
                delegate.listPrompts(cursor),
                result -> new MCPPage<>(
                        boundedMap(result.prompts(), this::toPromptDescriptor), cleanCursor(result.nextCursor())),
                options.requestTimeout(),
                new DefaultRunCancellation());
    }

    /**
     * Lists every prompt with bounded cursor traversal.
     *
     * @return immutable prompt list stage
     */
    public CompletionStage<List<MCPPromptDescriptor>> listPromptsAsync() {
        return collectPages(this::listPromptsAsync);
    }

    /**
     * Resolves one prompt.
     *
     * @param name exact prompt name
     * @param arguments string prompt arguments
     * @return prompt-result stage
     */
    public CompletionStage<MCPPromptResult> getPromptAsync(String name, Map<String, String> arguments) {
        String promptName = MCPValidation.nonBlank(name, "name");
        Map<String, String> safeArguments = MCPValidation.copyMap(arguments, "arguments");
        Map<String, Object> sdkArguments = new LinkedHashMap<>(safeArguments);
        McpSchema.GetPromptRequest request = McpSchema.GetPromptRequest.builder(promptName)
                .arguments(Map.copyOf(sdkArguments))
                .build();
        return execute(
                "prompts/get",
                delegate.getPrompt(request),
                this::toPromptResult,
                options.requestTimeout(),
                new DefaultRunCancellation());
    }

    /**
     * Lists one resource page.
     *
     * @param cursor opaque cursor, or {@code null}
     * @return resource page stage
     */
    public CompletionStage<MCPPage<MCPResourceDescriptor>> listResourcesAsync(String cursor) {
        return execute(
                "resources/list",
                delegate.listResources(cursor),
                result -> new MCPPage<>(
                        boundedMap(result.resources(), this::toResourceDescriptor), cleanCursor(result.nextCursor())),
                options.requestTimeout(),
                new DefaultRunCancellation());
    }

    /**
     * Lists every resource with bounded cursor traversal.
     *
     * @return immutable resource list stage
     */
    public CompletionStage<List<MCPResourceDescriptor>> listResourcesAsync() {
        return collectPages(this::listResourcesAsync);
    }

    /**
     * Lists one resource-template page.
     *
     * @param cursor opaque cursor, or {@code null}
     * @return template page stage
     */
    public CompletionStage<MCPPage<MCPResourceTemplateDescriptor>> listResourceTemplatesAsync(String cursor) {
        return execute(
                "resources/templates/list",
                delegate.listResourceTemplates(cursor),
                result -> new MCPPage<>(
                        boundedMap(result.resourceTemplates(), this::toResourceTemplateDescriptor),
                        cleanCursor(result.nextCursor())),
                options.requestTimeout(),
                new DefaultRunCancellation());
    }

    /**
     * Lists every resource template with bounded cursor traversal.
     *
     * @return immutable template list stage
     */
    public CompletionStage<List<MCPResourceTemplateDescriptor>> listResourceTemplatesAsync() {
        return collectPages(this::listResourceTemplatesAsync);
    }

    /**
     * Reads one resource.
     *
     * @param uri absolute resource URI
     * @return resource contents stage
     */
    public CompletionStage<MCPReadResourceResult> readResourceAsync(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if (!uri.isAbsolute()) {
            return CompletableFuture.failedFuture(new ValidationException("resource uri must be absolute."));
        }
        McpSchema.ReadResourceRequest request =
                McpSchema.ReadResourceRequest.builder(uri.toString()).build();
        return execute(
                "resources/read",
                delegate.readResource(request),
                result -> new MCPReadResourceResult(
                        boundedMap(result.contents(), this::toResourceContents), toStateMap(result.meta())),
                options.requestTimeout(),
                new DefaultRunCancellation());
    }

    /**
     * Subscribes to updates for one resource.
     *
     * @param uri absolute resource URI
     * @return completion stage
     */
    public CompletionStage<Void> subscribeResourceAsync(URI uri) {
        requireAbsoluteUri(uri, "resource");
        return execute(
                        "resources/subscribe",
                        delegate.subscribeResource(McpSchema.SubscribeRequest.builder(uri.toString())
                                        .build())
                                .thenReturn(Boolean.TRUE),
                        Function.identity(),
                        options.requestTimeout(),
                        new DefaultRunCancellation())
                .thenApply(ignored -> null);
    }

    /**
     * Removes a resource subscription.
     *
     * @param uri absolute resource URI
     * @return completion stage
     */
    public CompletionStage<Void> unsubscribeResourceAsync(URI uri) {
        requireAbsoluteUri(uri, "resource");
        return execute(
                        "resources/unsubscribe",
                        delegate.unsubscribeResource(McpSchema.UnsubscribeRequest.builder(uri.toString())
                                        .build())
                                .thenReturn(Boolean.TRUE),
                        Function.identity(),
                        options.requestTimeout(),
                        new DefaultRunCancellation())
                .thenApply(ignored -> null);
    }

    /**
     * Adds a root and notifies the connected server.
     *
     * @param root root descriptor
     * @return completion stage
     */
    public CompletionStage<Void> addRootAsync(MCPRoot root) {
        Objects.requireNonNull(root, "root");
        return execute(
                        "roots/add",
                        delegate.addRoot(toSdkRoot(root)).thenReturn(Boolean.TRUE),
                        Function.identity(),
                        options.requestTimeout(),
                        new DefaultRunCancellation())
                .thenApply(ignored -> null);
    }

    /**
     * Removes a root and notifies the connected server.
     *
     * @param uri exact root URI
     * @return completion stage
     */
    public CompletionStage<Void> removeRootAsync(URI uri) {
        requireAbsoluteUri(uri, "root");
        return execute(
                        "roots/remove",
                        delegate.removeRoot(uri.toString()).thenReturn(Boolean.TRUE),
                        Function.identity(),
                        options.requestTimeout(),
                        new DefaultRunCancellation())
                .thenApply(ignored -> null);
    }

    /**
     * Sets the minimum server logging level.
     *
     * @param level logging level
     * @return completion stage
     */
    public CompletionStage<Void> setLoggingLevelAsync(MCPLogLevel level) {
        Objects.requireNonNull(level, "level");
        return execute(
                        "logging/setLevel",
                        delegate.setLoggingLevel(McpSchema.LoggingLevel.valueOf(level.name()))
                                .thenReturn(Boolean.TRUE),
                        Function.identity(),
                        options.requestTimeout(),
                        new DefaultRunCancellation())
                .thenApply(ignored -> null);
    }

    /**
     * Discovers remote tools and adapts them into framework {@link FunctionTool} instances.
     *
     * <p>Names are normalized to a conservative ASCII identifier set. Collisions are assigned stable
     * suffixes after sorting by exact remote name, while each handler remains bound to the exact
     * remote name.
     *
     * @param prefix optional service prefix used for discoverability
     * @return immutable adapted tools stage
     */
    public CompletionStage<List<FunctionTool>> asFunctionToolsAsync(String prefix) {
        String normalizedPrefix = prefix == null || prefix.isBlank() ? "" : normalizeName(prefix, 64);
        return listToolsAsync().thenApply(tools -> adaptTools(tools, normalizedPrefix));
    }

    /**
     * Gracefully closes the owned SDK client and transport.
     *
     * @return close stage
     */
    public CompletionStage<Void> closeAsync() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (!closeFuture.compareAndSet(null, result)) {
            return Objects.requireNonNull(closeFuture.get()).minimalCompletionStage();
        }
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        delegate.closeGracefully()
                .timeout(options.closeTimeout())
                .doOnError(closeFailure::set)
                .doFinally(ignored -> Thread.startVirtualThread(() -> {
                    events.close();
                    try {
                        transportCleanup.run();
                    } catch (RuntimeException failure) {
                        closeFailure.compareAndSet(null, failure);
                    }
                    Throwable failure = closeFailure.get();
                    if (failure == null) {
                        result.complete(null);
                    } else {
                        result.completeExceptionally(failure);
                    }
                }))
                .subscribe(ignored -> {}, ignored -> {});
        return result.minimalCompletionStage();
    }

    /**
     * Closes synchronously and preserves interruption.
     */
    @Override
    public void close() {
        await(closeAsync().thenApply(ignored -> Boolean.TRUE), "MCP client close");
    }

    private McpAsyncClient createDelegate(McpClientTransport transport) {
        McpSchema.ClientCapabilities.RootCapabilities rootCapabilities =
                options.roots().isEmpty()
                        ? null
                        : McpSchema.ClientCapabilities.RootCapabilities.builder()
                                .listChanged(true)
                                .build();
        McpSchema.ClientCapabilities.Sampling sampling =
                options.samplingHandler() == null ? null : new McpSchema.ClientCapabilities.Sampling();
        McpSchema.ClientCapabilities.Elicitation.Form form =
                options.formElicitationHandler() == null ? null : new McpSchema.ClientCapabilities.Elicitation.Form();
        McpSchema.ClientCapabilities.Elicitation.Url url =
                options.urlElicitationHandler() == null ? null : new McpSchema.ClientCapabilities.Elicitation.Url();
        McpSchema.ClientCapabilities.Elicitation elicitation =
                form == null && url == null ? null : new McpSchema.ClientCapabilities.Elicitation(form, url);
        McpSchema.ClientCapabilities capabilities =
                new McpSchema.ClientCapabilities(null, rootCapabilities, sampling, elicitation);

        McpClient.AsyncSpec spec = McpClient.async(transport)
                .requestTimeout(options.requestTimeout())
                .initializationTimeout(options.initializationTimeout())
                .clientInfo(McpSchema.Implementation.builder(options.clientName(), options.clientVersion())
                        .build())
                .capabilities(capabilities)
                .roots(options.roots().stream().map(this::toSdkRoot).toList())
                .toolsChangeConsumer(
                        tools -> emit(new MCPClientEvent.ToolsChanged(boundedMap(tools, this::toToolDescriptor))))
                .resourcesChangeConsumer(resources ->
                        emit(new MCPClientEvent.ResourcesChanged(boundedMap(resources, this::toResourceDescriptor))))
                .resourcesUpdateConsumer(contents ->
                        emit(new MCPClientEvent.ResourcesUpdated(boundedMap(contents, this::toResourceContents))))
                .promptsChangeConsumer(prompts ->
                        emit(new MCPClientEvent.PromptsChanged(boundedMap(prompts, this::toPromptDescriptor))))
                .loggingConsumer(notification -> emit(new MCPClientEvent.Log(
                        MCPLogLevel.valueOf(notification.level().name()),
                        notification.logger(),
                        MCPRedactor.redact(notification.data()))))
                .progressConsumer(notification -> emit(new MCPClientEvent.Progress(
                        MCPTypes.toState(notification.progressToken(), options.limits()),
                        notification.progress(),
                        notification.total(),
                        notification.message())))
                .elicitationCompleteConsumer(
                        notification -> emit(new MCPClientEvent.ElicitationCompleted(notification.elicitationId())))
                .enableCallToolSchemaCaching(true);
        if (options.samplingHandler() != null) {
            spec.sampling(request -> Mono.fromCompletionStage(
                            options.samplingHandler().sampleAsync(toSamplingRequest(request)))
                    .map(this::toSdkSamplingResult));
        }
        if (options.formElicitationHandler() != null) {
            spec.elicitation(request -> Mono.fromCompletionStage(options.formElicitationHandler()
                            .elicitAsync(new MCPElicitationRequest.Form(
                                    request.message(),
                                    MCPTypes.toStateObject(request.requestedSchema(), options.limits()))))
                    .map(this::toSdkElicitationResult));
        }
        if (options.urlElicitationHandler() != null) {
            spec.urlElicitation(request -> Mono.fromCompletionStage(options.urlElicitationHandler()
                            .elicitAsync(new MCPElicitationRequest.Url(
                                    request.message(), URI.create(request.url()), request.elicitationId())))
                    .map(this::toSdkElicitationResult));
        }
        return spec.build();
    }

    private McpClientTransport createTransport(MCPTransport transport) {
        if (transport instanceof MCPStdioTransport stdio) {
            return new SecureStdioClientTransport(stdio, jsonMapper, options.limits());
        }
        if (transport instanceof MCPStreamableHTTPTransport http) {
            URI endpoint = http.endpoint();
            URI base;
            try {
                base = new URI(endpoint.getScheme(), null, endpoint.getHost(), endpoint.getPort(), "/", null, null);
            } catch (URISyntaxException exception) {
                throw new ValidationException("HTTP endpoint could not be normalized.", exception);
            }
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
            http.headers().forEach(requestBuilder::header);
            BoundedHttpClientBuilder clientBuilder =
                    new BoundedHttpClientBuilder(options.limits().maxPayloadBytes());
            clientBuilder.followRedirects(Redirect.NEVER);
            transportCleanup = () -> clientBuilder.closeBuiltClient(options.closeTimeout());
            return HttpClientStreamableHttpTransport.builder(base.toString())
                    .endpoint(endpoint.getRawPath())
                    .requestBuilder(requestBuilder)
                    .clientBuilder(clientBuilder)
                    .connectTimeout(http.connectTimeout())
                    .resumableStreams(false)
                    .openConnectionOnStartup(false)
                    .build();
        }
        throw new ValidationException("Unsupported MCP transport configuration '"
                + transport.getClass().getName() + "'.");
    }

    private <R, T> CompletionStage<T> execute(
            String operation,
            Mono<R> operationPublisher,
            Function<R, T> mapper,
            Duration timeout,
            RunCancellation cancellation) {
        return start(operation, operationPublisher, mapper, timeout, cancellation)
                .resultAsync();
    }

    private <R, T> RunHandle<T> start(
            String operation,
            Mono<R> operationPublisher,
            Function<R, T> mapper,
            Duration timeout,
            RunCancellation cancellation) {
        Objects.requireNonNull(operationPublisher, "operationPublisher");
        Objects.requireNonNull(mapper, "mapper");
        MCPValidation.positive(timeout, "timeout");
        Objects.requireNonNull(cancellation, "cancellation");
        RunHandleSource<T> source = new RunHandleSource<>(cancellation);
        if (source.isTerminal()) {
            return source.handle();
        }
        if (closeFuture.get() != null) {
            source.tryFail(new MCPException("MCP client is closed. Create a new client before " + operation + "."));
            return source.handle();
        }
        if (!concurrency.tryAcquire()) {
            source.tryFail(new MCPException("MCP concurrent request limit "
                    + options.limits().maxConcurrentRequests()
                    + " was reached; retry after an in-flight operation completes."));
            return source.handle();
        }

        AtomicBoolean released = new AtomicBoolean();
        AtomicReference<BaseSubscriber<R>> subscriberReference = new AtomicReference<>();
        var registration = RunCancellations.register(source.cancellation(), () -> {
            BaseSubscriber<R> active = subscriberReference.get();
            if (active != null) {
                active.cancel();
            }
        });
        BaseSubscriber<R> subscriber = new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                if (source.isTerminal()) {
                    cancel();
                    return;
                }
                request(1);
            }

            @Override
            protected void hookOnNext(R result) {
                try {
                    source.tryComplete(mapper.apply(result));
                } catch (RuntimeException failure) {
                    source.tryFail(mapFailure(operation, failure));
                }
            }

            @Override
            protected void hookOnError(Throwable failure) {
                source.tryFail(mapFailure(operation, failure));
            }

            @Override
            protected void hookOnComplete() {
                if (!source.isTerminal()) {
                    source.tryFail(new MCPException("MCP operation '" + operation + "' completed without a result."));
                }
            }
        };
        subscriberReference.set(subscriber);
        if (source.isTerminal()) {
            subscriber.cancel();
        }
        operationPublisher.timeout(timeout).subscribe(subscriber);
        source.handle().resultAsync().whenComplete((ignored, failure) -> {
            registration.close();
            subscriber.cancel();
            release(released);
        });
        return source.handle();
    }

    private void release(AtomicBoolean released) {
        if (released.compareAndSet(false, true)) {
            concurrency.release();
        }
    }

    private RuntimeException mapFailure(String operation, Throwable failure) {
        Throwable cause = RunHandles.unwrap(failure);
        if (cause instanceof com.microsoft.agents.core.RunCancelledException cancelled) {
            return cancelled;
        }
        if (cause instanceof MCPException mcpException) {
            return mcpException;
        }
        if (cause instanceof ValidationException validationException) {
            return validationException;
        }
        if (cause instanceof McpError protocol) {
            int code = protocol.getJsonRpcError().code();
            String message = MCPRedactor.redact(protocol.getJsonRpcError().message());
            return new MCPProtocolException(
                    code,
                    operation,
                    "MCP operation '" + operation + "' failed: " + message
                            + " Check the request and peer capabilities.",
                    cause);
        }
        if (cause instanceof TimeoutException
                || cause.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("timeout")) {
            return new MCPException(
                    "MCP operation '" + operation
                            + "' timed out. Reduce the request scope or increase the configured timeout.",
                    cause);
        }
        return new MCPException(
                "MCP operation '" + operation + "' failed. Verify transport configuration and peer availability.",
                cause);
    }

    private Mono<Void> emit(MCPClientEvent event) {
        return Mono.fromRunnable(() -> events.emit(event));
    }

    private MCPInitialization toInitialization(McpSchema.InitializeResult result) {
        McpSchema.ServerCapabilities capabilities = result.capabilities();
        return new MCPInitialization(
                result.protocolVersion(),
                result.serverInfo().name(),
                result.serverInfo().version(),
                result.instructions(),
                new MCPServerCapabilities(
                        capabilities.tools() != null,
                        capabilities.tools() != null
                                && Boolean.TRUE.equals(capabilities.tools().listChanged()),
                        capabilities.prompts() != null,
                        capabilities.prompts() != null
                                && Boolean.TRUE.equals(capabilities.prompts().listChanged()),
                        capabilities.resources() != null,
                        capabilities.resources() != null
                                && Boolean.TRUE.equals(capabilities.resources().subscribe()),
                        capabilities.resources() != null
                                && Boolean.TRUE.equals(capabilities.resources().listChanged()),
                        capabilities.logging() != null,
                        capabilities.completions() != null),
                toStateMap(result.meta()));
    }

    private MCPToolDescriptor toToolDescriptor(McpSchema.Tool tool) {
        return new MCPToolDescriptor(
                tool.name(),
                tool.title(),
                tool.description(),
                MCPTypes.toStateObject(tool.inputSchema(), options.limits()),
                tool.outputSchema() == null ? null : MCPTypes.toStateObject(tool.outputSchema(), options.limits()),
                toStateMap(tool.meta()));
    }

    private MCPPromptDescriptor toPromptDescriptor(McpSchema.Prompt prompt) {
        List<MCPPromptArgument> arguments = prompt.arguments() == null
                ? List.of()
                : boundedMap(
                        prompt.arguments(),
                        argument -> new MCPPromptArgument(
                                argument.name(), argument.description(), Boolean.TRUE.equals(argument.required())));
        return new MCPPromptDescriptor(
                prompt.name(), prompt.title(), prompt.description(), arguments, toStateMap(prompt.meta()));
    }

    private MCPResourceDescriptor toResourceDescriptor(McpSchema.Resource resource) {
        return new MCPResourceDescriptor(
                absoluteUri(resource.uri(), "resource"),
                resource.name(),
                resource.title(),
                resource.description(),
                resource.mimeType(),
                resource.size(),
                toStateMap(resource.meta()));
    }

    private MCPResourceTemplateDescriptor toResourceTemplateDescriptor(McpSchema.ResourceTemplate template) {
        return new MCPResourceTemplateDescriptor(
                template.uriTemplate(),
                template.name(),
                template.title(),
                template.description(),
                template.mimeType(),
                toStateMap(template.meta()));
    }

    private MCPToolResult toToolResult(McpSchema.CallToolResult result) {
        List<MCPContent> content = boundedMap(result.content(), this::toContent);
        StateValue structured = result.structuredContent() == null
                ? null
                : MCPTypes.toState(result.structuredContent(), options.limits());
        return new MCPToolResult(content, Boolean.TRUE.equals(result.isError()), structured, toStateMap(result.meta()));
    }

    private MCPPromptResult toPromptResult(McpSchema.GetPromptResult result) {
        return new MCPPromptResult(
                result.description(),
                boundedMap(
                        result.messages(),
                        message -> new MCPPromptMessage(toRole(message.role()), toContent(message.content()))),
                toStateMap(result.meta()));
    }

    private MCPResourceContents toResourceContents(McpSchema.ResourceContents contents) {
        if (contents instanceof McpSchema.TextResourceContents text) {
            return new MCPResourceContents.Text(
                    absoluteUri(text.uri(), "resource"),
                    text.mimeType(),
                    checkedText(text.text()),
                    toStateMap(text.meta()));
        }
        if (contents instanceof McpSchema.BlobResourceContents binary) {
            return new MCPResourceContents.Binary(
                    absoluteUri(binary.uri(), "resource"),
                    binary.mimeType(),
                    decode(binary.blob()),
                    toStateMap(binary.meta()));
        }
        throw new MCPException("MCP peer returned an unsupported resource content type.");
    }

    private MCPContent toContent(McpSchema.Content content) {
        if (content instanceof McpSchema.TextContent text) {
            return new MCPContent.Text(checkedText(text.text()), toStateMap(text.meta()));
        }
        if (content instanceof McpSchema.ImageContent image) {
            return new MCPContent.Image(decode(image.data()), image.mimeType(), toStateMap(image.meta()));
        }
        if (content instanceof McpSchema.AudioContent audio) {
            return new MCPContent.Audio(decode(audio.data()), audio.mimeType(), toStateMap(audio.meta()));
        }
        if (content instanceof McpSchema.EmbeddedResource embedded) {
            return new MCPContent.EmbeddedResource(
                    toResourceContents(embedded.resource()), toStateMap(embedded.meta()));
        }
        if (content instanceof McpSchema.ResourceLink link) {
            return new MCPContent.ResourceLink(
                    absoluteUri(link.uri(), "resource link"),
                    link.name(),
                    link.title(),
                    link.description(),
                    link.mimeType(),
                    link.size(),
                    toStateMap(link.meta()));
        }
        throw new MCPException("MCP peer returned an unsupported content type.");
    }

    MCPSamplingRequest toSamplingRequest(McpSchema.CreateMessageRequest request) {
        int previousRequestCount =
                samplingRequests.getAndUpdate(count -> count == Integer.MAX_VALUE ? Integer.MAX_VALUE : count + 1);
        if (previousRequestCount >= options.maxSamplingRequests()) {
            throw new MCPException("MCP sampling request limit exceeded: configured maxSamplingRequests="
                    + options.maxSamplingRequests()
                    + " applies to the entire MCPClient lifetime. Create a new MCPClient to start a new sampling "
                    + "budget, or increase maxSamplingRequests only after reviewing the server's trust and cost "
                    + "limits.");
        }
        Map<String, Object> raw = jsonMapper.convertValue(request, MAP_TYPE);
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>(raw);
        parameters.remove("messages");
        parameters.remove("maxTokens");
        parameters.remove("systemPrompt");
        return new MCPSamplingRequest(
                boundedMap(
                        request.messages(),
                        message -> new MCPPromptMessage(toRole(message.role()), toContent(message.content()))),
                Math.min(request.maxTokens(), options.maxSamplingTokens()),
                request.systemPrompt(),
                MCPTypes.toStateObject(parameters, options.limits()));
    }

    private McpSchema.CreateMessageResult toSdkSamplingResult(MCPSamplingResult result) {
        Objects.requireNonNull(result, "sampling result");
        McpSchema.CreateMessageResult.Builder builder = McpSchema.CreateMessageResult.builder(
                toSdkRole(result.role()), toSdkContent(result.content()), result.model());
        if (result.stopReason() != null) {
            builder.stopReason(McpSchema.CreateMessageResult.StopReason.valueOf(
                    result.stopReason().name()));
        }
        return builder.build();
    }

    private McpSchema.ElicitResult toSdkElicitationResult(MCPElicitationResult result) {
        Objects.requireNonNull(result, "elicitation result");
        McpSchema.ElicitResult.Builder builder = McpSchema.ElicitResult.builder(
                McpSchema.ElicitResult.Action.valueOf(result.action().name()));
        if (result.content() != null) {
            builder.content(MCPTypes.toJavaMap(result.content(), options.limits()));
        }
        return builder.build();
    }

    private McpSchema.Content toSdkContent(MCPContent content) {
        if (content instanceof MCPContent.Text text) {
            return McpSchema.TextContent.builder(text.text())
                    .meta(toJavaMap(text.metadata()))
                    .build();
        }
        if (content instanceof MCPContent.Image image) {
            return McpSchema.ImageContent.builder(encode(image.data()), image.mediaType())
                    .meta(toJavaMap(image.metadata()))
                    .build();
        }
        if (content instanceof MCPContent.Audio audio) {
            return McpSchema.AudioContent.builder(encode(audio.data()), audio.mediaType())
                    .meta(toJavaMap(audio.metadata()))
                    .build();
        }
        if (content instanceof MCPContent.EmbeddedResource embedded) {
            return McpSchema.EmbeddedResource.builder(toSdkResourceContents(embedded.resource()))
                    .meta(toJavaMap(embedded.metadata()))
                    .build();
        }
        if (content instanceof MCPContent.ResourceLink link) {
            McpSchema.ResourceLink.Builder builder = McpSchema.ResourceLink.builder()
                    .uri(link.uri().toString())
                    .name(link.name())
                    .description(link.description())
                    .mimeType(link.mediaType())
                    .size(link.size())
                    .meta(toJavaMap(link.metadata()));
            if (link.title() != null) {
                builder.title(link.title());
            }
            return builder.build();
        }
        throw new MCPException("Unsupported framework MCP content type.");
    }

    private McpSchema.ResourceContents toSdkResourceContents(MCPResourceContents contents) {
        if (contents instanceof MCPResourceContents.Text text) {
            return McpSchema.TextResourceContents.builder(text.uri().toString(), text.text())
                    .mimeType(text.mediaType())
                    .meta(toJavaMap(text.metadata()))
                    .build();
        }
        if (contents instanceof MCPResourceContents.Binary binary) {
            return McpSchema.BlobResourceContents.builder(binary.uri().toString(), encode(binary.data()))
                    .mimeType(binary.mediaType())
                    .meta(toJavaMap(binary.metadata()))
                    .build();
        }
        throw new MCPException("Unsupported framework MCP resource content type.");
    }

    private List<FunctionTool> adaptTools(List<MCPToolDescriptor> descriptors, String normalizedPrefix) {
        List<MCPToolDescriptor> sorted = descriptors.stream()
                .sorted(Comparator.comparing(MCPToolDescriptor::name))
                .toList();
        Set<String> emittedNames = new HashSet<>();
        ArrayList<FunctionTool> result = new ArrayList<>(sorted.size());
        for (MCPToolDescriptor descriptor : sorted) {
            String base = normalizedPrefix.isEmpty()
                    ? normalizeName(descriptor.name(), 112)
                    : normalizeName(normalizedPrefix + "_" + descriptor.name(), 112);
            String localName = base;
            int suffix = 2;
            while (!emittedNames.add(localName)) {
                localName = normalizeName(base + "_" + suffix, 128);
                suffix++;
            }
            ToolMetadata metadata = new ToolMetadata(
                    localName,
                    descriptor.description(),
                    Set.of(ToolCapability.FUNCTION),
                    options.remoteToolApprovalMode(),
                    descriptor.inputSchema(),
                    descriptor.outputSchema() == null ? OPEN_SCHEMA : descriptor.outputSchema());
            result.add(FunctionTool.create(metadata, (context, arguments) -> {
                MCPToolCallOptions callOptions = MCPToolCallOptions.builder(options.requestTimeout())
                        .cancellation(context.cancellation())
                        .progressToken(StateValue.string(context.callId()))
                        .metadata(Map.of(
                                "com.microsoft.agents/callId",
                                StateValue.string(context.callId()),
                                "com.microsoft.agents/invocationId",
                                StateValue.string(context.invocationId().value())))
                        .build();
                return callToolAsync(descriptor.name(), arguments, callOptions)
                        .thenApply(remoteResult -> functionResult(descriptor, remoteResult));
            }));
        }
        return List.copyOf(result);
    }

    private StateValue functionResult(MCPToolDescriptor descriptor, MCPToolResult remoteResult) {
        if (remoteResult.error()) {
            String detail = remoteResult.text().isBlank()
                    ? "The remote server did not provide error details."
                    : remoteResult.text();
            throw new ToolUserException("Remote MCP tool '"
                    + descriptor.name()
                    + "' failed: "
                    + MCPRedactor.redact(detail)
                    + " Review the arguments and server prerequisites.");
        }
        if (remoteResult.structuredContent() != null) {
            return remoteResult.structuredContent();
        }
        if (descriptor.outputSchema() != null) {
            throw new ToolUserException("Remote MCP tool '"
                    + descriptor.name()
                    + "' declared an output schema but returned no structuredContent.");
        }
        List<StateValue> content =
                remoteResult.content().stream().map(this::contentToState).toList();
        return StateValue.object(Map.of("content", StateValue.array(content)));
    }

    private StateValue contentToState(MCPContent content) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        if (content instanceof MCPContent.Text text) {
            value.put("type", StateValue.string("text"));
            value.put("text", StateValue.string(text.text()));
        } else if (content instanceof MCPContent.Image image) {
            value.put("type", StateValue.string("image"));
            value.put("data", StateValue.string(encode(image.data())));
            value.put("mimeType", StateValue.string(image.mediaType()));
        } else if (content instanceof MCPContent.Audio audio) {
            value.put("type", StateValue.string("audio"));
            value.put("data", StateValue.string(encode(audio.data())));
            value.put("mimeType", StateValue.string(audio.mediaType()));
        } else if (content instanceof MCPContent.EmbeddedResource embedded) {
            value.put("type", StateValue.string("resource"));
            value.put("resource", resourceContentsToState(embedded.resource()));
        } else if (content instanceof MCPContent.ResourceLink link) {
            value.put("type", StateValue.string("resource_link"));
            value.put("uri", StateValue.string(link.uri().toString()));
            value.put("name", StateValue.string(link.name()));
        } else {
            throw new MCPException("Unsupported framework MCP content type.");
        }
        if (!content.metadata().isEmpty()) {
            value.put("_meta", StateValue.object(content.metadata()));
        }
        return StateValue.object(value);
    }

    private StateValue resourceContentsToState(MCPResourceContents contents) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put("uri", StateValue.string(contents.uri().toString()));
        if (contents.mediaType() != null) {
            value.put("mimeType", StateValue.string(contents.mediaType()));
        }
        if (contents instanceof MCPResourceContents.Text text) {
            value.put("text", StateValue.string(text.text()));
        } else if (contents instanceof MCPResourceContents.Binary binary) {
            value.put("blob", StateValue.string(encode(binary.data())));
        }
        return StateValue.object(value);
    }

    private <T> CompletionStage<List<T>> collectPages(Function<String, CompletionStage<MCPPage<T>>> fetcher) {
        CompletableFuture<List<T>> result = new CompletableFuture<>();
        collectPage(fetcher, null, new HashSet<>(), new ArrayList<>(), 0, result);
        return result.minimalCompletionStage();
    }

    private <T> void collectPage(
            Function<String, CompletionStage<MCPPage<T>>> fetcher,
            String cursor,
            Set<String> seen,
            ArrayList<T> items,
            int pageCount,
            CompletableFuture<List<T>> result) {
        if (pageCount >= options.limits().maxPages()) {
            result.completeExceptionally(new MCPException(
                    "MCP pagination exceeded " + options.limits().maxPages() + " pages."));
            return;
        }
        fetcher.apply(cursor).whenComplete((page, failure) -> {
            if (failure != null) {
                result.completeExceptionally(RunHandles.unwrap(failure));
                return;
            }
            if (items.size() + page.items().size() > options.limits().maxCollectionItems()) {
                result.completeExceptionally(new MCPException("MCP pagination exceeded aggregate item limit "
                        + options.limits().maxCollectionItems()
                        + "."));
                return;
            }
            items.addAll(page.items());
            String next = page.nextCursor();
            if (next == null) {
                result.complete(List.copyOf(items));
                return;
            }
            if (!seen.add(next)) {
                result.completeExceptionally(new MCPException("MCP peer repeated pagination cursor '" + next + "'."));
                return;
            }
            collectPage(fetcher, next, seen, items, pageCount + 1, result);
        });
    }

    private <S, T> List<T> boundedMap(List<S> values, Function<S, T> mapper) {
        Objects.requireNonNull(values, "values");
        if (values.size() > options.limits().maxCollectionItems()) {
            throw new MCPException("MCP peer returned more than "
                    + options.limits().maxCollectionItems()
                    + " items in one collection.");
        }
        return values.stream().map(mapper).toList();
    }

    private Map<String, StateValue> toStateMap(Map<String, ?> metadata) {
        return MCPTypes.toStateMap(metadata, options.limits());
    }

    private Map<String, Object> toJavaMap(Map<String, StateValue> metadata) {
        return MCPTypes.toJavaMap(StateValue.object(metadata), options.limits());
    }

    private McpSchema.Root toSdkRoot(MCPRoot root) {
        McpSchema.Root.Builder builder = McpSchema.Root.builder(root.uri().toString());
        if (root.name() != null) {
            builder.name(root.name());
        }
        return builder.build();
    }

    private String checkedText(String text) {
        Objects.requireNonNull(text, "text");
        MCPTypes.validateState(StateValue.string(text), options.limits());
        return text;
    }

    private byte[] decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if ((long) encoded.length() * 3 / 4 > options.limits().maxPayloadBytes()) {
            throw new MCPException("MCP base64 content exceeds the configured payload limit.");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length > options.limits().maxPayloadBytes()) {
                throw new MCPException("MCP decoded content exceeds the configured payload limit.");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new MCPException("MCP peer returned invalid base64 content.", exception);
        }
    }

    private static String encode(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    private static URI absoluteUri(String value, String kind) {
        try {
            URI uri = URI.create(MCPValidation.nonBlank(value, kind + " uri"));
            if (!uri.isAbsolute()) {
                throw new ValidationException(kind + " uri must be absolute.");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(kind + " uri is invalid.", exception);
        }
    }

    private static void requireAbsoluteUri(URI uri, String kind) {
        Objects.requireNonNull(uri, "uri");
        if (!uri.isAbsolute()) {
            throw new ValidationException(kind + " uri must be absolute.");
        }
    }

    private static MCPRole toRole(McpSchema.Role role) {
        return MCPRole.valueOf(role.name());
    }

    private static McpSchema.Role toSdkRole(MCPRole role) {
        return McpSchema.Role.valueOf(role.name());
    }

    private static String cleanCursor(String cursor) {
        if (cursor != null && cursor.isBlank()) {
            throw new MCPException("MCP peer returned a blank pagination cursor.");
        }
        return cursor;
    }

    private static String normalizeName(String value, int maximumLength) {
        String normalized = MCPValidation.nonBlank(value, "tool name")
                .replaceAll("[^A-Za-z0-9_-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^[_-]+|[_-]+$", "");
        if (normalized.isEmpty()) {
            normalized = "mcp_tool";
        }
        if (normalized.length() <= maximumLength) {
            return normalized;
        }
        String hash = shortHash(value);
        return normalized.substring(0, maximumLength - hash.length() - 1) + "_" + hash;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static <T> T await(CompletionStage<T> stage, String operation) {
        RunHandleSource<T> source = new RunHandleSource<>();
        stage.whenComplete((value, failure) -> {
            if (failure == null) {
                source.tryComplete(value);
            } else {
                source.tryFail(RunHandles.unwrap(failure));
            }
        });
        return RunHandles.await(source.handle(), operation);
    }
}
