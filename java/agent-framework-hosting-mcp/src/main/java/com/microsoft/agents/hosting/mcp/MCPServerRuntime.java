// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.ApprovalRequiredException;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.ErrorContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.UriContent;
import com.microsoft.agents.protocols.mcp.MCPException;
import com.microsoft.agents.protocols.mcp.MCPPromptResult;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.InvocationId;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolBindingException;
import com.microsoft.agents.tools.ToolInvocationContext;
import com.microsoft.agents.tools.ToolOutputValidationException;
import com.microsoft.agents.tools.ToolUserException;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

final class MCPServerRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(MCPServerRuntime.class);

    private final MCPServer definition;

    private final McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final Semaphore concurrency;

    private final Object admissionLock = new Object();

    private final Set<DefaultRunCancellation> activeCancellations = ConcurrentHashMap.newKeySet();

    private final AtomicReference<CompletableFuture<Void>> closeFuture = new AtomicReference<>();

    private McpAsyncServer server;

    private MCPServerRuntime(MCPServer definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
        concurrency = new Semaphore(definition.limits().maxConcurrentRequests());
    }

    static MCPServerHandle startStdio(MCPServer definition, McpServerTransportProvider provider) {
        MCPServerRuntime runtime = new MCPServerRuntime(definition);
        runtime.server = runtime.build(McpServer.async(provider));
        return new StdioHandle(runtime);
    }

    static MCPServerRuntime startHTTP(MCPServer definition, HttpServletStreamableServerTransportProvider provider) {
        MCPServerRuntime runtime = new MCPServerRuntime(definition);
        runtime.server = runtime.build(McpServer.async(provider));
        return runtime;
    }

    boolean isRunning() {
        return closeFuture.get() == null;
    }

    CompletionStage<Void> closeAsync() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        List<DefaultRunCancellation> cancellations;
        synchronized (admissionLock) {
            if (!closeFuture.compareAndSet(null, result)) {
                return Objects.requireNonNull(closeFuture.get()).minimalCompletionStage();
            }
            cancellations = List.copyOf(activeCancellations);
        }
        cancellations.forEach(DefaultRunCancellation::cancel);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        server.closeGracefully()
                .timeout(Duration.ofSeconds(5))
                .doOnError(closeFailure::set)
                .doFinally(ignored -> Thread.startVirtualThread(() -> {
                    executor.close();
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

    void close() {
        await(closeAsync());
    }

    private McpAsyncServer build(McpServer.AsyncSpecification<?> specification) {
        List<McpServerFeatures.AsyncToolSpecification> tools = new ArrayList<>();
        definition.tools().forEach(tool -> tools.add(toolSpecification(tool)));
        definition.agents().forEach(agent -> tools.add(agentSpecification(agent)));

        List<McpServerFeatures.AsyncResourceSpecification> resources =
                definition.resources().stream().map(this::resourceSpecification).toList();
        List<McpServerFeatures.AsyncPromptSpecification> prompts =
                definition.prompts().stream().map(this::promptSpecification).toList();

        McpSchema.ServerCapabilities capabilities = new McpSchema.ServerCapabilities(
                null,
                null,
                new McpSchema.ServerCapabilities.LoggingCapabilities(),
                prompts.isEmpty()
                        ? null
                        : McpSchema.ServerCapabilities.PromptCapabilities.builder()
                                .listChanged(false)
                                .build(),
                resources.isEmpty()
                        ? null
                        : McpSchema.ServerCapabilities.ResourceCapabilities.builder()
                                .subscribe(false)
                                .listChanged(false)
                                .build(),
                tools.isEmpty()
                        ? null
                        : McpSchema.ServerCapabilities.ToolCapabilities.builder()
                                .listChanged(false)
                                .build());
        return specification
                .serverInfo(definition.name(), definition.version())
                .instructions(definition.instructions())
                .requestTimeout(definition.callTimeout())
                .capabilities(capabilities)
                .strictToolNameValidation(true)
                .validateToolInputs(true)
                .tools(tools)
                .resources(resources)
                .prompts(prompts)
                .build();
    }

    private McpServerFeatures.AsyncToolSpecification toolSpecification(FunctionTool tool) {
        String exposedName = HostingMCPNames.normalize(tool.name());
        McpSchema.Tool sdkTool = McpSchema.Tool.builder(
                        exposedName, HostingMCPTypes.toJavaMap(tool.metadata().inputSchema(), definition.limits()))
                .description(tool.description())
                .outputSchema(HostingMCPTypes.toJavaMap(tool.metadata().outputSchema(), definition.limits()))
                .build();
        return new McpServerFeatures.AsyncToolSpecification(
                sdkTool, (exchange, request) -> invokeTool(exchange, request, tool));
    }

    private McpServerFeatures.AsyncToolSpecification agentSpecification(MCPAgentTool agentTool) {
        McpSchema.Tool sdkTool = McpSchema.Tool.builder(
                        agentTool.name(), HostingMCPTypes.toJavaMap(agentTool.inputSchema(), definition.limits()))
                .description(agentTool.description())
                .outputSchema(HostingMCPTypes.toJavaMap(agentTool.outputSchema(), definition.limits()))
                .build();
        return new McpServerFeatures.AsyncToolSpecification(
                sdkTool, (exchange, request) -> invokeAgent(exchange, request, agentTool));
    }

    private Mono<McpSchema.CallToolResult> invokeTool(
            McpAsyncServerExchange exchange, McpSchema.CallToolRequest request, FunctionTool tool) {
        if (tool.metadata().approvalMode() == ToolApprovalMode.ALWAYS_REQUIRE) {
            return Mono.just(errorResult(
                    "approval_required",
                    "Tool '"
                            + request.name()
                            + "' requires explicit approval. Approve it in the owning "
                            + "application before exposing it over MCP."));
        }
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        Admission admission = admit(cancellation);
        if (admission != Admission.ADMITTED) {
            return Mono.just(admissionError(admission));
        }
        AtomicBoolean permitReleased = new AtomicBoolean();
        String logicalRunId = exchange.sessionId() == null ? "mcp-" + UUID.randomUUID() : "mcp-" + exchange.sessionId();
        String callId = requestMetaString(request, "com.microsoft.agents/callId");
        if (callId == null) {
            callId = UUID.randomUUID().toString();
        }
        String correlatedCallId = callId;
        String invocationId = requestMetaString(request, "com.microsoft.agents/invocationId");
        if (invocationId == null) {
            invocationId = logicalRunId + ":" + correlatedCallId;
        }
        ToolInvocationContext context = new ToolInvocationContext(
                logicalRunId,
                correlatedCallId,
                new InvocationId(invocationId),
                cancellation,
                executor,
                Map.of("mcp.tool", StateValue.string(request.name())));
        StateValue.ObjectValue arguments;
        try {
            arguments = HostingMCPTypes.fromMap(
                    Objects.requireNonNullElse(request.arguments(), Map.of()), definition.limits());
        } catch (RuntimeException failure) {
            finishCancellable(cancellation, permitReleased);
            return Mono.just(errorResult(
                    "invalid_arguments", "Tool arguments exceed server limits or are not valid JSON values."));
        }
        Mono<McpSchema.CallToolResult> execution;
        try {
            CompletionStage<StateValue> stage = tool.invokeAsync(context, arguments);
            if (stage == null) {
                throw new IllegalStateException("Tool returned a null CompletionStage.");
            }
            stage.whenComplete((ignored, failure) -> {
                finishCancellable(cancellation, permitReleased);
            });
            execution = Mono.fromCompletionStage(stage)
                    .timeout(definition.callTimeout())
                    .doOnError(failure -> {
                        if (isTimeout(failure)) {
                            cancellation.cancel();
                        }
                    })
                    .map(value -> successResult(value, correlatedCallId));
        } catch (RuntimeException failure) {
            finishCancellable(cancellation, permitReleased);
            execution = Mono.error(failure);
        }
        return withProgress(exchange, request, execution)
                .onErrorResume(failure -> Mono.just(toolFailure(request.name(), failure)))
                .doOnCancel(cancellation::cancel);
    }

    private Mono<McpSchema.CallToolResult> invokeAgent(
            McpAsyncServerExchange exchange, McpSchema.CallToolRequest request, MCPAgentTool agentTool) {
        Object rawTask =
                request.arguments() == null ? null : request.arguments().get(agentTool.argumentName());
        if (!(rawTask instanceof String task) || task.isBlank()) {
            return Mono.just(errorResult(
                    "invalid_arguments",
                    "Agent tool argument '" + agentTool.argumentName() + "' must be a non-blank string."));
        }
        if (task.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > definition.limits().maxPayloadBytes()) {
            return Mono.just(errorResult("payload_limit", "Agent task exceeds the configured payload limit."));
        }
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        RunOptions runOptions = RunOptions.builder()
                .metadata(Map.of(
                        "mcp.tool", StateValue.string(request.name()),
                        "mcp.session", StateValue.string(Objects.requireNonNullElse(exchange.sessionId(), "stdio"))))
                .build();
        Mono<McpSchema.CallToolResult> execution =
                startAgent(agentTool.agent(), task, request.name(), runOptions, cancellation);
        return withProgress(exchange, request, execution).onErrorResume(failure -> {
            Throwable cause = RunHandles.unwrap(failure);
            if (cause instanceof ApprovalRequiredException) {
                return Mono.just(errorResult(
                        "input_required",
                        "Agent execution requires approval or additional input. "
                                + "This MCP adapter does not expose process-local "
                                + "continuation state."));
            }
            return Mono.just(toolFailure(request.name(), cause));
        });
    }

    private <T> Mono<McpSchema.CallToolResult> startAgent(
            Agent<T> agent, String task, String toolName, RunOptions runOptions, DefaultRunCancellation cancellation) {
        return Mono.defer(() -> {
            Admission admission = admit(cancellation);
            if (admission != Admission.ADMITTED) {
                return Mono.just(admissionError(admission));
            }
            AtomicBoolean permitReleased = new AtomicBoolean();
            RunHandle<AgentResponse<T>> handle;
            try {
                handle = agent.startRun(task, runOptions, cancellation);
            } catch (RuntimeException failure) {
                finishCancellable(cancellation, permitReleased);
                return Mono.error(failure);
            }
            handle.resultAsync().whenComplete((ignored, failure) -> {
                finishCancellable(cancellation, permitReleased);
            });
            return Mono.fromCompletionStage(handle.resultAsync())
                    .timeout(definition.callTimeout())
                    .doOnError(failure -> {
                        if (isTimeout(failure)) {
                            handle.cancel();
                        }
                    })
                    .doOnCancel(handle::cancel)
                    .map(response -> agentResult(response, toolName));
        });
    }

    private Mono<McpSchema.CallToolResult> withProgress(
            McpAsyncServerExchange exchange,
            McpSchema.CallToolRequest request,
            Mono<McpSchema.CallToolResult> execution) {
        Object token = request.progressToken();
        if (token == null) {
            return execution;
        }
        Mono<Void> started = exchange.progressNotification(McpSchema.ProgressNotification.builder(token, 0)
                        .total(1.0)
                        .message("MCP tool call started.")
                        .build())
                .onErrorResume(ignored -> Mono.empty());
        return started.then(execution)
                .flatMap(result -> exchange.progressNotification(McpSchema.ProgressNotification.builder(token, 1)
                                .total(1.0)
                                .message("MCP tool call completed.")
                                .build())
                        .onErrorResume(ignored -> Mono.empty())
                        .thenReturn(result));
    }

    private McpSchema.CallToolResult successResult(StateValue value, String callId) {
        Object structured = HostingMCPTypes.toJava(value, definition.limits());
        String text;
        if (value instanceof StateValue.StringValue string) {
            text = string.value();
        } else {
            try {
                text = jsonMapper.writeValueAsString(structured);
            } catch (IOException exception) {
                throw new MCPException("Unable to serialize framework tool output.", exception);
            }
        }
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > definition.limits().maxPayloadBytes()) {
            throw new MCPException("Framework tool output exceeds the configured payload limit.");
        }
        return McpSchema.CallToolResult.builder(
                        List.of(McpSchema.TextContent.builder(text).build()))
                .structuredContent(structured)
                .isError(false)
                .meta(Map.of("com.microsoft.agents/callId", callId))
                .build();
    }

    private McpSchema.CallToolResult agentResult(AgentResponse<?> response, String toolName) {
        ArrayList<McpSchema.Content> contents = new ArrayList<>();
        for (Message message : response.messages()) {
            if (Role.USER.equals(message.role())) {
                continue;
            }
            for (Content content : message.contents()) {
                McpSchema.Content converted = agentContent(content, toolName);
                if (converted != null) {
                    contents.add(converted);
                }
            }
        }
        if (contents.isEmpty()) {
            contents.add(McpSchema.TextContent.builder(response.text()).build());
        }
        if (contents.size() > definition.limits().maxCollectionItems()) {
            throw new MCPException("Agent response exceeds the configured content-item limit.");
        }
        StateValue.ObjectValue structured = StateValue.object(Map.of("text", StateValue.string(response.text())));
        return McpSchema.CallToolResult.builder(contents)
                .structuredContent(HostingMCPTypes.toJavaMap(structured, definition.limits()))
                .isError(false)
                .build();
    }

    private McpSchema.Content agentContent(Content content, String toolName) {
        if (content instanceof TextContent text) {
            return McpSchema.TextContent.builder(text.text())
                    .meta(stateMetadata(text.metadata()))
                    .build();
        }
        if (content instanceof DataContent data) {
            if (data.data().length > definition.limits().maxPayloadBytes()) {
                throw new MCPException("Agent binary output exceeds the configured payload limit.");
            }
            String encoded = Base64.getEncoder().encodeToString(data.data());
            if (data.mediaType().startsWith("image/")) {
                return McpSchema.ImageContent.builder(encoded, data.mediaType())
                        .meta(stateMetadata(data.metadata()))
                        .build();
            }
            if (data.mediaType().startsWith("audio/")) {
                return McpSchema.AudioContent.builder(encoded, data.mediaType())
                        .meta(stateMetadata(data.metadata()))
                        .build();
            }
            String uri = metadataString(data.metadata(), "uri");
            if (uri == null) {
                uri = "af://agent-output/" + toolName;
            }
            return McpSchema.EmbeddedResource.builder(McpSchema.BlobResourceContents.builder(uri, encoded)
                            .mimeType(data.mediaType())
                            .build())
                    .meta(stateMetadata(data.metadata()))
                    .build();
        }
        if (content instanceof UriContent uri) {
            String path = uri.uri().getPath();
            String resourceName =
                    path == null || path.isBlank() ? uri.uri().toString() : path.substring(path.lastIndexOf('/') + 1);
            McpSchema.ResourceLink.Builder builder = McpSchema.ResourceLink.builder()
                    .uri(uri.uri().toString())
                    .name(resourceName.isBlank() ? uri.uri().toString() : resourceName)
                    .meta(stateMetadata(uri.metadata()));
            if (uri.mediaType() != null) {
                builder.mimeType(uri.mediaType());
            }
            return builder.build();
        }
        if (content instanceof ErrorContent error) {
            return McpSchema.TextContent.builder("Agent content error: " + error.message())
                    .meta(stateMetadata(error.metadata()))
                    .build();
        }
        return null;
    }

    private McpServerFeatures.AsyncResourceSpecification resourceSpecification(MCPServerResource resource) {
        var descriptor = resource.descriptor();
        McpSchema.Resource sdkResource = McpSchema.Resource.builder(
                        descriptor.uri().toString(), descriptor.name())
                .title(descriptor.title())
                .description(descriptor.description())
                .mimeType(descriptor.mediaType())
                .size(descriptor.size())
                .meta(HostingMCPTypes.toJavaMap(StateValue.object(descriptor.metadata()), definition.limits()))
                .build();
        return new McpServerFeatures.AsyncResourceSpecification(sdkResource, (exchange, request) -> {
            URI uri = URI.create(request.uri());
            return invokeBounded("resource read", () -> resource.handler().readAsync(uri))
                    .map(result -> McpSchema.ReadResourceResult.builder(
                                    HostingMCPTypes.toSdkResourceContents(result, definition.limits()))
                            .meta(HostingMCPTypes.toJavaMap(StateValue.object(result.metadata()), definition.limits()))
                            .build())
                    .onErrorMap(failure ->
                            sanitizedHandlerFailure("Resource '" + descriptor.uri() + "' could not be read.", failure));
        });
    }

    private McpServerFeatures.AsyncPromptSpecification promptSpecification(MCPServerPrompt prompt) {
        McpSchema.Prompt sdkPrompt = McpSchema.Prompt.builder(prompt.name())
                .description(prompt.description())
                .arguments(prompt.arguments().stream()
                        .map(argument -> McpSchema.PromptArgument.builder(argument.name())
                                .description(argument.description())
                                .required(argument.required())
                                .build())
                        .toList())
                .build();
        return new McpServerFeatures.AsyncPromptSpecification(sdkPrompt, (exchange, request) -> {
            Map<String, String> arguments = new LinkedHashMap<>();
            if (request.arguments() != null) {
                request.arguments().forEach((name, value) -> {
                    if (!(value instanceof String string)) {
                        throw new ToolBindingException("Prompt arguments must be strings.");
                    }
                    arguments.put(name, string);
                });
            }
            return invokeBounded("prompt resolution", () -> prompt.handler().getAsync(Map.copyOf(arguments)))
                    .map(result -> promptResult(result))
                    .onErrorMap(failure ->
                            sanitizedHandlerFailure("Prompt '" + prompt.name() + "' could not be resolved.", failure));
        });
    }

    private <T> Mono<T> invokeBounded(String operation, Supplier<CompletionStage<T>> invocation) {
        return Mono.defer(() -> {
                    Admission admission = admit(null);
                    if (admission != Admission.ADMITTED) {
                        return Mono.error(new MCPException(
                                admission == Admission.CLOSING
                                        ? "MCP server is closing and cannot start " + operation + "."
                                        : "MCP server concurrent request limit was reached during "
                                                + operation
                                                + ". Retry after an in-flight request completes."));
                    }
                    AtomicBoolean permitReleased = new AtomicBoolean();
                    CompletionStage<T> stage;
                    try {
                        stage = Objects.requireNonNull(invocation.get(), operation + " CompletionStage");
                    } catch (RuntimeException failure) {
                        releasePermit(permitReleased);
                        return Mono.error(failure);
                    }
                    stage.whenComplete((ignored, failure) -> releasePermit(permitReleased));
                    return Mono.fromCompletionStage(stage);
                })
                .timeout(definition.callTimeout());
    }

    private void releasePermit(AtomicBoolean released) {
        if (released.compareAndSet(false, true)) {
            concurrency.release();
        }
    }

    private void finishCancellable(DefaultRunCancellation cancellation, AtomicBoolean released) {
        activeCancellations.remove(cancellation);
        releasePermit(released);
    }

    private Admission admit(DefaultRunCancellation cancellation) {
        synchronized (admissionLock) {
            if (closeFuture.get() != null) {
                return Admission.CLOSING;
            }
            if (!concurrency.tryAcquire()) {
                return Admission.LIMIT;
            }
            if (cancellation != null) {
                activeCancellations.add(cancellation);
            }
            return Admission.ADMITTED;
        }
    }

    private McpSchema.CallToolResult admissionError(Admission admission) {
        return admission == Admission.CLOSING
                ? errorResult("server_closing", "The MCP server is closing and cannot start another call.")
                : errorResult(
                        "concurrency_limit",
                        "The MCP server is at its concurrent call limit. Retry after an in-flight call completes.");
    }

    private enum Admission {
        ADMITTED,
        CLOSING,
        LIMIT
    }

    private static boolean isTimeout(Throwable failure) {
        Throwable cause = RunHandles.unwrap(failure);
        return cause instanceof TimeoutException
                || cause.getClass()
                        .getSimpleName()
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("timeout");
    }

    private McpSchema.GetPromptResult promptResult(MCPPromptResult result) {
        return McpSchema.GetPromptResult.builder(
                        HostingMCPTypes.toSdkPromptMessages(result.messages(), definition.limits()))
                .description(result.description())
                .meta(HostingMCPTypes.toJavaMap(StateValue.object(result.metadata()), definition.limits()))
                .build();
    }

    private McpSchema.CallToolResult toolFailure(String toolName, Throwable failure) {
        Throwable cause = RunHandles.unwrap(failure);
        if (cause instanceof ToolUserException || cause instanceof ToolBindingException) {
            return errorResult(
                    "invalid_arguments",
                    "Tool '"
                            + toolName
                            + "' rejected the request: "
                            + sanitize(cause.getMessage())
                            + " Correct the arguments and retry.");
        }
        if (cause instanceof ToolOutputValidationException) {
            return errorResult(
                    "output_validation_failed",
                    "Tool '" + toolName + "' returned data that does not satisfy its declared output schema.");
        }
        if (cause instanceof RunCancelledException) {
            return errorResult("cancelled", "Tool '" + toolName + "' was cancelled.");
        }
        if (cause.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("timeout")) {
            return errorResult(
                    "timeout",
                    "Tool '" + toolName + "' timed out. Reduce the request scope or increase the server call timeout.");
        }
        LOGGER.error("Unexpected MCP tool failure for {}: {}", toolName, sanitize(cause.getMessage()));
        return errorResult(
                "execution_failed",
                "Tool '"
                        + toolName
                        + "' failed. Verify server prerequisites and retry; internal details were withheld.");
    }

    private McpSchema.CallToolResult errorResult(String code, String message) {
        Map<String, Object> structured = Map.of("status", "error", "code", code, "message", message);
        return McpSchema.CallToolResult.builder(
                        List.of(McpSchema.TextContent.builder(message).build()))
                .isError(true)
                .structuredContent(structured)
                .build();
    }

    private Map<String, Object> stateMetadata(Map<String, StateValue> metadata) {
        return HostingMCPTypes.toJavaMap(StateValue.object(metadata), definition.limits());
    }

    private static String requestMetaString(McpSchema.CallToolRequest request, String name) {
        Object value = request.meta() == null ? null : request.meta().get(name);
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static String metadataString(Map<String, StateValue> metadata, String name) {
        StateValue value = metadata.get(name);
        return value instanceof StateValue.StringValue string ? string.value() : null;
    }

    private static RuntimeException sanitizedHandlerFailure(String message, Throwable failure) {
        return new MCPException(message, RunHandles.unwrap(failure));
    }

    private static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "request was rejected";
        }
        String result = message.replaceAll(
                "(?i)(authorization|api[_-]?key|token|secret|password)(\\s*[:=]\\s*)[^\\s,;]+", "$1$2<redacted>");
        return result.length() > 512 ? result.substring(0, 512) + "…" : result;
    }

    private static void await(CompletionStage<Void> stage) {
        try {
            stage.toCompletableFuture().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MCPException("MCP server close was interrupted.", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new MCPException("MCP server close failed.", RunHandles.unwrap(exception.getCause()));
        }
    }

    private static final class StdioHandle implements MCPServerHandle {
        private final MCPServerRuntime runtime;

        private StdioHandle(MCPServerRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        public boolean isRunning() {
            return runtime.isRunning();
        }

        @Override
        public CompletionStage<Void> closeAsync() {
            return runtime.closeAsync();
        }

        @Override
        public void close() {
            runtime.close();
        }
    }
}
