// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

import com.azure.ai.agents.persistent.MessagesAsyncClient;
import com.azure.ai.agents.persistent.PersistentAgentsAdministrationAsyncClient;
import com.azure.ai.agents.persistent.PersistentAgentsAsyncClient;
import com.azure.ai.agents.persistent.PersistentAgentsClientBuilder;
import com.azure.ai.agents.persistent.RunsAsyncClient;
import com.azure.ai.agents.persistent.ThreadsAsyncClient;
import com.azure.ai.agents.persistent.models.CodeInterpreterToolDefinition;
import com.azure.ai.agents.persistent.models.CodeInterpreterToolResource;
import com.azure.ai.agents.persistent.models.CreateAgentOptions;
import com.azure.ai.agents.persistent.models.CreateRunOptions;
import com.azure.ai.agents.persistent.models.FileSearchToolDefinition;
import com.azure.ai.agents.persistent.models.FileSearchToolResource;
import com.azure.ai.agents.persistent.models.FunctionDefinition;
import com.azure.ai.agents.persistent.models.FunctionToolDefinition;
import com.azure.ai.agents.persistent.models.ListSortOrder;
import com.azure.ai.agents.persistent.models.MessageAttachment;
import com.azure.ai.agents.persistent.models.MessageDeltaTextContent;
import com.azure.ai.agents.persistent.models.MessageRole;
import com.azure.ai.agents.persistent.models.MessageTextContent;
import com.azure.ai.agents.persistent.models.OpenApiAnonymousAuthDetails;
import com.azure.ai.agents.persistent.models.OpenApiFunctionDefinition;
import com.azure.ai.agents.persistent.models.OpenApiToolDefinition;
import com.azure.ai.agents.persistent.models.PersistentAgent;
import com.azure.ai.agents.persistent.models.PersistentAgentThread;
import com.azure.ai.agents.persistent.models.RequiredFunctionToolCall;
import com.azure.ai.agents.persistent.models.RequiredToolCall;
import com.azure.ai.agents.persistent.models.RunCompletionUsage;
import com.azure.ai.agents.persistent.models.StreamMessageUpdate;
import com.azure.ai.agents.persistent.models.StreamRequiredAction;
import com.azure.ai.agents.persistent.models.StreamThreadRunCreation;
import com.azure.ai.agents.persistent.models.StreamUpdate;
import com.azure.ai.agents.persistent.models.SubmitToolOutputsAction;
import com.azure.ai.agents.persistent.models.ThreadMessage;
import com.azure.ai.agents.persistent.models.ThreadRun;
import com.azure.ai.agents.persistent.models.ToolDefinition;
import com.azure.ai.agents.persistent.models.ToolOutput;
import com.azure.ai.agents.persistent.models.ToolResources;
import com.azure.ai.agents.persistent.models.UpdateAgentOptions;
import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.exception.ClientAuthenticationException;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.policy.ExponentialBackoffOptions;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.policy.HttpLogOptions;
import com.azure.core.http.policy.RetryOptions;
import com.azure.core.util.BinaryData;
import com.microsoft.agents.azure.AzureAuthenticationProvider;
import com.microsoft.agents.azure.AzureTokenRequest;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

final class PersistentSdkTransport implements PersistentTransport {
    private static final String AI_SCOPE = "https://ai.azure.com/.default";

    private final PersistentAgentsAdministrationAsyncClient administration;
    private final ThreadsAsyncClient threads;
    private final MessagesAsyncClient messages;
    private final RunsAsyncClient runs;

    private PersistentSdkTransport(PersistentAgentsAsyncClient client) {
        administration = client.getPersistentAgentsAdministrationAsyncClient();
        threads = client.getThreadsAsyncClient();
        messages = client.getMessagesAsyncClient();
        runs = client.getRunsAsyncClient();
    }

    static PersistentSdkTransport create(AzureAIPersistentClientOptions options) {
        return create(options, null);
    }

    static PersistentSdkTransport create(AzureAIPersistentClientOptions options, HttpClient httpClient) {
        Objects.requireNonNull(options, "options");
        RetryOptions retryOptions = new RetryOptions(new ExponentialBackoffOptions()
                .setMaxRetries(options.maxRetries())
                .setBaseDelay(Duration.ofMillis(200))
                .setMaxDelay(Duration.ofSeconds(5)));
        PersistentAgentsClientBuilder builder = new PersistentAgentsClientBuilder()
                .endpoint(options.endpoint().toString())
                .credential(tokenCredential(options.authenticationProvider()))
                .retryOptions(retryOptions)
                .httpLogOptions(new HttpLogOptions().setLogLevel(HttpLogDetailLevel.NONE));
        if (httpClient != null) {
            builder.httpClient(httpClient);
        }
        return new PersistentSdkTransport(builder.buildAsyncClient());
    }

    @Override
    public CompletionStage<PersistentAgentDefinition> createAgentAsync(
            PersistentAgentCreateRequest request, RunCancellation cancellation) {
        CreateAgentOptions sdk = new CreateAgentOptions(request.model())
                .setName(request.name())
                .setDescription(request.description())
                .setInstructions(request.instructions())
                .setTools(toTools(request.tools()))
                .setToolResources(toToolResources(request.tools()))
                .setMetadata(request.metadata());
        return stage(administration.createAgent(sdk), cancellation).thenApply(PersistentSdkTransport::agent);
    }

    @Override
    public CompletionStage<PersistentAgentDefinition> getAgentAsync(String agentId, RunCancellation cancellation) {
        return stage(administration.getAgent(nonBlank(agentId, "agentId")), cancellation)
                .thenApply(PersistentSdkTransport::agent);
    }

    @Override
    public CompletionStage<PersistentAgentDefinition> updateAgentAsync(
            String agentId, PersistentAgentCreateRequest request, RunCancellation cancellation) {
        UpdateAgentOptions sdk = new UpdateAgentOptions(nonBlank(agentId, "agentId"))
                .setModel(request.model())
                .setName(request.name())
                .setDescription(request.description())
                .setInstructions(request.instructions())
                .setTools(toTools(request.tools()))
                .setToolResources(toToolResources(request.tools()))
                .setMetadata(request.metadata());
        return stage(administration.updateAgent(sdk), cancellation).thenApply(PersistentSdkTransport::agent);
    }

    @Override
    public CompletionStage<Void> deleteAgentAsync(String agentId, RunCancellation cancellation) {
        return stage(administration.deleteAgent(nonBlank(agentId, "agentId")), cancellation);
    }

    @Override
    public CompletionStage<PersistentPage<PersistentAgentDefinition>> listAgentsAsync(
            int limit, String after, RunCancellation cancellation) {
        return page(
                administration
                        .listAgents(Math.min(limit + 1, 100), ListSortOrder.ASCENDING, after, null)
                        .map(PersistentSdkTransport::agent),
                limit,
                after,
                PersistentAgentDefinition::id,
                cancellation);
    }

    @Override
    public CompletionStage<PersistentThread> createThreadAsync(
            Map<String, String> metadata, RunCancellation cancellation) {
        return stage(threads.createThread(null, null, metadata), cancellation)
                .thenApply(PersistentSdkTransport::thread);
    }

    @Override
    public CompletionStage<PersistentThread> getThreadAsync(String threadId, RunCancellation cancellation) {
        return stage(threads.getThread(nonBlank(threadId, "threadId")), cancellation)
                .thenApply(PersistentSdkTransport::thread);
    }

    @Override
    public CompletionStage<Void> deleteThreadAsync(String threadId, RunCancellation cancellation) {
        return stage(threads.deleteThread(nonBlank(threadId, "threadId")), cancellation);
    }

    @Override
    public CompletionStage<PersistentMessage> createMessageAsync(
            String threadId,
            Role role,
            String text,
            List<PersistentAttachment> attachments,
            Map<String, String> metadata,
            RunCancellation cancellation) {
        return stage(
                        messages.createMessage(
                                nonBlank(threadId, "threadId"),
                                toMessageRole(role),
                                Objects.requireNonNull(text, "text"),
                                attachments.stream()
                                        .map(PersistentSdkTransport::attachment)
                                        .toList(),
                                metadata),
                        cancellation)
                .thenApply(PersistentSdkTransport::message);
    }

    @Override
    public CompletionStage<PersistentPage<PersistentMessage>> listMessagesAsync(
            String threadId, String runId, int limit, String after, RunCancellation cancellation) {
        return page(
                messages.listMessages(
                                nonBlank(threadId, "threadId"),
                                runId,
                                Math.min(limit + 1, 100),
                                ListSortOrder.ASCENDING,
                                after,
                                null)
                        .map(PersistentSdkTransport::message),
                limit,
                after,
                PersistentMessage::id,
                cancellation);
    }

    @Override
    public CompletionStage<PersistentRun> createRunAsync(PersistentRunRequest request, RunCancellation cancellation) {
        return stage(runs.createRun(runOptions(request)), cancellation).thenApply(PersistentSdkTransport::run);
    }

    @Override
    public CompletionStage<PersistentRun> getRunAsync(String threadId, String runId, RunCancellation cancellation) {
        return stage(runs.getRun(nonBlank(threadId, "threadId"), nonBlank(runId, "runId")), cancellation)
                .thenApply(PersistentSdkTransport::run);
    }

    @Override
    public CompletionStage<PersistentPage<PersistentRun>> listRunsAsync(
            String threadId, int limit, String after, RunCancellation cancellation) {
        return page(
                runs.listRuns(
                                nonBlank(threadId, "threadId"),
                                Math.min(limit + 1, 100),
                                ListSortOrder.ASCENDING,
                                after,
                                null)
                        .map(PersistentSdkTransport::run),
                limit,
                after,
                PersistentRun::id,
                cancellation);
    }

    @Override
    public CompletionStage<PersistentRun> cancelRunAsync(String threadId, String runId, RunCancellation cancellation) {
        return stage(runs.cancelRun(nonBlank(threadId, "threadId"), nonBlank(runId, "runId")), cancellation)
                .thenApply(PersistentSdkTransport::run);
    }

    @Override
    public CompletionStage<PersistentRun> submitToolOutputsAsync(
            String threadId, String runId, List<PersistentToolOutput> outputs, RunCancellation cancellation) {
        List<ToolOutput> sdkOutputs = outputs.stream()
                .map(output ->
                        new ToolOutput().setToolCallId(output.toolCallId()).setOutput(output.output()))
                .toList();
        return stage(
                        runs.submitToolOutputsToRun(
                                nonBlank(threadId, "threadId"), nonBlank(runId, "runId"), sdkOutputs),
                        cancellation)
                .thenApply(PersistentSdkTransport::run);
    }

    @Override
    public Flow.Publisher<PersistentRunEvent> createRunStreaming(
            PersistentRunRequest request, RunCancellation cancellation) {
        Flux<PersistentRunEvent> events = runs.createRunStreaming(runOptions(request))
                .map(PersistentSdkTransport::event)
                .doOnCancel(cancellation::cancel)
                .onErrorMap(PersistentSdkTransport::mapFailure);
        return JdkFlowAdapter.publisherToFlowPublisher(events);
    }

    private static CreateRunOptions runOptions(PersistentRunRequest request) {
        return new CreateRunOptions(request.threadId(), request.agentId())
                .setAdditionalInstructions(request.additionalInstructions())
                .setMaxPromptTokens(request.maxPromptTokens())
                .setMaxCompletionTokens(request.maxCompletionTokens())
                .setMetadata(request.metadata());
    }

    private static TokenCredential tokenCredential(AzureAuthenticationProvider provider) {
        return context -> Mono.fromCompletionStage(provider.getTokenAsync(
                        new AzureTokenRequest(context.getScopes(), context.getTenantId()),
                        new DefaultRunCancellation()))
                .map(token -> new AccessToken(
                        token.token(),
                        java.time.OffsetDateTime.ofInstant(token.expiresAt(), java.time.ZoneOffset.UTC)));
    }

    private static List<ToolDefinition> toTools(List<PersistentAgentTool> tools) {
        if (tools.isEmpty()) {
            return null;
        }
        ArrayList<ToolDefinition> result = new ArrayList<>(tools.size());
        for (PersistentAgentTool tool : tools) {
            if (!tool.supported()) {
                throw configuration(tool.limitation());
            }
            result.add(
                    switch (tool.kind()) {
                        case CODE_INTERPRETER -> new CodeInterpreterToolDefinition();
                        case FILE_SEARCH -> new FileSearchToolDefinition();
                        case FUNCTION ->
                            new FunctionToolDefinition(new FunctionDefinition(
                                            nonBlank(tool.name(), "function name"),
                                            BinaryData.fromString(nonBlank(tool.schemaJson(), "function schemaJson")))
                                    .setDescription(tool.description()));
                        case OPENAPI ->
                            new OpenApiToolDefinition(new OpenApiFunctionDefinition(
                                            nonBlank(tool.name(), "OpenAPI name"),
                                            BinaryData.fromString(nonBlank(tool.schemaJson(), "OpenAPI schemaJson")),
                                            new OpenApiAnonymousAuthDetails())
                                    .setDescription(tool.description()));
                        case MCP, UNSUPPORTED ->
                            throw configuration(
                                    tool.limitation() == null
                                            ? "The persistent SDK does not support this tool."
                                            : tool.limitation());
                    });
        }
        return List.copyOf(result);
    }

    private static ToolResources toToolResources(List<PersistentAgentTool> tools) {
        ToolResources resources = new ToolResources();
        boolean present = false;
        for (PersistentAgentTool tool : tools) {
            if (tool.resourceIds().isEmpty()) {
                continue;
            }
            switch (tool.kind()) {
                case CODE_INTERPRETER -> {
                    resources.setCodeInterpreter(new CodeInterpreterToolResource().setFileIds(tool.resourceIds()));
                    present = true;
                }
                case FILE_SEARCH -> {
                    resources.setFileSearch(new FileSearchToolResource().setVectorStoreIds(tool.resourceIds()));
                    present = true;
                }
                default -> {
                    // Other tool families do not consume file/vector-store resources.
                }
            }
        }
        return present ? resources : null;
    }

    private static MessageAttachment attachment(PersistentAttachment attachment) {
        List<BinaryData> tools = attachment.toolKinds().stream()
                .map(kind -> BinaryData.fromString("{\"type\":\""
                        + (kind == PersistentToolKind.FILE_SEARCH ? "file_search" : "code_interpreter")
                        + "\"}"))
                .toList();
        return new MessageAttachment(tools).setFileId(attachment.fileId());
    }

    private static PersistentAgentDefinition agent(PersistentAgent value) {
        return new PersistentAgentDefinition(
                value.getId(),
                value.getModel(),
                value.getName(),
                value.getDescription(),
                value.getInstructions(),
                value.getTools() == null
                        ? List.of()
                        : value.getTools().stream()
                                .map(PersistentSdkTransport::tool)
                                .toList(),
                value.getMetadata(),
                instant(value.getCreatedAt()));
    }

    private static PersistentAgentTool tool(ToolDefinition value) {
        return switch (value) {
            case CodeInterpreterToolDefinition _ -> PersistentAgentTool.codeInterpreter(List.of());
            case FileSearchToolDefinition _ -> PersistentAgentTool.fileSearch(List.of());
            case FunctionToolDefinition function ->
                PersistentAgentTool.function(
                        function.getFunction().getName(),
                        function.getFunction().getDescription(),
                        function.getFunction().getParameters().toString());
            case OpenApiToolDefinition openApi ->
                PersistentAgentTool.openApi(
                        openApi.getOpenapi().getName(),
                        openApi.getOpenapi().getDescription(),
                        openApi.getOpenapi().getSpec().toString());
            default ->
                new PersistentAgentTool(
                        PersistentToolKind.UNSUPPORTED,
                        null,
                        null,
                        null,
                        List.of(),
                        false,
                        "Unsupported persistent tool type: " + value.getType());
        };
    }

    private static PersistentThread thread(PersistentAgentThread value) {
        return new PersistentThread(value.getId(), instant(value.getCreatedAt()), value.getMetadata());
    }

    private static PersistentMessage message(ThreadMessage value) {
        String text = value.getContent() == null
                ? ""
                : value.getContent().stream()
                        .filter(MessageTextContent.class::isInstance)
                        .map(MessageTextContent.class::cast)
                        .map(content -> content.getText().getValue())
                        .collect(Collectors.joining());
        List<PersistentAttachment> attachments = value.getAttachments() == null
                ? List.of()
                : value.getAttachments().stream()
                        .filter(item -> item.getFileId() != null)
                        .map(item -> new PersistentAttachment(
                                item.getFileId(),
                                item.getTools().stream()
                                        .map(BinaryData::toString)
                                        .map(json -> json.contains("file_search")
                                                ? PersistentToolKind.FILE_SEARCH
                                                : PersistentToolKind.CODE_INTERPRETER)
                                        .toList()))
                        .toList();
        return new PersistentMessage(
                value.getId(),
                value.getThreadId(),
                value.getRunId(),
                MessageRole.USER.equals(value.getRole()) ? Role.USER : Role.ASSISTANT,
                text,
                attachments,
                value.getMetadata(),
                instant(value.getCreatedAt()));
    }

    private static PersistentRun run(ThreadRun value) {
        com.azure.ai.agents.persistent.models.RunError lastError = value.getLastError();
        RunCompletionUsage usage = value.getUsage();
        return new PersistentRun(
                value.getId(),
                value.getThreadId(),
                value.getAssistantId(),
                PersistentRunStatus.fromValue(value.getStatus().toString()),
                action(value.getRequiredAction()),
                lastError == null ? null : safe(lastError.getCode()),
                lastError == null ? null : safe(lastError.getMessage()),
                usage == null
                        ? null
                        : new PersistentRunUsage(
                                usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens()),
                instant(value.getCreatedAt()),
                instant(value.getCompletedAt()),
                value.getMetadata());
    }

    private static PersistentRequiredAction action(com.azure.ai.agents.persistent.models.RequiredAction value) {
        if (value == null) {
            return null;
        }
        if (value instanceof SubmitToolOutputsAction submit) {
            List<PersistentToolCall> calls = submit.getSubmitToolOutputs().getToolCalls().stream()
                    .map(PersistentSdkTransport::toolCall)
                    .toList();
            return new PersistentRequiredAction(value.getType(), calls, true);
        }
        return new PersistentRequiredAction(value.getType(), List.of(), false);
    }

    private static PersistentToolCall toolCall(RequiredToolCall value) {
        if (value instanceof RequiredFunctionToolCall function) {
            return new PersistentToolCall(
                    value.getId(),
                    function.getType(),
                    function.getFunction().getName(),
                    function.getFunction().getArguments(),
                    true);
        }
        return new PersistentToolCall(value.getId(), value.getType(), null, null, false);
    }

    private static PersistentRunEvent event(StreamUpdate update) {
        String kind = update.getKind() == null ? "unknown" : update.getKind().toString();
        if (update instanceof StreamThreadRunCreation created) {
            PersistentRun mapped = run(created.getMessage());
            return new PersistentRunEvent(kind, mapped.id(), null, null, mapped);
        }
        if (update instanceof StreamRequiredAction required) {
            PersistentRun mapped = run(required.getMessage());
            return new PersistentRunEvent(kind, mapped.id(), null, null, mapped);
        }
        if (update instanceof StreamMessageUpdate messageUpdate) {
            String delta = messageUpdate.getMessage().getDelta().getContent().stream()
                    .filter(MessageDeltaTextContent.class::isInstance)
                    .map(MessageDeltaTextContent.class::cast)
                    .map(MessageDeltaTextContent::getText)
                    .map(item -> item.getValue())
                    .collect(Collectors.joining());
            return new PersistentRunEvent(kind, null, messageUpdate.getMessage().getId(), delta, null);
        }
        return new PersistentRunEvent(kind, null, null, null, null);
    }

    private static MessageRole toMessageRole(Role role) {
        Objects.requireNonNull(role, "role");
        if (Role.USER.equals(role)) {
            return MessageRole.USER;
        }
        if (Role.ASSISTANT.equals(role)) {
            return MessageRole.AGENT;
        }
        throw configuration("Persistent thread messages support USER and ASSISTANT roles only.");
    }

    private static <T> CompletionStage<T> stage(Mono<T> mono, RunCancellation cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        CompletableFuture<T> upstream = mono.toFuture();
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicReference<RunCancellationRegistration> registration = new AtomicReference<>();
        registration.set(RunCancellations.register(cancellation, () -> {
            result.completeExceptionally(new RunCancelledException());
            upstream.cancel(true);
        }));
        upstream.whenComplete((value, failure) -> {
            RunCancellationRegistration current = registration.getAndSet(null);
            if (current != null) {
                current.close();
            }
            if (failure != null) {
                result.completeExceptionally(mapFailure(failure));
            } else {
                result.complete(value);
            }
        });
        return result.minimalCompletionStage();
    }

    private static <T> CompletionStage<PersistentPage<T>> page(
            Flux<T> flux,
            int limit,
            String inputCursor,
            java.util.function.Function<T, String> id,
            RunCancellation cancellation) {
        return stage(flux.take(limit + 1L).collectList(), cancellation).thenApply(values -> {
            boolean hasMore = values.size() > limit;
            List<T> items = hasMore ? List.copyOf(values.subList(0, limit)) : List.copyOf(values);
            String next = hasMore ? id.apply(items.getLast()) : null;
            if (hasMore && Objects.equals(next, inputCursor)) {
                throw new AzureAIPersistentException(
                        "Persistent pagination returned a repeated cursor.",
                        null,
                        AzureAIPersistentException.Kind.PROTOCOL,
                        null,
                        null,
                        "cursor_loop",
                        null);
            }
            return new PersistentPage<>(items, next, hasMore);
        });
    }

    private static RuntimeException mapFailure(Throwable failure) {
        Throwable cause =
                failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof RunCancelledException cancelled) {
            return cancelled;
        }
        if (cause instanceof AzureAIPersistentException mapped) {
            return mapped;
        }
        if (cause instanceof ClientAuthenticationException) {
            return new AzureAIPersistentException(
                    "Azure AI Persistent authentication failed.",
                    cause,
                    AzureAIPersistentException.Kind.AUTHENTICATION,
                    401,
                    null,
                    "authentication_failed",
                    null);
        }
        if (cause instanceof HttpResponseException responseFailure) {
            int status = responseFailure.getResponse().getStatusCode();
            String requestId =
                    responseFailure.getResponse().getHeaders().getValue(HttpHeaderName.fromString("x-request-id"));
            String retry =
                    responseFailure.getResponse().getHeaders().getValue(HttpHeaderName.fromString("retry-after"));
            return new AzureAIPersistentException(
                    "Azure AI Persistent request failed with HTTP " + status + ".",
                    cause,
                    AzureAIPersistentException.Kind.SERVICE,
                    status,
                    requestId,
                    "http_" + status,
                    parseRetryAfter(retry));
        }
        return new AzureAIPersistentException(
                "Azure AI Persistent transport failed.",
                cause,
                AzureAIPersistentException.Kind.TRANSPORT,
                null,
                null,
                "transport_failed",
                null);
    }

    private static AzureAIPersistentException configuration(String message) {
        return new AzureAIPersistentException(
                safe(message),
                null,
                AzureAIPersistentException.Kind.CONFIGURATION,
                null,
                null,
                "unsupported_capability",
                null);
    }

    private static Duration parseRetryAfter(String value) {
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

    private static java.time.Instant instant(java.time.OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String safe(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString()
                .replaceAll("(?i)(bearer|token|secret|api[-_ ]?key)\\s*[:=]?\\s*\\S+", "$1=[REDACTED]")
                .replaceAll("[\\r\\n\\t]", " ")
                .trim();
        return text.substring(0, Math.min(text.length(), 512));
    }

    private static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
