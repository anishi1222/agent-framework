// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.SystemMessageMode;
import com.github.copilot.generated.AssistantMessageDeltaEvent;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.AssistantUsageEvent;
import com.github.copilot.generated.SessionErrorEvent;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.generated.SessionIdleEvent;
import com.github.copilot.generated.ToolExecutionCompleteEvent;
import com.github.copilot.generated.ToolExecutionStartEvent;
import com.github.copilot.generated.UserMessageEvent;
import com.github.copilot.rpc.AgentStopHookInput;
import com.github.copilot.rpc.AgentStopHookOutput;
import com.github.copilot.rpc.CustomAgentConfig;
import com.github.copilot.rpc.HookInvocation;
import com.github.copilot.rpc.InfiniteSessionConfig;
import com.github.copilot.rpc.McpHttpServerConfig;
import com.github.copilot.rpc.McpServerConfig;
import com.github.copilot.rpc.McpStdioServerConfig;
import com.github.copilot.rpc.PermissionRequestResult;
import com.github.copilot.rpc.PostToolUseHookInput;
import com.github.copilot.rpc.PostToolUseHookOutput;
import com.github.copilot.rpc.PreMcpToolCallHookInput;
import com.github.copilot.rpc.PreMcpToolCallHookOutput;
import com.github.copilot.rpc.PreToolUseHookInput;
import com.github.copilot.rpc.PreToolUseHookOutput;
import com.github.copilot.rpc.ProviderConfig;
import com.github.copilot.rpc.ResumeSessionConfig;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.SessionEndHookInput;
import com.github.copilot.rpc.SessionEndHookOutput;
import com.github.copilot.rpc.SessionHooks;
import com.github.copilot.rpc.SessionStartHookInput;
import com.github.copilot.rpc.SessionStartHookOutput;
import com.github.copilot.rpc.SystemMessageConfig;
import com.github.copilot.rpc.ToolDefinition;
import com.github.copilot.rpc.UserInputResponse;
import com.github.copilot.rpc.UserPromptSubmittedHookInput;
import com.github.copilot.rpc.UserPromptSubmittedHookOutput;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.UsageDetails;
import com.microsoft.agents.core.internal.StrictJsonCodec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

final class GitHubCopilotSdkMapper {
    private final GitHubCopilotLimits limits;

    private final StrictJsonCodec json;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    GitHubCopilotSdkMapper(GitHubCopilotLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        json = new StrictJsonCodec(
                limits.maxDocumentBytes(),
                limits.maxDocumentBytes(),
                limits.maxNestingDepth(),
                limits.maxStringLength(),
                256,
                limits.maxCollectionEntries());
    }

    SessionConfig sessionConfig(GitHubCopilotSessionConfig source) {
        SessionConfig target = new SessionConfig()
                .setStreaming(source.streaming())
                .setOnPermissionRequest((request, invocation) -> source.permissionHandler()
                        .handleAsync(new GitHubCopilotPermissionRequest(
                                invocation.getSessionId(),
                                blankTo(request.getKind(), "unknown"),
                                request.getToolCallId(),
                                Boolean.TRUE.equals(request.getManagedApprovalRequired()),
                                stateMap(request.getExtensionData())))
                        .thenApply(GitHubCopilotSdkMapper::permissionResult)
                        .toCompletableFuture())
                .setOnUserInputRequest((request, invocation) -> source.userInputHandler()
                        .handleAsync(new GitHubCopilotUserInputRequest(
                                invocation.getSessionId(),
                                blankTo(request.getQuestion(), "Input required"),
                                request.getChoices() == null ? List.of() : request.getChoices(),
                                request.getAllowFreeform().orElse(false)))
                        .thenApply(GitHubCopilotSdkMapper::userInputResult)
                        .toCompletableFuture())
                .setEnableSessionStore(source.enableSessionStore());
        if (source.sessionId() != null) {
            target.setSessionId(source.sessionId());
        }
        if (source.model() != null) {
            target.setModel(source.model());
        }
        if (source.reasoningEffort() != null) {
            target.setReasoningEffort(source.reasoningEffort());
        }
        if (source.systemMessage() != null) {
            target.setSystemMessage(systemMessage(source.systemMessage()));
        }
        if (!source.tools().isEmpty()) {
            target.setTools(source.tools().stream().map(this::tool).toList());
        }
        List<String> available = availableTools(source);
        target.setAvailableTools(available);
        if (!source.excludedTools().isEmpty()) {
            target.setExcludedTools(source.excludedTools());
        }
        if (!source.mcpServers().isEmpty()) {
            LinkedHashMap<String, McpServerConfig> servers = new LinkedHashMap<>();
            source.mcpServers().forEach((name, config) -> servers.put(name, mcpServer(config)));
            target.setMcpServers(servers);
        }
        if (!source.customAgents().isEmpty()) {
            target.setCustomAgents(source.customAgents().stream()
                    .map(GitHubCopilotSdkMapper::customAgent)
                    .toList());
        }
        if (!source.skillDirectories().isEmpty()) {
            target.setSkillDirectories(source.skillDirectories().stream()
                    .map(java.nio.file.Path::toString)
                    .toList());
        }
        if (!source.disabledSkills().isEmpty()) {
            target.setDisabledSkills(List.copyOf(source.disabledSkills()));
        }
        if (source.infiniteSession() != null) {
            target.setInfiniteSessions(infiniteSession(source.infiniteSession()));
        }
        if (source.provider() != null) {
            target.setProvider(provider(source.provider()));
        }
        if (!source.hooks().isEmpty()) {
            target.setHooks(hooks(source));
        }
        return target;
    }

    ResumeSessionConfig resumeConfig(GitHubCopilotSessionConfig source) {
        ResumeSessionConfig target = new ResumeSessionConfig()
                .setStreaming(source.streaming())
                .setOnPermissionRequest((request, invocation) -> source.permissionHandler()
                        .handleAsync(new GitHubCopilotPermissionRequest(
                                invocation.getSessionId(),
                                blankTo(request.getKind(), "unknown"),
                                request.getToolCallId(),
                                Boolean.TRUE.equals(request.getManagedApprovalRequired()),
                                stateMap(request.getExtensionData())))
                        .thenApply(GitHubCopilotSdkMapper::permissionResult)
                        .toCompletableFuture())
                .setOnUserInputRequest((request, invocation) -> source.userInputHandler()
                        .handleAsync(new GitHubCopilotUserInputRequest(
                                invocation.getSessionId(),
                                blankTo(request.getQuestion(), "Input required"),
                                request.getChoices() == null ? List.of() : request.getChoices(),
                                request.getAllowFreeform().orElse(false)))
                        .thenApply(GitHubCopilotSdkMapper::userInputResult)
                        .toCompletableFuture())
                .setEnableSessionStore(source.enableSessionStore())
                .setAvailableTools(availableTools(source));
        if (source.model() != null) {
            target.setModel(source.model());
        }
        if (source.reasoningEffort() != null) {
            target.setReasoningEffort(source.reasoningEffort());
        }
        if (source.systemMessage() != null) {
            target.setSystemMessage(systemMessage(source.systemMessage()));
        }
        if (!source.tools().isEmpty()) {
            target.setTools(source.tools().stream().map(this::tool).toList());
        }
        if (!source.excludedTools().isEmpty()) {
            target.setExcludedTools(source.excludedTools());
        }
        if (!source.mcpServers().isEmpty()) {
            LinkedHashMap<String, McpServerConfig> servers = new LinkedHashMap<>();
            source.mcpServers().forEach((name, config) -> servers.put(name, mcpServer(config)));
            target.setMcpServers(servers);
        }
        if (!source.customAgents().isEmpty()) {
            target.setCustomAgents(source.customAgents().stream()
                    .map(GitHubCopilotSdkMapper::customAgent)
                    .toList());
        }
        if (!source.skillDirectories().isEmpty()) {
            target.setSkillDirectories(source.skillDirectories().stream()
                    .map(java.nio.file.Path::toString)
                    .toList());
        }
        if (!source.disabledSkills().isEmpty()) {
            target.setDisabledSkills(List.copyOf(source.disabledSkills()));
        }
        if (source.infiniteSession() != null) {
            target.setInfiniteSessions(infiniteSession(source.infiniteSession()));
        }
        if (source.provider() != null) {
            target.setProvider(provider(source.provider()));
        }
        if (!source.hooks().isEmpty()) {
            target.setHooks(hooks(source));
        }
        return target;
    }

    GitHubCopilotEvent event(String sessionId, SessionEvent source, long sequence, AtomicLong droppedUnknownEvents) {
        GitHubCopilotEventType type = GitHubCopilotEventType.OTHER;
        String messageId = null;
        String toolCallId = null;
        String toolName = null;
        StateValue.ObjectValue arguments = null;
        StateValue result = null;
        Boolean success = null;
        String model = null;
        String text = null;
        UsageDetails usage = null;
        if (source instanceof UserMessageEvent user && user.getData() != null) {
            type = GitHubCopilotEventType.USER_MESSAGE;
            text = bounded(user.getData().content(), "user message");
        } else if (source instanceof AssistantMessageDeltaEvent delta && delta.getData() != null) {
            type = GitHubCopilotEventType.ASSISTANT_MESSAGE_DELTA;
            messageId = delta.getData().messageId();
            text = bounded(delta.getData().deltaContent(), "assistant delta");
            model = null;
        } else if (source instanceof AssistantMessageEvent message && message.getData() != null) {
            type = GitHubCopilotEventType.ASSISTANT_MESSAGE;
            messageId = message.getData().messageId();
            model = message.getData().model();
            text = bounded(message.getData().content(), "assistant message");
        } else if (source instanceof ToolExecutionStartEvent start && start.getData() != null) {
            type = GitHubCopilotEventType.TOOL_EXECUTION_START;
            toolCallId = start.getData().toolCallId();
            toolName = start.getData().toolName();
            model = start.getData().model();
            StateValue mappedArguments = state(start.getData().arguments());
            arguments = mappedArguments instanceof StateValue.ObjectValue object
                    ? object
                    : StateValue.object(Map.of("value", mappedArguments));
        } else if (source instanceof ToolExecutionCompleteEvent complete && complete.getData() != null) {
            type = GitHubCopilotEventType.TOOL_EXECUTION_COMPLETE;
            toolCallId = complete.getData().toolCallId();
            model = complete.getData().model();
            success = Boolean.TRUE.equals(complete.getData().success());
            if (complete.getData().result() != null) {
                result = state(complete.getData().result().content());
            }
            if (complete.getData().error() != null) {
                text = bounded(complete.getData().error().message(), "tool error");
                result = StateValue.string(text == null ? "Tool execution failed." : text);
            }
        } else if (source instanceof AssistantUsageEvent usageEvent && usageEvent.getData() != null) {
            type = GitHubCopilotEventType.USAGE;
            model = usageEvent.getData().model();
            usage = usage(usageEvent);
        } else if (source instanceof SessionIdleEvent) {
            type = GitHubCopilotEventType.IDLE;
        } else if (source instanceof SessionErrorEvent error && error.getData() != null) {
            type = GitHubCopilotEventType.ERROR;
            text = bounded(error.getData().message(), "session error");
        } else {
            droppedUnknownEvents.incrementAndGet();
        }
        StateValue.ObjectValue rawValue = rawEvent(source);
        if (json.write(rawValue).length > limits.maxEventBytes()) {
            throw new GitHubCopilotProviderException(
                    "Copilot event exceeds the configured event limit.", null, "limit", "event_bytes");
        }
        Instant timestamp =
                source.getTimestamp() == null ? null : source.getTimestamp().toInstant();
        return new GitHubCopilotEvent(
                sequence,
                type,
                source.getType(),
                sessionId,
                source.getId() == null ? null : source.getId().toString(),
                messageId,
                toolCallId,
                toolName,
                arguments,
                result,
                success,
                model,
                text,
                usage,
                timestamp,
                rawValue);
    }

    StateValue state(Object value) {
        if (value == null) {
            return StateValue.nullValue();
        }
        try {
            return json.parse(objectMapper.writeValueAsBytes(value));
        } catch (Exception exception) {
            throw new GitHubCopilotProviderException(
                    "Copilot JSON value is malformed or exceeds configured limits.",
                    exception,
                    "protocol",
                    "invalid_json");
        }
    }

    private StateValue.ObjectValue rawEvent(SessionEvent source) {
        StateValue value = state(objectMapper.valueToTree(source));
        return value instanceof StateValue.ObjectValue object ? object : StateValue.object(Map.of("value", value));
    }

    Object javaValue(StateValue value) {
        return switch (value) {
            case StateValue.ObjectValue object -> {
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                object.values().forEach((key, child) -> result.put(key, javaValue(child)));
                yield result;
            }
            case StateValue.ArrayValue array ->
                array.values().stream().map(this::javaValue).toList();
            case StateValue.StringValue string -> string.value();
            case StateValue.NumberValue number -> number.value();
            case StateValue.BooleanValue bool -> bool.value();
            case StateValue.NullValue nullValue -> jsonNull(nullValue);
        };
    }

    private static Object jsonNull(StateValue.NullValue value) {
        Objects.requireNonNull(value, "value");
        return null;
    }

    private ToolDefinition tool(GitHubCopilotTool source) {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) javaValue(source.parameters());
        return new ToolDefinition(
                source.name(),
                source.description(),
                schema,
                invocation -> {
                    StateValue arguments = state(invocation.getArguments());
                    if (!(arguments instanceof StateValue.ObjectValue object)) {
                        return CompletableFuture.failedFuture(new GitHubCopilotProviderException(
                                "Tool arguments must be a JSON object.", null, "protocol", "tool_arguments"));
                    }
                    CompletionStage<StateValue> result;
                    try {
                        result = Objects.requireNonNull(
                                source.handler()
                                        .invokeAsync(new GitHubCopilotToolCall(
                                                invocation.getSessionId(),
                                                invocation.getToolCallId(),
                                                source.name(),
                                                object)),
                                "GitHubCopilotToolHandler returned null.");
                    } catch (RuntimeException exception) {
                        return CompletableFuture.failedFuture(exception);
                    }
                    return result.thenApply(this::javaValue).toCompletableFuture();
                },
                false,
                false,
                null);
    }

    private McpServerConfig mcpServer(GitHubCopilotMCPServerConfig source) {
        int timeoutMillis = Math.toIntExact(source.timeout().toMillis());
        if (source instanceof GitHubCopilotMCPStdioServerConfig stdio) {
            return new McpStdioServerConfig()
                    .setCommand(stdio.executable().toString())
                    .setArgs(stdio.arguments())
                    .setWorkingDirectory(stdio.workingDirectory().toString())
                    .setEnv(stdio.environment())
                    .setTools(stdio.tools())
                    .setTimeout(timeoutMillis);
        }
        GitHubCopilotMCPHttpServerConfig http = (GitHubCopilotMCPHttpServerConfig) source;
        return new McpHttpServerConfig()
                .setUrl(http.endpoint().toString())
                .setHeaders(http.headers())
                .setTools(http.tools())
                .setTimeout(timeoutMillis);
    }

    private static List<String> availableTools(GitHubCopilotSessionConfig source) {
        ArrayList<String> result = new ArrayList<>(source.allowedBuiltInTools());
        source.tools().forEach(tool -> result.add(tool.name()));
        source.mcpServers()
                .forEach((server, config) -> config.tools().forEach(tool -> result.add("mcp:" + server + "-" + tool)));
        return List.copyOf(result);
    }

    private static SystemMessageConfig systemMessage(GitHubCopilotSystemMessage source) {
        SystemMessageMode mode = source.mode() == GitHubCopilotSystemMessage.Mode.APPEND
                ? SystemMessageMode.APPEND
                : SystemMessageMode.REPLACE;
        return new SystemMessageConfig().setMode(mode).setContent(source.content());
    }

    private static CustomAgentConfig customAgent(GitHubCopilotCustomAgent source) {
        CustomAgentConfig target = new CustomAgentConfig()
                .setName(source.name())
                .setDisplayName(source.displayName())
                .setDescription(source.description())
                .setPrompt(source.prompt())
                .setTools(source.tools())
                .setSkills(source.skills());
        if (source.model() != null) {
            target.setModel(source.model());
        }
        return target;
    }

    private static InfiniteSessionConfig infiniteSession(GitHubCopilotInfiniteSessionConfig source) {
        return new InfiniteSessionConfig()
                .setEnabled(source.enabled())
                .setBackgroundCompactionThreshold(source.backgroundCompactionThreshold())
                .setBufferExhaustionThreshold(source.bufferExhaustionThreshold());
    }

    private static ProviderConfig provider(GitHubCopilotProviderConfig source) {
        ProviderConfig target = new ProviderConfig()
                .setType(source.type())
                .setWireApi(source.wireApi())
                .setBaseUrl(source.baseUri().toString())
                .setHeaders(source.headers())
                .setModelId(source.modelId())
                .setWireModel(source.wireModel());
        if (source.apiKey() != null) {
            target.setApiKey(source.apiKey().reveal());
        } else {
            target.setBearerToken(source.bearerToken().reveal());
        }
        if (source.maxPromptTokens() != null) {
            target.setMaxPromptTokens(source.maxPromptTokens());
        }
        if (source.maxOutputTokens() != null) {
            target.setMaxOutputTokens(source.maxOutputTokens());
        }
        return target;
    }

    private SessionHooks hooks(GitHubCopilotSessionConfig source) {
        SessionHooks target = new SessionHooks();
        GitHubCopilotHook preTool = source.hooks().get(GitHubCopilotHookType.PRE_TOOL_USE);
        if (preTool != null) {
            target.setOnPreToolUse((input, invocation) -> invokeHook(
                            preTool, hookRequest(GitHubCopilotHookType.PRE_TOOL_USE, input, invocation))
                    .thenApply(result -> preToolUseResult(result)));
        }
        GitHubCopilotHook preMcp = source.hooks().get(GitHubCopilotHookType.PRE_MCP_TOOL_CALL);
        if (preMcp != null) {
            target.setOnPreMcpToolCall((input, invocation) -> invokeHook(
                            preMcp, hookRequest(GitHubCopilotHookType.PRE_MCP_TOOL_CALL, input, invocation))
                    .thenApply(result -> preMcpResult(result)));
        }
        GitHubCopilotHook postTool = source.hooks().get(GitHubCopilotHookType.POST_TOOL_USE);
        if (postTool != null) {
            target.setOnPostToolUse((input, invocation) -> invokeHook(
                            postTool, hookRequest(GitHubCopilotHookType.POST_TOOL_USE, input, invocation))
                    .thenApply(result -> postToolUseResult(result)));
        }
        GitHubCopilotHook prompt = source.hooks().get(GitHubCopilotHookType.USER_PROMPT_SUBMITTED);
        if (prompt != null) {
            target.setOnUserPromptSubmitted((input, invocation) -> invokeHook(
                            prompt, hookRequest(GitHubCopilotHookType.USER_PROMPT_SUBMITTED, input, invocation))
                    .thenApply(result -> promptResult(result)));
        }
        GitHubCopilotHook sessionStart = source.hooks().get(GitHubCopilotHookType.SESSION_START);
        if (sessionStart != null) {
            target.setOnSessionStart((input, invocation) -> invokeHook(
                            sessionStart, hookRequest(GitHubCopilotHookType.SESSION_START, input, invocation))
                    .thenApply(result -> sessionStartResult(result)));
        }
        GitHubCopilotHook sessionEnd = source.hooks().get(GitHubCopilotHookType.SESSION_END);
        if (sessionEnd != null) {
            target.setOnSessionEnd((input, invocation) -> invokeHook(
                            sessionEnd, hookRequest(GitHubCopilotHookType.SESSION_END, input, invocation))
                    .thenApply(result -> sessionEndResult(result)));
        }
        GitHubCopilotHook agentStop = source.hooks().get(GitHubCopilotHookType.AGENT_STOP);
        if (agentStop != null) {
            target.setOnAgentStop((input, invocation) -> invokeHook(
                            agentStop, hookRequest(GitHubCopilotHookType.AGENT_STOP, input, invocation))
                    .thenApply(result -> agentStopResult(result)));
        }
        return target;
    }

    private CompletableFuture<GitHubCopilotHookResult> invokeHook(
            GitHubCopilotHook hook, GitHubCopilotHookRequest request) {
        CompletionStage<GitHubCopilotHookResult> stage;
        try {
            stage = Objects.requireNonNull(hook.handleAsync(request), "GitHubCopilotHook returned null.");
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        return stage.thenApply(result -> Objects.requireNonNull(result, "GitHubCopilotHook result"))
                .toCompletableFuture();
    }

    private GitHubCopilotHookRequest hookRequest(
            GitHubCopilotHookType type, PreToolUseHookInput input, HookInvocation invocation) {
        return new GitHubCopilotHookRequest(
                type,
                hookSessionId(input.getSessionId(), invocation),
                Instant.ofEpochMilli(input.getTimestamp()),
                input.getCwd(),
                input.getToolName(),
                null,
                null,
                state(input.getToolArgs()),
                null,
                Map.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private GitHubCopilotHookRequest hookRequest(
            GitHubCopilotHookType type, PreMcpToolCallHookInput input, HookInvocation invocation) {
        return new GitHubCopilotHookRequest(
                type,
                hookSessionId(input.getSessionId(), invocation),
                Instant.ofEpochMilli(input.getTimestamp()),
                input.getCwd(),
                input.getToolName(),
                input.getServerName(),
                input.getToolCallId(),
                state(input.getArguments()),
                null,
                stateMap(input.getMeta()),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private GitHubCopilotHookRequest hookRequest(
            GitHubCopilotHookType type, PostToolUseHookInput input, HookInvocation invocation) {
        return new GitHubCopilotHookRequest(
                type,
                hookSessionId(input.getSessionId(), invocation),
                Instant.ofEpochMilli(input.getTimestamp()),
                input.getCwd(),
                input.getToolName(),
                null,
                null,
                state(input.getToolArgs()),
                state(input.getToolResult()),
                Map.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static GitHubCopilotHookRequest hookRequest(
            GitHubCopilotHookType type, UserPromptSubmittedHookInput input, HookInvocation invocation) {
        return new GitHubCopilotHookRequest(
                type,
                hookSessionId(input.sessionId(), invocation),
                Instant.ofEpochMilli(input.timestamp()),
                input.cwd(),
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                input.prompt(),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static GitHubCopilotHookRequest hookRequest(
            GitHubCopilotHookType type, SessionStartHookInput input, HookInvocation invocation) {
        return new GitHubCopilotHookRequest(
                type,
                hookSessionId(input.sessionId(), invocation),
                Instant.ofEpochMilli(input.timestamp()),
                input.cwd(),
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                input.initialPrompt(),
                input.source(),
                null,
                null,
                null,
                null,
                null);
    }

    private static GitHubCopilotHookRequest hookRequest(
            GitHubCopilotHookType type, SessionEndHookInput input, HookInvocation invocation) {
        return new GitHubCopilotHookRequest(
                type,
                hookSessionId(input.sessionId(), invocation),
                Instant.ofEpochMilli(input.timestamp()),
                input.cwd(),
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                null,
                null,
                input.reason(),
                input.finalMessage(),
                input.error(),
                null,
                null);
    }

    private static GitHubCopilotHookRequest hookRequest(
            GitHubCopilotHookType type, AgentStopHookInput input, HookInvocation invocation) {
        return new GitHubCopilotHookRequest(
                type,
                hookSessionId(input.getSessionId(), invocation),
                Instant.ofEpochMilli(input.getTimestamp()),
                input.getCwd(),
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                null,
                null,
                input.getStopReason(),
                null,
                null,
                input.getTranscriptPath(),
                input.getStopHookActive());
    }

    private PreToolUseHookOutput preToolUseResult(GitHubCopilotHookResult source) {
        if (source == GitHubCopilotHookResult.NoChange.INSTANCE) {
            return null;
        }
        if (!(source instanceof GitHubCopilotHookResult.PreToolUse result)) {
            throw hookResultMismatch(GitHubCopilotHookType.PRE_TOOL_USE, source);
        }
        return new PreToolUseHookOutput(
                result.permission().sdkValue(),
                result.reason(),
                jsonNode(result.modifiedArguments()),
                result.additionalContext(),
                result.suppressOutput());
    }

    private PreMcpToolCallHookOutput preMcpResult(GitHubCopilotHookResult source) {
        if (source == GitHubCopilotHookResult.NoChange.INSTANCE) {
            return null;
        }
        if (!(source instanceof GitHubCopilotHookResult.McpMetadata result)) {
            throw hookResultMismatch(GitHubCopilotHookType.PRE_MCP_TOOL_CALL, source);
        }
        return switch (result.action()) {
            case KEEP -> null;
            case REMOVE -> PreMcpToolCallHookOutput.removeMeta();
            case REPLACE -> PreMcpToolCallHookOutput.withMeta(jsonNode(result.metadata()));
        };
    }

    private PostToolUseHookOutput postToolUseResult(GitHubCopilotHookResult source) {
        if (source == GitHubCopilotHookResult.NoChange.INSTANCE) {
            return null;
        }
        if (!(source instanceof GitHubCopilotHookResult.PostToolUse result)) {
            throw hookResultMismatch(GitHubCopilotHookType.POST_TOOL_USE, source);
        }
        return new PostToolUseHookOutput(
                jsonNode(result.modifiedResult()), result.additionalContext(), result.suppressOutput());
    }

    private static UserPromptSubmittedHookOutput promptResult(GitHubCopilotHookResult source) {
        if (source == GitHubCopilotHookResult.NoChange.INSTANCE) {
            return null;
        }
        if (!(source instanceof GitHubCopilotHookResult.Prompt result)) {
            throw hookResultMismatch(GitHubCopilotHookType.USER_PROMPT_SUBMITTED, source);
        }
        return new UserPromptSubmittedHookOutput(
                result.modifiedPrompt(), result.additionalContext(), result.suppressOutput());
    }

    private SessionStartHookOutput sessionStartResult(GitHubCopilotHookResult source) {
        if (source == GitHubCopilotHookResult.NoChange.INSTANCE) {
            return null;
        }
        if (!(source instanceof GitHubCopilotHookResult.SessionStart result)) {
            throw hookResultMismatch(GitHubCopilotHookType.SESSION_START, source);
        }
        LinkedHashMap<String, Object> modifiedConfig = new LinkedHashMap<>();
        result.modifiedConfig().forEach((name, value) -> modifiedConfig.put(name, javaValue(value)));
        return new SessionStartHookOutput(result.additionalContext(), modifiedConfig);
    }

    private static SessionEndHookOutput sessionEndResult(GitHubCopilotHookResult source) {
        if (source == GitHubCopilotHookResult.NoChange.INSTANCE) {
            return null;
        }
        if (!(source instanceof GitHubCopilotHookResult.SessionEnd result)) {
            throw hookResultMismatch(GitHubCopilotHookType.SESSION_END, source);
        }
        return new SessionEndHookOutput(result.suppressOutput(), result.cleanupActions(), result.sessionSummary());
    }

    private static AgentStopHookOutput agentStopResult(GitHubCopilotHookResult source) {
        if (source == GitHubCopilotHookResult.NoChange.INSTANCE) {
            return null;
        }
        if (!(source instanceof GitHubCopilotHookResult.AgentStop result)) {
            throw hookResultMismatch(GitHubCopilotHookType.AGENT_STOP, source);
        }
        return result.block() ? new AgentStopHookOutput().setDecision("block").setReason(result.reason()) : null;
    }

    private com.fasterxml.jackson.databind.JsonNode jsonNode(StateValue source) {
        return source == null ? null : objectMapper.valueToTree(javaValue(source));
    }

    private static String hookSessionId(String source, HookInvocation invocation) {
        String sessionId = source == null || source.isBlank() ? invocation.getSessionId() : source;
        return blankTo(sessionId, "unknown-session");
    }

    private static IllegalArgumentException hookResultMismatch(
            GitHubCopilotHookType type, GitHubCopilotHookResult result) {
        return new IllegalArgumentException("Hook " + type + " returned incompatible result "
                + result.getClass().getSimpleName() + ".");
    }

    private static PermissionRequestResult permissionResult(GitHubCopilotPermissionResponse response) {
        return switch (response.decision()) {
            case APPROVE_ONCE -> PermissionRequestResult.approveOnce();
            case USER_NOT_AVAILABLE -> PermissionRequestResult.userNotAvailable();
            case DENY ->
                PermissionRequestResult.reject(
                        response.feedback() == null ? "Permission denied." : response.feedback());
        };
    }

    private static UserInputResponse userInputResult(GitHubCopilotUserInputResponse response) {
        return new UserInputResponse().setAnswer(response.answer()).setWasFreeform(response.freeform());
    }

    private static UsageDetails usage(AssistantUsageEvent event) {
        UsageDetails.Builder builder = UsageDetails.builder();
        long input = value(event.getData().inputTokens());
        long output = value(event.getData().outputTokens());
        builder.inputTokens(input).outputTokens(output).totalTokens(Math.addExact(input, output));
        if (event.getData().cacheReadTokens() != null) {
            builder.value(
                    UsageDetails.CACHE_READ_INPUT_TOKENS,
                    StateValue.integer(event.getData().cacheReadTokens()));
        }
        if (event.getData().cacheWriteTokens() != null) {
            builder.value(
                    UsageDetails.CACHE_CREATION_INPUT_TOKENS,
                    StateValue.integer(event.getData().cacheWriteTokens()));
        }
        if (event.getData().reasoningTokens() != null) {
            builder.value(
                    UsageDetails.REASONING_OUTPUT_TOKENS,
                    StateValue.integer(event.getData().reasoningTokens()));
        }
        return builder.build();
    }

    private Map<String, StateValue> stateMap(Map<String, ?> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        StateValue state = state(values);
        return state instanceof StateValue.ObjectValue object ? object.values() : Map.of();
    }

    private String bounded(String value, String field) {
        if (value == null) {
            return null;
        }
        if (value.length() > limits.maxStringLength()) {
            throw new GitHubCopilotProviderException(
                    "Copilot " + field + " exceeds the configured string limit.", null, "limit", "string_length");
        }
        return value;
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
