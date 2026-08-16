// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.openai;

import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.ReasoningContent;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.UriContent;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.hosting.HostingError;
import com.microsoft.agents.hosting.HostingErrorCode;
import com.microsoft.agents.hosting.HostingException;
import com.microsoft.agents.hosting.HostingJsonCodec;
import com.microsoft.agents.hosting.HostingLimits;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Implements a bounded strict subset of the OpenAI Responses request, response, and SSE wire
 * contract.
 *
 * <p>The codec rejects duplicate members, trailing JSON, non-finite numbers, unknown request
 * members, unsupported item/content discriminators, and configured parser-limit violations. It
 * exposes no JSON-library types.
 */
public final class OpenAIResponsesJsonCodec {
    /** OpenAI Responses JSON response media type. */
    public static final String JSON_MEDIA_TYPE = "application/json; charset=utf-8";

    /** OpenAI Responses streaming media type. */
    public static final String SSE_MEDIA_TYPE = "text/event-stream; charset=utf-8";

    private static final Pattern EVENT_NAME = Pattern.compile("[a-z0-9._-]{1,128}");

    private static final Set<String> REQUEST_FIELDS = Set.of(
            "input",
            "model",
            "instructions",
            "max_output_tokens",
            "temperature",
            "top_p",
            "parallel_tool_calls",
            "metadata",
            "stream",
            "previous_response_id",
            "conversation",
            "store",
            "tools",
            "tool_choice",
            "max_tool_calls",
            "user");

    private static final Set<String> MESSAGE_FIELDS = Set.of("type", "role", "content", "id", "name", "status");

    private static final Set<String> FUNCTION_CALL_FIELDS =
            Set.of("type", "id", "call_id", "name", "arguments", "status");

    private static final Set<String> FUNCTION_RESULT_FIELDS = Set.of("type", "id", "call_id", "output", "status");

    private static final Set<String> REASONING_FIELDS = Set.of("type", "id", "summary", "encrypted_content", "status");

    private static final Set<String> TEXT_CONTENT_FIELDS = Set.of("type", "text", "annotations", "logprobs");

    private static final Set<String> REFUSAL_CONTENT_FIELDS = Set.of("type", "refusal");

    private static final Set<String> IMAGE_CONTENT_FIELDS = Set.of("type", "image_url", "detail");

    private static final Set<String> FILE_CONTENT_FIELDS =
            Set.of("type", "file_url", "file_data", "filename", "detail");

    private static final Set<String> TOOL_FIELDS = Set.of("type", "name", "description", "parameters", "strict");

    private static final Set<String> TOOL_CHOICE_FIELDS = Set.of("type", "name");

    private static final Set<String> CONVERSATION_FIELDS = Set.of("id");

    private static final Role DEVELOPER = Role.of("developer");

    private final HostingLimits limits;

    private final HostingJsonCodec json;

    private final OpenAIResponsesHostingOptions options;

    /**
     * Creates a codec using mandatory generic hosting limits and protocol options.
     *
     * @param limits shared hosting limits
     * @param options OpenAI Responses mapping options
     */
    public OpenAIResponsesJsonCodec(HostingLimits limits, OpenAIResponsesHostingOptions options) {
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
        this.options = java.util.Objects.requireNonNull(options, "options");
        json = new HostingJsonCodec(limits);
    }

    /**
     * Decodes one complete strict OpenAI Responses create request.
     *
     * @param utf8Json request bytes
     * @return mapped framework request
     */
    public OpenAIResponsesRunRequest decodeRunRequest(byte[] utf8Json) {
        try {
            return decodeValidatedRunRequest(utf8Json);
        } catch (HostingException failure) {
            throw failure;
        } catch (ValidationException | IllegalArgumentException failure) {
            throw requestError(
                    null,
                    "invalid_parameter",
                    "OpenAI Responses request contains an invalid framework value.",
                    failure);
        }
    }

    private OpenAIResponsesRunRequest decodeValidatedRunRequest(byte[] utf8Json) {
        StateValue.ObjectValue root = json.decodeObject(utf8Json);
        rejectUnknown(root, REQUEST_FIELDS, "OpenAI Responses request");
        StateValue input = require(root, "input");
        String model = optionalString(root, "model");
        String instructions = optionalString(root, "instructions");
        Double temperature = optionalDouble(root, "temperature");
        Double topP = optionalDouble(root, "top_p");
        Integer maxOutputTokens = optionalPositiveInteger(root, "max_output_tokens");
        Boolean parallelToolCalls = optionalBoolean(root, "parallel_tool_calls");
        Integer maxToolCalls = optionalPositiveInteger(root, "max_tool_calls");
        Map<String, String> metadata = optionalStringMap(root, "metadata");
        String user = optionalString(root, "user");
        List<StateValue.ObjectValue> tools = decodeTools(optionalArray(root, "tools"));
        StateValue toolChoice = decodeToolChoice(optional(root, "tool_choice"));
        OpenAIResponsesRequestInfo requestInfo;
        try {
            requestInfo = new OpenAIResponsesRequestInfo(
                    model,
                    temperature,
                    topP,
                    maxOutputTokens,
                    instructions,
                    tools,
                    toolChoice,
                    parallelToolCalls,
                    maxToolCalls,
                    metadata,
                    user);
        } catch (IllegalArgumentException failure) {
            throw requestError(null, "invalid_parameter", "OpenAI Responses request settings are invalid.", failure);
        }

        RunOptions mapped = mapOptions(requestInfo);
        String previousResponseId = optionalString(root, "previous_response_id");
        String conversationId = decodeConversation(optional(root, "conversation"));
        if (previousResponseId != null && conversationId != null) {
            throw requestError(
                    "conversation",
                    "invalid_parameter",
                    "conversation and previous_response_id are mutually exclusive.");
        }
        List<Message> messages = decodeInput(input);
        if (instructions != null) {
            ArrayList<Message> withInstructions = new ArrayList<>(messages.size() + 1);
            withInstructions.add(Message.text(DEVELOPER, instructions));
            withInstructions.addAll(messages);
            messages = List.copyOf(withInstructions);
        }
        RunOptions runOptions = mergeMappedOptions(mapped, requestInfo, previousResponseId, conversationId);
        return new OpenAIResponsesRunRequest(
                messages,
                runOptions,
                requestInfo,
                optionalBoolean(root, "stream", false),
                previousResponseId,
                conversationId,
                optionalBoolean(root, "store", true));
    }

    /**
     * Encodes one framework-owned JSON value canonically under the shared response bounds.
     *
     * @param value response value
     * @return compact UTF-8 JSON
     */
    public byte[] encodeValue(StateValue value) {
        return json.encodeValue(java.util.Objects.requireNonNull(value, "value"));
    }

    /**
     * Encodes one OpenAI Responses SSE frame.
     *
     * @param event event name
     * @param data event data
     * @return complete UTF-8 frame ending in a blank line
     */
    public byte[] encodeSseFrame(String event, StateValue.ObjectValue data) {
        java.util.Objects.requireNonNull(event, "event");
        java.util.Objects.requireNonNull(data, "data");
        if (!EVENT_NAME.matcher(event).matches()) {
            throw new HostingException(HostingErrorCode.INTERNAL_ERROR, "OpenAI Responses event name is invalid.");
        }
        byte[] jsonBytes = json.encodeValue(data);
        byte[] prefix = ("event: " + event + "\ndata: ").getBytes(StandardCharsets.UTF_8);
        long total = (long) prefix.length + jsonBytes.length + 2;
        if (total > limits.maxResponseBytes()) {
            throw new HostingException(
                    HostingErrorCode.OVERFLOW, "Encoded OpenAI Responses SSE frame exceeds maxResponseBytes.");
        }
        byte[] frame = new byte[Math.toIntExact(total)];
        System.arraycopy(prefix, 0, frame, 0, prefix.length);
        System.arraycopy(jsonBytes, 0, frame, prefix.length, jsonBytes.length);
        frame[frame.length - 2] = '\n';
        frame[frame.length - 1] = '\n';
        return frame;
    }

    /**
     * Returns the shared generic hosting limits.
     *
     * @return limits
     */
    public HostingLimits limits() {
        return limits;
    }

    StateValue.ObjectValue errorValue(HostingError error) {
        java.util.Objects.requireNonNull(error, "error");
        String type =
                switch (error.code()) {
                    case UNAUTHENTICATED -> "authentication_error";
                    case FORBIDDEN -> "permission_error";
                    case INTERNAL_ERROR, RUN_TIMEOUT -> "server_error";
                    default -> "invalid_request_error";
                };
        String code = detailString(error, "openaiCode");
        String parameter = detailString(error, "param");
        LinkedHashMap<String, StateValue> body = new LinkedHashMap<>();
        body.put("message", StateValue.string(error.message()));
        body.put("type", StateValue.string(type));
        body.put("param", parameter == null ? StateValue.nullValue() : StateValue.string(parameter));
        body.put("code", StateValue.string(code == null ? error.code().value() : code));
        return StateValue.object(Map.of("error", StateValue.object(body)));
    }

    private RunOptions mapOptions(OpenAIResponsesRequestInfo requestInfo) {
        try {
            RunOptions mapped = options.runOptionsMapper().map(requestInfo);
            if (mapped == null) {
                throw new HostingException(
                        HostingErrorCode.INTERNAL_ERROR, "OpenAI Responses run-options mapper returned null.");
            }
            return mapped;
        } catch (HostingException failure) {
            throw failure;
        } catch (IllegalArgumentException | UnsupportedOperationException failure) {
            throw requestError(
                    null, "unsupported_parameter", "OpenAI Responses request settings were rejected.", failure);
        } catch (RuntimeException failure) {
            throw new HostingException(
                    HostingErrorCode.INTERNAL_ERROR, "OpenAI Responses run-options mapping failed.", failure);
        }
    }

    private RunOptions mergeMappedOptions(
            RunOptions mapped, OpenAIResponsesRequestInfo request, String previousResponseId, String conversationId) {
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(mapped.metadata());
        putString(metadata, "openai.model", request.model());
        putNumber(metadata, "openai.temperature", request.temperature());
        putNumber(metadata, "openai.topP", request.topP());
        if (request.maxOutputTokens() != null) {
            metadata.put("openai.maxOutputTokens", StateValue.integer(request.maxOutputTokens()));
        }
        if (request.parallelToolCalls() != null) {
            metadata.put("openai.parallelToolCalls", StateValue.bool(request.parallelToolCalls()));
        }
        if (!request.tools().isEmpty()) {
            metadata.put("openai.tools", StateValue.array(request.tools()));
        }
        if (request.toolChoice() != null) {
            metadata.put("openai.toolChoice", request.toolChoice());
        }
        if (!request.metadata().isEmpty()) {
            LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
            request.metadata().forEach((key, value) -> values.put(key, StateValue.string(value)));
            metadata.put("openai.metadata", StateValue.object(values));
        }
        putString(metadata, "openai.user", request.user());
        putString(metadata, "openai.previousResponseId", previousResponseId);
        putString(metadata, "openai.conversationId", conversationId);
        Integer maxFunctionCalls = mapped.maxFunctionCalls();
        if (maxFunctionCalls == null) {
            maxFunctionCalls = request.maxToolCalls();
        }
        return new RunOptions(mapped.maxIterations(), maxFunctionCalls, metadata);
    }

    private List<Message> decodeInput(StateValue input) {
        if (input instanceof StateValue.StringValue string) {
            return List.of(Message.text(Role.USER, string.value()));
        }
        if (!(input instanceof StateValue.ArrayValue array)) {
            throw requestError(
                    "input", "invalid_type", "input must be a string or an array of OpenAI Responses input items.");
        }
        if (array.values().isEmpty()) {
            throw requestError("input", "invalid_parameter", "input must not be empty.");
        }
        ArrayList<Message> messages = new ArrayList<>();
        for (StateValue value : array.values()) {
            if (value instanceof StateValue.StringValue string) {
                messages.add(Message.text(Role.USER, string.value()));
                continue;
            }
            StateValue.ObjectValue item = requireObject(value, "input item");
            String type = optionalString(item, "type");
            if (type == null || "message".equals(type)) {
                messages.add(decodeMessage(item));
            } else if ("function_call".equals(type)) {
                messages.add(decodeFunctionCall(item));
            } else if ("function_call_output".equals(type)) {
                messages.add(decodeFunctionResult(item));
            } else if ("reasoning".equals(type)) {
                messages.add(decodeReasoning(item));
            } else {
                throw requestError(
                        "input", "unsupported_value", "Unsupported OpenAI Responses input item type '" + type + "'.");
            }
        }
        return List.copyOf(messages);
    }

    private Message decodeMessage(StateValue.ObjectValue item) {
        rejectUnknown(item, MESSAGE_FIELDS, "OpenAI Responses message");
        String roleValue = requireString(item, "role");
        Role role =
                switch (roleValue) {
                    case "system" -> Role.SYSTEM;
                    case "developer" -> DEVELOPER;
                    case "user" -> Role.USER;
                    case "assistant" -> Role.ASSISTANT;
                    case "tool" -> Role.TOOL;
                    default ->
                        throw requestError(
                                "input",
                                "unsupported_value",
                                "Unsupported OpenAI Responses message role '" + roleValue + "'.");
                };
        List<Content> contents = decodeMessageContent(require(item, "content"), role);
        if (contents.isEmpty()) {
            throw requestError("input", "invalid_parameter", "Message content must not be empty.");
        }
        return new Message(role, contents, optionalString(item, "name"), optionalString(item, "id"), Map.of());
    }

    private List<Content> decodeMessageContent(StateValue value, Role role) {
        if (value instanceof StateValue.StringValue string) {
            return List.of(new TextContent(string.value()));
        }
        if (!(value instanceof StateValue.ArrayValue array)) {
            throw requestError("input", "invalid_type", "Message content must be a string or an array.");
        }
        ArrayList<Content> contents = new ArrayList<>(array.values().size());
        for (StateValue entry : array.values()) {
            contents.add(decodeContent(requireObject(entry, "message content"), role));
        }
        return List.copyOf(contents);
    }

    private Content decodeContent(StateValue.ObjectValue content, Role role) {
        String type = requireString(content, "type");
        return switch (type) {
            case "input_text", "output_text" -> {
                rejectUnknown(content, TEXT_CONTENT_FIELDS, "OpenAI Responses text content");
                yield new TextContent(requireString(content, "text"));
            }
            case "refusal" -> {
                rejectUnknown(content, REFUSAL_CONTENT_FIELDS, "OpenAI Responses refusal content");
                yield new TextContent(
                        requireString(content, "refusal"), Map.of("openai.refusal", StateValue.bool(true)));
            }
            case "input_image" -> decodeImage(content, role);
            case "input_file" -> decodeFile(content, role);
            default ->
                throw requestError(
                        "input", "unsupported_value", "Unsupported OpenAI Responses content type '" + type + "'.");
        };
    }

    private Content decodeImage(StateValue.ObjectValue content, Role role) {
        rejectUnknown(content, IMAGE_CONTENT_FIELDS, "OpenAI Responses image content");
        if (!Role.USER.equals(role)) {
            throw requestError(
                    "input", "unsupported_value", "input_image content is supported only for user messages.");
        }
        String value = requireString(content, "image_url");
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        String detail = optionalString(content, "detail");
        requireChoice(detail, Set.of("auto", "low", "high", "original"), "input_image.detail");
        putString(metadata, "detail", detail);
        if (value.startsWith("data:")) {
            DataContent decoded = DataContent.fromDataUri(value);
            if (!decoded.mediaType().toLowerCase(Locale.ROOT).startsWith("image/")) {
                throw requestError("input", "invalid_value", "input_image data URI must use an image media type.");
            }
            return new DataContent(decoded.data(), decoded.mediaType(), metadata);
        }
        return new UriContent(requireRemoteUri(value, "input_image.image_url"), "image/*", metadata);
    }

    private Content decodeFile(StateValue.ObjectValue content, Role role) {
        rejectUnknown(content, FILE_CONTENT_FIELDS, "OpenAI Responses file content");
        if (!Role.USER.equals(role)) {
            throw requestError("input", "unsupported_value", "input_file content is supported only for user messages.");
        }
        String fileUrl = optionalString(content, "file_url");
        String fileData = optionalString(content, "file_data");
        if ((fileUrl == null) == (fileData == null)) {
            throw requestError(
                    "input", "invalid_parameter", "input_file requires exactly one of file_url or file_data.");
        }
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        putString(metadata, "filename", optionalString(content, "filename"));
        String detail = optionalString(content, "detail");
        requireChoice(detail, Set.of("auto", "low", "high"), "input_file.detail");
        putString(metadata, "detail", detail);
        String value = fileUrl == null ? fileData : fileUrl;
        if (value.startsWith("data:")) {
            DataContent decoded = DataContent.fromDataUri(value);
            return new DataContent(decoded.data(), decoded.mediaType(), metadata);
        }
        return new UriContent(requireRemoteUri(value, "input_file URI"), "application/octet-stream", metadata);
    }

    private URI requireRemoteUri(String value, String parameter) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (!uri.isAbsolute()
                    || uri.isOpaque()
                    || uri.getHost() == null
                    || scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("URI must be an absolute HTTP(S) URI.");
            }
            return uri;
        } catch (IllegalArgumentException failure) {
            throw requestError(
                    "input",
                    "invalid_value",
                    parameter + " must be an absolute HTTP(S) URI or base64 data URI.",
                    failure);
        }
    }

    private Message decodeFunctionCall(StateValue.ObjectValue item) {
        rejectUnknown(item, FUNCTION_CALL_FIELDS, "OpenAI Responses function call");
        String callId = requireString(item, "call_id");
        String name = requireString(item, "name");
        StateValue arguments = decodeArguments(require(item, "arguments"));
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        putString(metadata, "openai.itemId", optionalString(item, "id"));
        return new Message(Role.ASSISTANT, List.of(new FunctionCallContent(callId, name, arguments, true, metadata)));
    }

    private Message decodeFunctionResult(StateValue.ObjectValue item) {
        rejectUnknown(item, FUNCTION_RESULT_FIELDS, "OpenAI Responses function result");
        String callId = requireString(item, "call_id");
        StateValue output = decodeFunctionOutput(require(item, "output"));
        return new Message(Role.TOOL, List.of(new FunctionResultContent(callId, output)));
    }

    private Message decodeReasoning(StateValue.ObjectValue item) {
        rejectUnknown(item, REASONING_FIELDS, "OpenAI Responses reasoning item");
        String id = optionalString(item, "id");
        String protectedData = optionalString(item, "encrypted_content");
        String text = null;
        StateValue summaryValue = optional(item, "summary");
        if (summaryValue instanceof StateValue.ArrayValue summary
                && !summary.values().isEmpty()) {
            ArrayList<String> parts = new ArrayList<>();
            for (StateValue entry : summary.values()) {
                StateValue.ObjectValue object = requireObject(entry, "reasoning summary");
                rejectUnknown(object, Set.of("type", "text"), "OpenAI Responses reasoning summary");
                if (!"summary_text".equals(requireString(object, "type"))) {
                    throw requestError(
                            "input", "unsupported_value", "Unsupported OpenAI Responses reasoning summary type.");
                }
                parts.add(requireString(object, "text"));
            }
            text = String.join("", parts);
        } else if (summaryValue != null) {
            throw requestError("input", "invalid_type", "Reasoning summary must be an array when present.");
        }
        if (text == null && protectedData == null) {
            throw requestError(
                    "input", "invalid_parameter", "Reasoning input requires summary text or encrypted_content.");
        }
        return new Message(Role.ASSISTANT, List.of(new ReasoningContent(id, text, protectedData, Map.of())));
    }

    private List<StateValue.ObjectValue> decodeTools(List<StateValue> values) {
        ArrayList<StateValue.ObjectValue> tools = new ArrayList<>(values.size());
        for (StateValue value : values) {
            StateValue.ObjectValue tool = requireObject(value, "tool");
            rejectUnknown(tool, TOOL_FIELDS, "OpenAI Responses tool");
            if (!"function".equals(requireString(tool, "type"))) {
                throw requestError("tools", "unsupported_value", "Only function tool declarations are supported.");
            }
            requireString(tool, "name");
            optionalString(tool, "description");
            requireObject(require(tool, "parameters"), "tool parameters");
            optionalBoolean(tool, "strict");
            tools.add(tool);
        }
        return List.copyOf(tools);
    }

    private StateValue decodeToolChoice(StateValue value) {
        if (value == null || value == StateValue.NullValue.INSTANCE) {
            return null;
        }
        if (value instanceof StateValue.StringValue string) {
            if (!Set.of("none", "auto", "required").contains(string.value())) {
                throw requestError(
                        "tool_choice", "unsupported_value", "tool_choice string must be none, auto, or required.");
            }
            return value;
        }
        StateValue.ObjectValue object = requireObject(value, "tool_choice");
        rejectUnknown(object, TOOL_CHOICE_FIELDS, "OpenAI Responses tool_choice");
        if (!"function".equals(requireString(object, "type"))) {
            throw requestError("tool_choice", "unsupported_value", "Only function tool_choice objects are supported.");
        }
        requireString(object, "name");
        return object;
    }

    private String decodeConversation(StateValue value) {
        if (value == null || value == StateValue.NullValue.INSTANCE) {
            return null;
        }
        if (value instanceof StateValue.StringValue string) {
            return requireNonBlank(string.value(), "conversation");
        }
        StateValue.ObjectValue object = requireObject(value, "conversation");
        rejectUnknown(object, CONVERSATION_FIELDS, "OpenAI Responses conversation");
        return requireString(object, "id");
    }

    private StateValue decodeArguments(StateValue value) {
        if (value instanceof StateValue.StringValue string) {
            StateValue parsed = parseEmbeddedJson(string.value(), "function arguments");
            if (parsed instanceof StateValue.ObjectValue) {
                return parsed;
            }
            throw requestError("input", "invalid_type", "Function-call arguments JSON must contain an object.");
        }
        if (value instanceof StateValue.ObjectValue) {
            return value;
        }
        throw requestError(
                "input", "invalid_type", "Function-call arguments must be a JSON object or encoded JSON string.");
    }

    private StateValue decodeFunctionOutput(StateValue value) {
        if (!(value instanceof StateValue.StringValue string)) {
            return value;
        }
        try {
            return parseEmbeddedJson(string.value(), "function output");
        } catch (HostingException ignored) {
            return value;
        }
    }

    private StateValue parseEmbeddedJson(String text, String name) {
        byte[] value = text.getBytes(StandardCharsets.UTF_8);
        byte[] prefix = "{\"value\":".getBytes(StandardCharsets.UTF_8);
        if ((long) prefix.length + value.length + 1 > limits.maxRequestBytes()) {
            throw requestError("input", "invalid_value", name + " exceeds the configured request bound.");
        }
        byte[] wrapped = new byte[prefix.length + value.length + 1];
        System.arraycopy(prefix, 0, wrapped, 0, prefix.length);
        System.arraycopy(value, 0, wrapped, prefix.length, value.length);
        wrapped[wrapped.length - 1] = '}';
        try {
            return json.decodeObject(wrapped).require("value");
        } catch (HostingException failure) {
            throw requestError("input", "invalid_json", name + " must contain valid JSON.", failure);
        }
    }

    private static String detailString(HostingError error, String name) {
        StateValue value = error.details().get(name);
        return value instanceof StateValue.StringValue string ? string.value() : null;
    }

    private static StateValue require(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null || value == StateValue.NullValue.INSTANCE) {
            throw requestError(name, "missing_required_parameter", "Missing required parameter '" + name + "'.");
        }
        return value;
    }

    private static StateValue optional(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        return value == StateValue.NullValue.INSTANCE ? null : value;
    }

    private static String requireString(StateValue.ObjectValue object, String name) {
        StateValue value = require(object, name);
        if (value instanceof StateValue.StringValue string) {
            return requireNonBlank(string.value(), name);
        }
        throw requestError(name, "invalid_type", "Parameter '" + name + "' must be a string.");
    }

    private static String optionalString(StateValue.ObjectValue object, String name) {
        StateValue value = optional(object, name);
        if (value == null) {
            return null;
        }
        if (value instanceof StateValue.StringValue string) {
            return requireNonBlank(string.value(), name);
        }
        throw requestError(name, "invalid_type", "Parameter '" + name + "' must be a string.");
    }

    private static Boolean optionalBoolean(StateValue.ObjectValue object, String name) {
        StateValue value = optional(object, name);
        if (value == null) {
            return null;
        }
        if (value instanceof StateValue.BooleanValue bool) {
            return bool.value();
        }
        throw requestError(name, "invalid_type", "Parameter '" + name + "' must be a Boolean.");
    }

    private static boolean optionalBoolean(StateValue.ObjectValue object, String name, boolean defaultValue) {
        Boolean value = optionalBoolean(object, name);
        return value == null ? defaultValue : value;
    }

    private static Double optionalDouble(StateValue.ObjectValue object, String name) {
        StateValue value = optional(object, name);
        if (value == null) {
            return null;
        }
        if (value instanceof StateValue.NumberValue number) {
            return number.value().doubleValue();
        }
        throw requestError(name, "invalid_type", "Parameter '" + name + "' must be a number.");
    }

    private static Integer optionalPositiveInteger(StateValue.ObjectValue object, String name) {
        StateValue value = optional(object, name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof StateValue.NumberValue number)) {
            throw requestError(name, "invalid_type", "Parameter '" + name + "' must be an integer.");
        }
        try {
            int integer = number.value().intValueExact();
            if (integer <= 0) {
                throw requestError(name, "invalid_value", "Parameter '" + name + "' must be greater than zero.");
            }
            return integer;
        } catch (ArithmeticException failure) {
            throw requestError(name, "invalid_type", "Parameter '" + name + "' must be an integer.", failure);
        }
    }

    private static List<StateValue> optionalArray(StateValue.ObjectValue object, String name) {
        StateValue value = optional(object, name);
        if (value == null) {
            return List.of();
        }
        if (value instanceof StateValue.ArrayValue array) {
            return array.values();
        }
        throw requestError(name, "invalid_type", "Parameter '" + name + "' must be an array.");
    }

    private static Map<String, String> optionalStringMap(StateValue.ObjectValue object, String name) {
        StateValue value = optional(object, name);
        if (value == null) {
            return Map.of();
        }
        StateValue.ObjectValue values = requireObject(value, name);
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        values.values().forEach((key, entry) -> {
            if (!(entry instanceof StateValue.StringValue string)
                    || string.value().isBlank()) {
                throw requestError(name, "invalid_type", "OpenAI Responses metadata values must be non-blank strings.");
            }
            result.put(key, string.value());
        });
        return Map.copyOf(result);
    }

    private static StateValue.ObjectValue requireObject(StateValue value, String name) {
        if (value instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw requestError(name, "invalid_type", name + " must be an object.");
    }

    private static void rejectUnknown(StateValue.ObjectValue object, Set<String> allowed, String context) {
        TreeSet<String> unknown = new TreeSet<>(object.values().keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            String name = unknown.getFirst();
            throw requestError(
                    name, "unsupported_parameter", context + " contains unsupported parameter '" + name + "'.");
        }
    }

    private static String requireNonBlank(String value, String name) {
        java.util.Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw requestError(name, "invalid_value", "Parameter '" + name + "' must not be blank.");
        }
        return value;
    }

    private static void requireChoice(String value, Set<String> allowed, String name) {
        if (value != null && !allowed.contains(value)) {
            throw requestError("input", "unsupported_value", name + " contains an unsupported value.");
        }
    }

    private static void putString(Map<String, StateValue> target, String name, String value) {
        if (value != null) {
            target.put(name, StateValue.string(value));
        }
    }

    private static void putNumber(Map<String, StateValue> target, String name, Double value) {
        if (value != null) {
            target.put(name, StateValue.number(BigDecimal.valueOf(value)));
        }
    }

    private static HostingException requestError(String parameter, String code, String message) {
        LinkedHashMap<String, StateValue> details = new LinkedHashMap<>();
        details.put("openaiCode", StateValue.string(code.toLowerCase(Locale.ROOT)));
        if (parameter != null) {
            details.put("param", StateValue.string(parameter));
        }
        return new HostingException(new HostingError(HostingErrorCode.MALFORMED_REQUEST, message, false, details));
    }

    private static HostingException requestError(String parameter, String code, String message, Throwable cause) {
        HostingException safe = requestError(parameter, code, message);
        safe.addSuppressed(cause);
        return safe;
    }
}
