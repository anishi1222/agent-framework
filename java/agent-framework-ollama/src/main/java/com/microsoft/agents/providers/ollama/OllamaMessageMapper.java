// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.ollama;

import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.ReasoningContent;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StructuredOutputOptions;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.UsageDetails;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.core.internal.StrictJsonCodec;
import com.microsoft.agents.core.internal.StructuredOutputSupport;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import com.microsoft.agents.tools.ToolMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class OllamaMessageMapper {
    private static final Role DEVELOPER = Role.of("developer");

    private OllamaMessageMapper() {}

    static void validate(ChatClientRequest request) {
        if (request.messages().isEmpty()) {
            throw new ValidationException("Ollama requests require at least one message.");
        }
        for (Message message : request.messages()) {
            Role role = message.role();
            if (role.equals(DEVELOPER)) {
                throw new ValidationException("Ollama /api/chat does not support the developer role.");
            }
            if (!(role.equals(Role.SYSTEM)
                    || role.equals(Role.USER)
                    || role.equals(Role.ASSISTANT)
                    || role.equals(Role.TOOL))) {
                throw new ValidationException("Ollama does not support role '" + role.value() + "'.");
            }
            if (message.contents().isEmpty()) {
                throw new ValidationException("Ollama messages require content.");
            }
            for (Content content : message.contents()) {
                if (role.equals(Role.TOOL)) {
                    if (!(content instanceof FunctionResultContent result)
                            || !result.items().isEmpty()) {
                        throw unsupported(role, content);
                    }
                } else if (content instanceof TextContent) {
                    // Supported for all non-tool roles.
                } else if (content instanceof DataContent data) {
                    if (!role.equals(Role.USER)
                            || !data.mediaType()
                                    .toLowerCase(java.util.Locale.ROOT)
                                    .startsWith("image/")) {
                        throw unsupported(role, content);
                    }
                } else if (content instanceof FunctionCallContent && role.equals(Role.ASSISTANT)) {
                    // Supported.
                } else {
                    throw unsupported(role, content);
                }
            }
        }
        for (ToolMetadata tool : request.tools()) {
            if (!tool.capabilities().equals(Set.of(ToolCapability.FUNCTION))) {
                throw new ValidationException(
                        "Ollama supports only FUNCTION tools; tool '" + tool.name() + "' is unsupported.");
            }
        }
        ChatOptions options = request.options();
        if (options.frequencyPenalty() != null
                || options.presencePenalty() != null
                || options.user() != null
                || options.store() != null
                || options.conversationId() != null
                || options.allowMultipleToolCalls() != null) {
            throw new ValidationException("Ollama does not support frequencyPenalty, presencePenalty, user, store, "
                    + "conversationId, or allowMultipleToolCalls.");
        }
        for (Map.Entry<String, StateValue> entry : options.metadata().entrySet()) {
            if (entry.getKey().startsWith("ollama.")
                    && !Set.of("ollama.responseSchema", "ollama.think", "ollama.keepAlive")
                            .contains(entry.getKey())) {
                throw new ValidationException("Unsupported Ollama option '" + entry.getKey() + "'.");
            }
        }
        StructuredOutputSupport.resolve(options, "ollama.responseSchema");
        StateValue think = options.metadata().get("ollama.think");
        if (think != null
                && !(think instanceof StateValue.BooleanValue)
                && !(think instanceof StateValue.StringValue)) {
            throw new ValidationException("ollama.think must be a Boolean or string.");
        }
        StateValue keepAlive = options.metadata().get("ollama.keepAlive");
        if (keepAlive != null && !(keepAlive instanceof StateValue.StringValue)) {
            throw new ValidationException("ollama.keepAlive must be a string.");
        }
        if (request.tools().isEmpty()
                && (request.toolMode() == ToolMode.REQUIRED
                        || options.toolChoice() == com.microsoft.agents.core.ToolChoice.REQUIRED)) {
            throw new ValidationException("Ollama required tool selection needs at least one tool.");
        }
    }

    static StateValue.ObjectValue request(
            ChatClientRequest request, OllamaChatClientOptions defaults, StrictJsonCodec codec, boolean stream) {
        validate(request);
        ChatOptions options = request.options();
        LinkedHashMap<String, StateValue> root = new LinkedHashMap<>();
        root.put("model", StateValue.string(options.model() == null ? defaults.model() : options.model()));
        root.put("messages", StateValue.array(messages(request, options, codec)));
        root.put("stream", StateValue.bool(stream));
        if (!request.tools().isEmpty() && effectiveToolMode(request) != ToolMode.NONE) {
            root.put(
                    "tools",
                    StateValue.array(request.tools().stream()
                            .map(OllamaMessageMapper::tool)
                            .toList()));
        }
        StructuredOutputOptions structuredOutput = StructuredOutputSupport.resolve(options, "ollama.responseSchema");
        if (structuredOutput != null) {
            root.put("format", structuredOutput.schema());
        }
        StateValue think = options.metadata().get("ollama.think");
        if (think != null) {
            root.put("think", think);
        }
        StateValue keepAlive = options.metadata().get("ollama.keepAlive");
        if (keepAlive != null) {
            root.put("keep_alive", keepAlive);
        }
        LinkedHashMap<String, StateValue> generation = new LinkedHashMap<>();
        putDecimal(generation, "temperature", options.temperature());
        putDecimal(generation, "top_p", options.topP());
        putLong(generation, "num_predict", options.maxTokens());
        putLong(generation, "seed", options.seed());
        if (!options.stop().isEmpty()) {
            generation.put(
                    "stop",
                    StateValue.array(
                            options.stop().stream().map(StateValue::string).toList()));
        }
        if (!generation.isEmpty()) {
            root.put("options", StateValue.object(generation));
        }
        return StateValue.object(root);
    }

    static ChatResponse response(StateValue value, String requestId, StrictJsonCodec codec) {
        StateValue.ObjectValue root = object(value);
        rejectError(root, null, requestId);
        StateValue.ObjectValue message = requiredObject(root, "message");
        ArrayList<Content> content = messageContent(message, codec, "finite");
        String model = optionalString(root, "model");
        Instant created = optionalInstant(root, "created_at");
        String reason = optionalString(root, "done_reason");
        FinishReason finish = mapFinish(reason, bool(root, "done", true));
        Map<String, StateValue> metadata = metadata(root, requestId, reason);
        return ChatResponse.builder()
                .messages(List.of(
                        Message.builder(Role.ASSISTANT).contents(content).build()))
                .model(model)
                .createdAt(created)
                .finishReason(finish)
                .usage(usage(root))
                .metadata(metadata)
                .build();
    }

    private static List<StateValue> messages(ChatClientRequest request, ChatOptions options, StrictJsonCodec codec) {
        ArrayList<StateValue> result = new ArrayList<>();
        if (options.instructions() != null) {
            result.add(StateValue.object(
                    Map.of("role", StateValue.string("system"), "content", StateValue.string(options.instructions()))));
        }
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        for (Message message : request.messages()) {
            if (message.role().equals(Role.TOOL)) {
                for (Content item : message.contents()) {
                    FunctionResultContent toolResult = (FunctionResultContent) item;
                    String name = names.get(toolResult.callId());
                    if (name == null) {
                        throw new ValidationException(
                                "Ollama tool result '" + toolResult.callId() + "' has no preceding function call.");
                    }
                    LinkedHashMap<String, StateValue> mapped = new LinkedHashMap<>();
                    mapped.put("role", StateValue.string("tool"));
                    mapped.put("content", StateValue.string(resultText(toolResult, codec)));
                    mapped.put("tool_name", StateValue.string(name));
                    mapped.put("tool_call_id", StateValue.string(toolResult.callId()));
                    result.add(StateValue.object(mapped));
                }
                continue;
            }
            LinkedHashMap<String, StateValue> mapped = new LinkedHashMap<>();
            mapped.put("role", StateValue.string(role(message.role())));
            StringBuilder text = new StringBuilder();
            ArrayList<StateValue> images = new ArrayList<>();
            ArrayList<StateValue> calls = new ArrayList<>();
            for (Content item : message.contents()) {
                if (item instanceof TextContent value) {
                    text.append(value.text());
                } else if (item instanceof DataContent image) {
                    images.add(StateValue.string(Base64.getEncoder().encodeToString(image.data())));
                } else if (item instanceof FunctionCallContent call) {
                    names.put(call.callId(), call.name());
                    calls.add(functionCall(call));
                }
            }
            mapped.put("content", StateValue.string(text.toString()));
            if (!images.isEmpty()) {
                mapped.put("images", StateValue.array(images));
            }
            if (!calls.isEmpty()) {
                mapped.put("tool_calls", StateValue.array(calls));
            }
            result.add(StateValue.object(mapped));
        }
        return List.copyOf(result);
    }

    private static StateValue functionCall(FunctionCallContent call) {
        return StateValue.object(Map.of(
                "id",
                StateValue.string(call.callId()),
                "function",
                StateValue.object(Map.of("name", StateValue.string(call.name()), "arguments", call.arguments()))));
    }

    private static StateValue tool(ToolMetadata tool) {
        return StateValue.object(Map.of(
                "type",
                StateValue.string("function"),
                "function",
                StateValue.object(Map.of(
                        "name", StateValue.string(tool.name()),
                        "description", StateValue.string(tool.description()),
                        "parameters", tool.inputSchema()))));
    }

    private static ArrayList<Content> messageContent(
            StateValue.ObjectValue message, StrictJsonCodec codec, String syntheticPrefix) {
        ArrayList<Content> content = new ArrayList<>();
        String thinking = optionalString(message, "thinking");
        if (thinking != null && !thinking.isEmpty()) {
            content.add(new ReasoningContent("ollama-thinking", thinking));
        }
        String text = optionalString(message, "content");
        if (text != null && !text.isEmpty()) {
            content.add(new TextContent(text));
        }
        StateValue.ArrayValue calls = optionalArray(message, "tool_calls");
        if (calls != null) {
            int index = 0;
            for (StateValue item : calls.values()) {
                content.add(parseToolCall(object(item), codec, syntheticPrefix + "-" + index, index));
                index++;
            }
        }
        return content;
    }

    private static FunctionCallContent parseToolCall(
            StateValue.ObjectValue call, StrictJsonCodec codec, String fallbackId, int index) {
        String id = optionalString(call, "id");
        StateValue.ObjectValue function = requiredObject(call, "function");
        String name = requiredString(function, "name");
        StateValue arguments = function.values().getOrDefault("arguments", StateValue.object(Map.of()));
        if (arguments instanceof StateValue.StringValue text) {
            arguments = text.value().isBlank()
                    ? StateValue.object(Map.of())
                    : codec.parse(text.value().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        if (id == null) {
            id = fallbackId;
            metadata.put("ollama.syntheticCallId", StateValue.bool(true));
            metadata.put("ollama.toolIndex", StateValue.integer(index));
        }
        return new FunctionCallContent(id, name, arguments, false, metadata);
    }

    private static String resultText(FunctionResultContent result, StrictJsonCodec codec) {
        if (result.error() != null) {
            return "Error: " + result.error();
        }
        if (result.result() instanceof StateValue.StringValue text) {
            return text.value();
        }
        return new String(codec.write(result.result()), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static ToolMode effectiveToolMode(ChatClientRequest request) {
        ToolMode mode = request.toolMode();
        if (mode == ToolMode.NONE && request.options().toolChoice() != null) {
            mode = switch (request.options().toolChoice()) {
                case NONE -> ToolMode.NONE;
                case AUTO -> ToolMode.AUTO;
                case REQUIRED -> ToolMode.REQUIRED;
            };
        }
        return mode;
    }

    static UsageDetails usage(StateValue.ObjectValue root) {
        Long input = optionalLong(root, "prompt_eval_count");
        Long output = optionalLong(root, "eval_count");
        if (input == null && output == null) {
            return null;
        }
        UsageDetails.Builder builder = UsageDetails.builder();
        if (input != null) {
            builder.inputTokens(input);
        }
        if (output != null) {
            builder.outputTokens(output);
        }
        if (input != null && output != null) {
            builder.totalTokens(Math.addExact(input, output));
        }
        return builder.build();
    }

    static FinishReason mapFinish(String reason, boolean done) {
        if (!done) {
            return null;
        }
        if (reason == null || reason.isBlank() || "stop".equals(reason)) {
            return FinishReason.STOP;
        }
        return switch (reason) {
            case "length" -> FinishReason.LENGTH;
            case "tool_calls", "tool_call" -> FinishReason.TOOL_CALLS;
            case "content_filter", "safety" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.of(reason);
        };
    }

    static void rejectError(StateValue.ObjectValue root, Integer status, String requestId) {
        StateValue error = root.values().get("error");
        if (error != null && error != StateValue.NullValue.INSTANCE) {
            throw new OllamaProviderException("provider_error", status, requestId);
        }
    }

    static Map<String, StateValue> metadata(StateValue.ObjectValue root, String requestId, String reason) {
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        if (requestId != null && !requestId.isBlank()) {
            metadata.put("ollama.requestId", StateValue.string(requestId));
        }
        if (reason != null && !reason.isBlank()) {
            metadata.put("ollama.doneReason", StateValue.string(reason));
        }
        for (String field :
                List.of("total_duration", "load_duration", "prompt_eval_duration", "eval_duration", "created_at")) {
            StateValue value = root.values().get(field);
            if (value != null && value != StateValue.NullValue.INSTANCE) {
                metadata.put("ollama." + camel(field), value);
            }
        }
        return Map.copyOf(metadata);
    }

    private static String camel(String value) {
        StringBuilder result = new StringBuilder();
        boolean upper = false;
        for (char character : value.toCharArray()) {
            if (character == '_') {
                upper = true;
            } else if (upper) {
                result.append(Character.toUpperCase(character));
                upper = false;
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    private static String role(Role role) {
        if (role.equals(Role.SYSTEM)) {
            return "system";
        }
        if (role.equals(Role.USER)) {
            return "user";
        }
        if (role.equals(Role.ASSISTANT)) {
            return "assistant";
        }
        throw new ValidationException("Unsupported Ollama role '" + role.value() + "'.");
    }

    private static ValidationException unsupported(Role role, Content content) {
        return new ValidationException(
                "Ollama does not support content kind '" + content.kind() + "' for role '" + role.value() + "'.");
    }

    static StateValue.ObjectValue object(StateValue value) {
        if (!(value instanceof StateValue.ObjectValue object)) {
            throw new OllamaProviderException("expected_object", null, null);
        }
        return object;
    }

    static StateValue.ObjectValue requiredObject(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (!(value instanceof StateValue.ObjectValue result)) {
            throw new OllamaProviderException("missing_" + name, null, null);
        }
        return result;
    }

    static StateValue.ObjectValue optionalObject(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null || value == StateValue.NullValue.INSTANCE) {
            return null;
        }
        return object(value);
    }

    static StateValue.ArrayValue optionalArray(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null || value == StateValue.NullValue.INSTANCE) {
            return null;
        }
        if (!(value instanceof StateValue.ArrayValue array)) {
            throw new OllamaProviderException("invalid_" + name, null, null);
        }
        return array;
    }

    static String optionalString(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null || value == StateValue.NullValue.INSTANCE) {
            return null;
        }
        if (!(value instanceof StateValue.StringValue string)) {
            throw new OllamaProviderException("invalid_" + name, null, null);
        }
        return string.value();
    }

    static String requiredString(StateValue.ObjectValue object, String name) {
        String value = optionalString(object, name);
        if (value == null || value.isBlank()) {
            throw new OllamaProviderException("missing_" + name, null, null);
        }
        return value;
    }

    static Long optionalLong(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null || value == StateValue.NullValue.INSTANCE) {
            return null;
        }
        if (!(value instanceof StateValue.NumberValue number)) {
            throw new OllamaProviderException("invalid_" + name, null, null);
        }
        try {
            return number.value().longValueExact();
        } catch (ArithmeticException exception) {
            throw new OllamaProviderException("invalid_" + name, null, null);
        }
    }

    static boolean bool(StateValue.ObjectValue object, String name, boolean defaultValue) {
        StateValue value = object.values().get(name);
        if (value == null || value == StateValue.NullValue.INSTANCE) {
            return defaultValue;
        }
        if (!(value instanceof StateValue.BooleanValue bool)) {
            throw new OllamaProviderException("invalid_" + name, null, null);
        }
        return bool.value();
    }

    private static Instant optionalInstant(StateValue.ObjectValue object, String name) {
        String value = optionalString(object, name);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new OllamaProviderException("invalid_" + name, null, null);
        }
    }

    private static void putDecimal(Map<String, StateValue> target, String key, Double value) {
        if (value != null) {
            target.put(key, StateValue.number(BigDecimal.valueOf(value)));
        }
    }

    private static void putLong(Map<String, StateValue> target, String key, Number value) {
        if (value != null) {
            target.put(key, StateValue.integer(value.longValue()));
        }
    }

    static final class StreamAssembler {
        private final StrictJsonCodec codec;

        private final String requestId;

        private final LinkedHashMap<Integer, ToolAccumulator> tools = new LinkedHashMap<>();

        private String model;

        private Instant createdAt;

        private String reason;

        private UsageDetails usage;

        private Map<String, StateValue> terminalMetadata = Map.of();

        private boolean terminalSeen;

        private long sequence;

        StreamAssembler(StrictJsonCodec codec, String requestId) {
            this.codec = codec;
            this.requestId = requestId;
        }

        List<ChatResponseUpdate> accept(StateValue value) {
            StateValue.ObjectValue root = object(value);
            rejectError(root, null, requestId);
            if (terminalSeen) {
                throw new OllamaProviderException("event_after_terminal", null, requestId);
            }
            model = stable(model, optionalString(root, "model"));
            Instant eventCreated = optionalInstant(root, "created_at");
            if (eventCreated != null) {
                if (createdAt != null && !createdAt.equals(eventCreated)) {
                    throw new OllamaProviderException("inconsistent_created_at", null, requestId);
                }
                createdAt = eventCreated;
            }
            StateValue.ObjectValue message = optionalObject(root, "message");
            ArrayList<ChatResponseUpdate> updates = new ArrayList<>();
            if (message != null) {
                String thinking = optionalString(message, "thinking");
                if (thinking != null && !thinking.isEmpty()) {
                    updates.add(
                            update(List.of(new ReasoningContent("ollama-thinking", thinking)), null, null, Map.of()));
                }
                String text = optionalString(message, "content");
                if (text != null && !text.isEmpty()) {
                    updates.add(update(List.of(new TextContent(text)), null, null, Map.of()));
                }
                StateValue.ArrayValue toolCalls = optionalArray(message, "tool_calls");
                if (toolCalls != null) {
                    int fallback = 0;
                    for (StateValue call : toolCalls.values()) {
                        acceptTool(object(call), fallback++);
                    }
                }
            }
            if (bool(root, "done", false)) {
                terminalSeen = true;
                reason = optionalString(root, "done_reason");
                usage = usage(root);
                terminalMetadata = metadata(root, requestId, reason);
            }
            return List.copyOf(updates);
        }

        ChatResponseUpdate finish() {
            if (!terminalSeen) {
                throw new OllamaProviderException("missing_terminal", null, requestId);
            }
            ArrayList<Content> content = new ArrayList<>();
            tools.values().stream()
                    .sorted(Comparator.comparingInt(ToolAccumulator::index))
                    .map(tool -> tool.build(codec))
                    .forEach(content::add);
            return update(content, mapFinish(reason, true), usage, terminalMetadata);
        }

        private void acceptTool(StateValue.ObjectValue call, int fallbackIndex) {
            Long rawIndex = optionalLong(call, "index");
            int index = rawIndex == null ? fallbackIndex : Math.toIntExact(rawIndex);
            ToolAccumulator accumulator = tools.computeIfAbsent(index, ToolAccumulator::new);
            accumulator.id(stableToolField(accumulator.id, optionalString(call, "id"), "id"));
            StateValue.ObjectValue function = requiredObject(call, "function");
            accumulator.name(stableToolField(accumulator.name, optionalString(function, "name"), "name"));
            StateValue arguments = function.values().get("arguments");
            if (arguments != null && arguments != StateValue.NullValue.INSTANCE) {
                accumulator.arguments = mergeArguments(accumulator.arguments, arguments);
            }
        }

        private ChatResponseUpdate update(
                List<? extends Content> content,
                FinishReason finish,
                UsageDetails usageValue,
                Map<String, StateValue> metadataValue) {
            ChatResponseUpdate.Builder builder = ChatResponseUpdate.builder()
                    .sequence(sequence++)
                    .contents(content)
                    .role(Role.ASSISTANT)
                    .metadata(metadataValue);
            if (model != null) {
                builder.model(model);
            }
            if (createdAt != null) {
                builder.createdAt(createdAt);
            }
            if (finish != null) {
                builder.finishReason(finish);
            }
            if (usageValue != null) {
                builder.usage(usageValue);
            }
            return builder.build();
        }

        private static String stable(String current, String incoming) {
            if (incoming == null) {
                return current;
            }
            if (current != null && !current.equals(incoming)) {
                throw new OllamaProviderException("inconsistent_model", null, null);
            }
            return incoming;
        }

        private static String stableToolField(String current, String incoming, String field) {
            if (incoming == null || incoming.isEmpty()) {
                return current;
            }
            if (current == null || current.isEmpty()) {
                return incoming;
            }
            if (!current.equals(incoming)) {
                throw new OllamaProviderException("inconsistent_tool_" + field, null, null);
            }
            return current;
        }

        private static StateValue mergeArguments(StateValue current, StateValue incoming) {
            if (current == null || current == StateValue.NullValue.INSTANCE) {
                return incoming;
            }
            if (incoming == StateValue.NullValue.INSTANCE) {
                return current;
            }
            if (current instanceof StateValue.StringValue left && incoming instanceof StateValue.StringValue right) {
                return StateValue.string(left.value() + right.value());
            }
            if (current instanceof StateValue.ObjectValue left && incoming instanceof StateValue.ObjectValue right) {
                LinkedHashMap<String, StateValue> values = new LinkedHashMap<>(left.values());
                right.values().forEach(values::put);
                return StateValue.object(values);
            }
            throw new OllamaProviderException("incompatible_tool_arguments", null, null);
        }
    }

    private static final class ToolAccumulator {
        private final int index;

        private String id;

        private String name;

        private StateValue arguments;

        private ToolAccumulator(int index) {
            this.index = index;
        }

        int index() {
            return index;
        }

        void id(String value) {
            id = value;
        }

        void name(String value) {
            name = value;
        }

        FunctionCallContent build(StrictJsonCodec codec) {
            if (name == null || name.isBlank()) {
                throw new OllamaProviderException("incomplete_tool_call", null, null);
            }
            String callId = id;
            LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
            if (callId == null || callId.isBlank()) {
                callId = "ollama-tool-" + index;
                metadata.put("ollama.syntheticCallId", StateValue.bool(true));
                metadata.put("ollama.toolIndex", StateValue.integer(index));
            }
            StateValue parsed = arguments == null ? StateValue.object(Map.of()) : arguments;
            if (parsed instanceof StateValue.StringValue text) {
                parsed = text.value().isBlank()
                        ? StateValue.object(Map.of())
                        : codec.parse(text.value().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return new FunctionCallContent(callId, name, parsed, false, metadata);
        }
    }
}
