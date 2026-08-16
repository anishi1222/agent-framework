// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.mistral;

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
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StructuredOutputOptions;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.UriContent;
import com.microsoft.agents.core.UsageDetails;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.core.internal.StructuredOutputSupport;
import com.microsoft.agents.tools.ToolMetadata;
import com.microsoft.agents.tools.ToolMode;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MistralMessageMapper {
    private MistralMessageMapper() {}

    static StateValue.ObjectValue request(
            ChatClientRequest request, MistralChatClientOptions defaults, boolean stream) {
        MistralRequestValidator.validate(request);
        MistralJson json = new MistralJson(defaults);
        ChatOptions options = request.options();
        LinkedHashMap<String, StateValue> root = new LinkedHashMap<>();
        root.put("model", StateValue.string(options.model() == null ? defaults.model() : options.model()));
        root.put("messages", StateValue.array(messages(request, options, json)));
        root.put("stream", StateValue.bool(stream));
        putNumber(root, "temperature", options.temperature());
        putNumber(root, "top_p", options.topP());
        putInteger(root, "max_tokens", options.maxTokens());
        putInteger(root, "random_seed", options.seed());
        putNumber(root, "frequency_penalty", options.frequencyPenalty());
        putNumber(root, "presence_penalty", options.presencePenalty());
        if (!options.stop().isEmpty()) {
            root.put(
                    "stop",
                    StateValue.array(
                            options.stop().stream().map(StateValue::string).toList()));
        }
        if (!request.tools().isEmpty()) {
            root.put(
                    "tools",
                    StateValue.array(request.tools().stream()
                            .map(MistralMessageMapper::tool)
                            .toList()));
            root.put("tool_choice", StateValue.string(toolChoice(request.toolMode(), options)));
            if (options.allowMultipleToolCalls() != null) {
                root.put("parallel_tool_calls", StateValue.bool(options.allowMultipleToolCalls()));
            }
        }
        StructuredOutputOptions structuredOutput = StructuredOutputSupport.resolve(options, "mistral.responseSchema");
        if (structuredOutput != null) {
            LinkedHashMap<String, StateValue> jsonSchema = new LinkedHashMap<>();
            jsonSchema.put("name", StateValue.string(structuredOutput.name()));
            if (structuredOutput.description() != null) {
                jsonSchema.put("description", StateValue.string(structuredOutput.description()));
            }
            jsonSchema.put("strict", StateValue.bool(structuredOutput.strict()));
            jsonSchema.put("schema", structuredOutput.schema());
            root.put(
                    "response_format",
                    StateValue.object(Map.of(
                            "type", StateValue.string("json_schema"),
                            "json_schema", StateValue.object(jsonSchema))));
        }
        StateValue safePrompt = options.metadata().get("mistral.safePrompt");
        if (safePrompt != null) {
            root.put("safe_prompt", safePrompt);
        }
        return StateValue.object(root);
    }

    static ChatResponse response(StateValue value, String requestId, MistralJson json) {
        StateValue.ObjectValue root = object(value, "$");
        rejectError(root, null, requestId);
        String responseId = optionalString(root, "id");
        String model = optionalString(root, "model");
        Instant createdAt = optionalEpoch(root, "created");
        StateValue.ArrayValue choices = requiredArray(root, "choices", "$");
        if (choices.values().isEmpty()) {
            throw protocol("missing_choices", null, requestId);
        }
        ArrayList<Message> messages = new ArrayList<>();
        FinishReason finish = null;
        for (int index = 0; index < choices.values().size(); index++) {
            StateValue.ObjectValue choice = object(choices.values().get(index), "$.choices[" + index + "]");
            StateValue.ObjectValue providerMessage =
                    object(require(choice, "message", "$.choices[" + index + "]"), "$.choices[" + index + "].message");
            messages.add(mapAssistantMessage(providerMessage, json, responseId, index));
            FinishReason current = mapFinishReason(optionalString(choice, "finish_reason"));
            if (finish == null) {
                finish = current;
            } else if (current != null && !finish.equals(current)) {
                throw protocol("inconsistent_finish_reason", null, requestId);
            }
        }
        Map<String, StateValue> metadata = metadata(requestId, null);
        return ChatResponse.builder()
                .messages(messages)
                .responseId(responseId)
                .model(model)
                .createdAt(createdAt)
                .finishReason(finish)
                .usage(usage(optionalObject(root, "usage")))
                .metadata(metadata)
                .build();
    }

    private static List<StateValue> messages(ChatClientRequest request, ChatOptions options, MistralJson json) {
        ArrayList<StateValue> result = new ArrayList<>();
        if (options.instructions() != null) {
            result.add(StateValue.object(
                    Map.of("role", StateValue.string("system"), "content", StateValue.string(options.instructions()))));
        }
        LinkedHashMap<String, String> callNames = new LinkedHashMap<>();
        for (Message message : request.messages()) {
            if (message.role().equals(Role.TOOL)) {
                for (Content content : message.contents()) {
                    FunctionResultContent toolResult = (FunctionResultContent) content;
                    String name = callNames.get(toolResult.callId());
                    if (name == null) {
                        throw new ValidationException(
                                "Mistral tool result '" + toolResult.callId() + "' has no preceding function call.");
                    }
                    LinkedHashMap<String, StateValue> mapped = new LinkedHashMap<>();
                    mapped.put("role", StateValue.string("tool"));
                    mapped.put("tool_call_id", StateValue.string(toolResult.callId()));
                    mapped.put("name", StateValue.string(name));
                    mapped.put("content", StateValue.string(toolResultText(toolResult, json)));
                    result.add(StateValue.object(mapped));
                }
                continue;
            }
            LinkedHashMap<String, StateValue> mapped = new LinkedHashMap<>();
            mapped.put("role", StateValue.string(role(message.role())));
            ArrayList<FunctionCallContent> calls = new ArrayList<>();
            for (Content content : message.contents()) {
                if (content instanceof FunctionCallContent call) {
                    calls.add(call);
                    callNames.put(call.callId(), call.name());
                }
            }
            StateValue content = messageContent(message);
            if (content != null) {
                mapped.put("content", content);
            } else {
                mapped.put("content", StateValue.nullValue());
            }
            if (!calls.isEmpty()) {
                mapped.put(
                        "tool_calls",
                        StateValue.array(calls.stream()
                                .map(call -> functionCall(call, json))
                                .toList()));
            }
            result.add(StateValue.object(mapped));
        }
        return List.copyOf(result);
    }

    private static StateValue messageContent(Message message) {
        ArrayList<Content> content = new ArrayList<>();
        for (Content item : message.contents()) {
            if (!(item instanceof FunctionCallContent)) {
                content.add(item);
            }
        }
        if (content.isEmpty()) {
            return null;
        }
        boolean allText = content.stream().allMatch(TextContent.class::isInstance);
        if (allText) {
            return StateValue.string(content.stream()
                    .map(TextContent.class::cast)
                    .map(TextContent::text)
                    .reduce("", String::concat));
        }
        ArrayList<StateValue> parts = new ArrayList<>();
        for (Content item : content) {
            if (item instanceof TextContent text) {
                parts.add(StateValue.object(
                        Map.of("type", StateValue.string("text"), "text", StateValue.string(text.text()))));
            } else if (item instanceof DataContent data) {
                parts.add(imagePart(data.dataUri().toString()));
            } else if (item instanceof UriContent uri) {
                parts.add(imagePart(uri.uri().toString()));
            } else {
                throw new ValidationException("Unsupported Mistral content kind '" + item.kind() + "'.");
            }
        }
        return StateValue.array(parts);
    }

    private static StateValue imagePart(String uri) {
        return StateValue.object(Map.of(
                "type", StateValue.string("image_url"),
                "image_url", StateValue.object(Map.of("url", StateValue.string(uri)))));
    }

    private static StateValue functionCall(FunctionCallContent call, MistralJson json) {
        return StateValue.object(Map.of(
                "id",
                StateValue.string(call.callId()),
                "type",
                StateValue.string("function"),
                "function",
                StateValue.object(Map.of(
                        "name", StateValue.string(call.name()),
                        "arguments", StateValue.string(json.writeValue(call.arguments()))))));
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

    private static String toolResultText(FunctionResultContent result, MistralJson json) {
        if (result.error() != null) {
            return "Error: " + result.error();
        }
        if (result.result() instanceof StateValue.StringValue text) {
            return text.value();
        }
        return json.writeValue(result.result());
    }

    private static Message mapAssistantMessage(
            StateValue.ObjectValue message, MistralJson json, String responseId, int choiceIndex) {
        ArrayList<Content> content = new ArrayList<>();
        StateValue rawContent = message.values().get("content");
        if (rawContent instanceof StateValue.StringValue text && !text.value().isEmpty()) {
            content.add(new TextContent(text.value()));
        } else if (rawContent instanceof StateValue.ArrayValue parts) {
            for (StateValue item : parts.values()) {
                StateValue.ObjectValue part = object(item, "$.message.content[]");
                String type = requiredString(part, "type", "$.message.content[]");
                if ("text".equals(type)) {
                    content.add(new TextContent(requiredString(part, "text", "$.message.content[]")));
                } else {
                    throw protocol("unsupported_response_content", null, null);
                }
            }
        } else if (rawContent != null && rawContent != StateValue.NullValue.INSTANCE) {
            throw protocol("invalid_response_content", null, null);
        }
        StateValue.ArrayValue toolCalls = optionalArray(message, "tool_calls");
        if (toolCalls != null) {
            for (StateValue item : toolCalls.values()) {
                content.add(mapFunctionCall(object(item, "$.message.tool_calls[]"), json));
            }
        }
        if (content.isEmpty()) {
            throw protocol("empty_response_message", null, null);
        }
        String messageId = responseId == null ? null : responseId + ":" + choiceIndex;
        return Message.builder(Role.ASSISTANT)
                .contents(content)
                .messageId(messageId)
                .build();
    }

    private static FunctionCallContent mapFunctionCall(StateValue.ObjectValue call, MistralJson json) {
        String callId = requiredString(call, "id", "$.tool_call");
        StateValue.ObjectValue function = object(require(call, "function", "$.tool_call"), "$.tool_call.function");
        String name = requiredString(function, "name", "$.tool_call.function");
        StateValue arguments = require(function, "arguments", "$.tool_call.function");
        if (arguments instanceof StateValue.StringValue encoded) {
            arguments = encoded.value().isBlank() ? StateValue.object(Map.of()) : json.parseEvent(encoded.value());
        }
        return new FunctionCallContent(callId, name, arguments);
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
        throw new ValidationException("Unsupported Mistral role '" + role.value() + "'.");
    }

    private static String toolChoice(ToolMode mode, ChatOptions options) {
        if (mode == ToolMode.NONE && options.toolChoice() != null) {
            mode = switch (options.toolChoice()) {
                case NONE -> ToolMode.NONE;
                case AUTO -> ToolMode.AUTO;
                case REQUIRED -> ToolMode.REQUIRED;
            };
        }
        return switch (mode) {
            case NONE -> "none";
            case AUTO -> "auto";
            case REQUIRED -> "any";
        };
    }

    static FinishReason mapFinishReason(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "stop" -> FinishReason.STOP;
            case "length", "model_length" -> FinishReason.LENGTH;
            case "tool_calls", "tool_call" -> FinishReason.TOOL_CALLS;
            case "content_filter", "safety" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.of(value);
        };
    }

    static UsageDetails usage(StateValue.ObjectValue usage) {
        if (usage == null) {
            return null;
        }
        UsageDetails.Builder builder = UsageDetails.builder();
        putUsage(builder, usage, "prompt_tokens", UsageDetails.INPUT_TOKENS);
        putUsage(builder, usage, "completion_tokens", UsageDetails.OUTPUT_TOKENS);
        putUsage(builder, usage, "total_tokens", UsageDetails.TOTAL_TOKENS);
        StateValue.ObjectValue details = optionalObject(usage, "prompt_tokens_details");
        if (details != null) {
            putUsage(builder, details, "cached_tokens", UsageDetails.CACHE_READ_INPUT_TOKENS);
        }
        UsageDetails result = builder.build();
        return result.values().isEmpty() ? null : result;
    }

    static void rejectError(StateValue.ObjectValue root, Integer statusCode, String requestId) {
        StateValue error = root.values().get("error");
        if (error == null) {
            return;
        }
        String code = null;
        if (error instanceof StateValue.ObjectValue object) {
            code = optionalString(object, "code");
            if (code == null) {
                code = optionalString(object, "type");
            }
        }
        throw new MistralProviderException("provider_error", statusCode, requestId, code);
    }

    static Map<String, StateValue> metadata(String requestId, String rawFinishReason) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        if (requestId != null && !requestId.isBlank()) {
            values.put("mistral.requestId", StateValue.string(requestId));
        }
        if (rawFinishReason != null && !rawFinishReason.isBlank()) {
            values.put("mistral.finishReason", StateValue.string(rawFinishReason));
        }
        return Map.copyOf(values);
    }

    static StateValue require(StateValue.ObjectValue object, String name, String path) {
        StateValue value = object.values().get(name);
        if (value == null) {
            throw protocol("missing_" + name, null, null);
        }
        return value;
    }

    static StateValue.ObjectValue object(StateValue value, String path) {
        if (!(value instanceof StateValue.ObjectValue object)) {
            throw protocol("expected_object", null, null);
        }
        return object;
    }

    static StateValue.ArrayValue requiredArray(StateValue.ObjectValue object, String name, String path) {
        StateValue value = require(object, name, path);
        if (!(value instanceof StateValue.ArrayValue array)) {
            throw protocol("expected_array", null, null);
        }
        return array;
    }

    static StateValue.ArrayValue optionalArray(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null || value == StateValue.NullValue.INSTANCE) {
            return null;
        }
        if (!(value instanceof StateValue.ArrayValue array)) {
            throw protocol("expected_array", null, null);
        }
        return array;
    }

    static StateValue.ObjectValue optionalObject(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null || value == StateValue.NullValue.INSTANCE) {
            return null;
        }
        return object(value, "$." + name);
    }

    static String requiredString(StateValue.ObjectValue object, String name, String path) {
        String value = optionalString(object, name);
        if (value == null || value.isBlank()) {
            throw protocol("missing_" + name, null, null);
        }
        return value;
    }

    static String optionalString(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null || value == StateValue.NullValue.INSTANCE) {
            return null;
        }
        if (!(value instanceof StateValue.StringValue string)) {
            throw protocol("expected_string", null, null);
        }
        return string.value();
    }

    static Long optionalLong(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null || value == StateValue.NullValue.INSTANCE) {
            return null;
        }
        if (!(value instanceof StateValue.NumberValue number)) {
            throw protocol("expected_integer", null, null);
        }
        try {
            return number.value().longValueExact();
        } catch (ArithmeticException exception) {
            throw protocol("expected_integer", null, null);
        }
    }

    private static Instant optionalEpoch(StateValue.ObjectValue object, String name) {
        Long value = optionalLong(object, name);
        return value == null ? null : Instant.ofEpochSecond(value);
    }

    private static void putNumber(Map<String, StateValue> target, String name, Double value) {
        if (value != null) {
            target.put(name, StateValue.number(java.math.BigDecimal.valueOf(value)));
        }
    }

    private static void putInteger(Map<String, StateValue> target, String name, Number value) {
        if (value != null) {
            target.put(name, StateValue.integer(BigInteger.valueOf(value.longValue())));
        }
    }

    private static void putUsage(
            UsageDetails.Builder builder, StateValue.ObjectValue source, String sourceName, String targetName) {
        Long value = optionalLong(source, sourceName);
        if (value != null && value >= 0) {
            builder.value(targetName, StateValue.integer(value));
        }
    }

    private static MistralProviderException protocol(String kind, Integer status, String requestId) {
        return new MistralProviderException(kind, status, requestId, "invalid_response");
    }

    static final class StreamAssembler {
        private final MistralJson json;

        private final String requestId;

        private final LinkedHashMap<Integer, ToolAccumulator> tools = new LinkedHashMap<>();

        private String responseId;

        private String model;

        private Instant createdAt;

        private FinishReason finishReason;

        private String rawFinishReason;

        private UsageDetails usage;

        private long sequence;

        private boolean done;

        StreamAssembler(MistralJson json, String requestId) {
            this.json = json;
            this.requestId = requestId;
        }

        List<ChatResponseUpdate> accept(String payload) {
            if (done) {
                throw protocol("event_after_done", null, requestId);
            }
            StateValue.ObjectValue root = object(json.parseEvent(payload), "$");
            rejectError(root, null, requestId);
            responseId = stable(responseId, optionalString(root, "id"), "response id");
            model = stable(model, optionalString(root, "model"), "model");
            Long created = optionalLong(root, "created");
            if (created != null) {
                Instant incoming = Instant.ofEpochSecond(created);
                if (createdAt != null && !createdAt.equals(incoming)) {
                    throw protocol("inconsistent_created", null, requestId);
                }
                createdAt = incoming;
            }
            UsageDetails incomingUsage = usage(optionalObject(root, "usage"));
            if (incomingUsage != null) {
                usage = incomingUsage;
            }
            StateValue.ArrayValue choices = optionalArray(root, "choices");
            if (choices == null || choices.values().isEmpty()) {
                return List.of();
            }
            if (choices.values().size() != 1) {
                throw protocol("multiple_stream_choices", null, requestId);
            }
            StateValue.ObjectValue choice = object(choices.values().getFirst(), "$.choices[0]");
            Long index = optionalLong(choice, "index");
            if (index != null && index != 0) {
                throw protocol("unsupported_choice_index", null, requestId);
            }
            StateValue.ObjectValue delta = optionalObject(choice, "delta");
            ArrayList<ChatResponseUpdate> updates = new ArrayList<>();
            if (delta != null) {
                String text = optionalString(delta, "content");
                if (text != null && !text.isEmpty()) {
                    if (finishReason != null) {
                        throw protocol("content_after_finish", null, requestId);
                    }
                    updates.add(update(List.of(new TextContent(text)), null, null));
                }
                StateValue.ArrayValue toolDeltas = optionalArray(delta, "tool_calls");
                if (toolDeltas != null) {
                    if (finishReason != null) {
                        throw protocol("tool_after_finish", null, requestId);
                    }
                    for (StateValue item : toolDeltas.values()) {
                        acceptToolDelta(object(item, "$.choices[0].delta.tool_calls[]"));
                    }
                }
            }
            String finish = optionalString(choice, "finish_reason");
            if (finish != null) {
                if (finishReason != null) {
                    throw protocol("duplicate_terminal", null, requestId);
                }
                rawFinishReason = finish;
                finishReason = mapFinishReason(finish);
            }
            return List.copyOf(updates);
        }

        ChatResponseUpdate finish() {
            if (done) {
                throw protocol("duplicate_done", null, requestId);
            }
            done = true;
            if (finishReason == null) {
                throw protocol("missing_terminal", null, requestId);
            }
            ArrayList<Content> terminalContent = new ArrayList<>();
            tools.values().stream()
                    .sorted(Comparator.comparingInt(ToolAccumulator::index))
                    .map(tool -> tool.build(json))
                    .forEach(terminalContent::add);
            return update(terminalContent, finishReason, usage);
        }

        private void acceptToolDelta(StateValue.ObjectValue delta) {
            Long indexValue = optionalLong(delta, "index");
            int index = indexValue == null ? 0 : Math.toIntExact(indexValue);
            ToolAccumulator accumulator = tools.computeIfAbsent(index, ToolAccumulator::new);
            accumulator.id(stableToolField(accumulator.id(), optionalString(delta, "id"), "id"));
            StateValue.ObjectValue function = optionalObject(delta, "function");
            if (function != null) {
                accumulator.name(stableToolField(accumulator.name(), optionalString(function, "name"), "name"));
                accumulator.arguments(concatenateDelta(accumulator.arguments(), optionalString(function, "arguments")));
            }
        }

        private ChatResponseUpdate update(
                List<? extends Content> contents, FinishReason finish, UsageDetails usageValue) {
            ChatResponseUpdate.Builder builder = ChatResponseUpdate.builder()
                    .sequence(sequence++)
                    .contents(contents)
                    .role(Role.ASSISTANT)
                    .metadata(metadata(requestId, rawFinishReason));
            if (responseId != null) {
                builder.responseId(responseId).messageId(responseId + ":0");
            }
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

        private static String stable(String current, String incoming, String name) {
            if (incoming == null) {
                return current;
            }
            if (current != null && !current.equals(incoming)) {
                throw protocol("inconsistent_" + name.replace(' ', '_'), null, null);
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
                throw protocol("inconsistent_tool_" + field, null, null);
            }
            return current;
        }

        private static String concatenateDelta(String current, String incoming) {
            if (incoming == null) {
                return current;
            }
            return current == null ? incoming : current + incoming;
        }
    }

    private static final class ToolAccumulator {
        private final int index;

        private String id;

        private String name;

        private String arguments;

        private ToolAccumulator(int index) {
            this.index = index;
        }

        int index() {
            return index;
        }

        String id() {
            return id;
        }

        void id(String value) {
            id = value;
        }

        String name() {
            return name;
        }

        void name(String value) {
            name = value;
        }

        String arguments() {
            return arguments;
        }

        void arguments(String value) {
            arguments = value;
        }

        FunctionCallContent build(MistralJson json) {
            if (id == null || id.isBlank() || name == null || name.isBlank()) {
                throw protocol("incomplete_tool_call", null, null);
            }
            StateValue parsed =
                    arguments == null || arguments.isBlank() ? StateValue.object(Map.of()) : json.parseEvent(arguments);
            return new FunctionCallContent(id, name, parsed);
        }
    }
}
