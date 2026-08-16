// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.agents.core.StateValue;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Encodes and decodes the OpenAI Responses wire contract using framework-owned transport values.
 *
 * <p>This codec is intended for provider adapters whose service exposes the OpenAI Responses
 * protocol but whose official SDK has a different model surface. It deliberately exposes no SDK
 * model types. Unsupported output and stream variants fail explicitly instead of being discarded.
 */
public final class OpenAIResponsesJsonCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenAIResponsesJsonCodec() {}

    /**
     * Encodes one mapped request as an OpenAI Responses JSON object.
     *
     * @param request immutable transport request
     * @return JSON request body
     */
    public static String encodeRequest(OpenAITransport.Request request) {
        Objects.requireNonNull(request, "request");
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put(
                "input",
                request.input().stream()
                        .map(OpenAIResponsesJsonCodec::encodeInput)
                        .toList());
        put(body, "instructions", request.instructions());
        put(body, "temperature", request.temperature());
        put(body, "top_p", request.topP());
        put(body, "max_output_tokens", request.maxOutputTokens());
        if (!request.tools().isEmpty() || request.responseOptions().imageOutputFormat() != null) {
            body.put("tools", encodeTools(request));
        }
        if (request.toolChoice() != null) {
            body.put("tool_choice", request.toolChoice().name().toLowerCase(Locale.ROOT));
        }
        put(body, "parallel_tool_calls", request.parallelToolCalls());
        put(body, "user", request.user());
        put(body, "store", request.store());
        put(body, "previous_response_id", request.previousResponseId());
        put(body, "conversation", request.conversationId());
        if (!request.metadata().isEmpty()) {
            body.put("metadata", request.metadata());
        }
        if (request.structuredOutput() != null) {
            LinkedHashMap<String, Object> format = new LinkedHashMap<>();
            format.put("type", "json_schema");
            format.put("name", request.structuredOutput().name());
            format.put(
                    "schema", OpenAIStateJson.toJava(request.structuredOutput().schema()));
            format.put("strict", request.structuredOutput().strict());
            put(format, "description", request.structuredOutput().description());
            body.put("text", Map.of("format", format));
        }
        encodeResponseOptions(request.responseOptions(), body);
        return write(body);
    }

    /**
     * Decodes one finite Responses JSON object.
     *
     * @param json response JSON
     * @param requestId optional sanitized HTTP request identifier
     * @return framework-owned transport response
     */
    public static OpenAITransport.Response decodeResponse(String json, String requestId) {
        return decodeResponse(json, requestId, null);
    }

    /**
     * Decodes one finite Responses JSON object with a fallback model identifier.
     *
     * @param json response JSON
     * @param requestId optional sanitized HTTP request identifier
     * @param fallbackModel model used when an agent-scoped response omits {@code model}
     * @return framework-owned transport response
     */
    public static OpenAITransport.Response decodeResponse(String json, String requestId, String fallbackModel) {
        ObjectNode root = object(read(json), "response");
        String responseId = requiredText(root, "id");
        String model = optionalText(root, "model");
        if (model == null) {
            model = required(fallbackModel, "fallbackModel");
        }
        Instant createdAt = instant(root.get("created_at"));
        OpenAITransport.ResponseStatus status = status(optionalText(root, "status"));
        ArrayList<OpenAITransport.OutputItem> outputs = new ArrayList<>();
        JsonNode output = root.get("output");
        if (output != null && !output.isNull()) {
            for (JsonNode item : array(output, "output")) {
                outputs.addAll(decodeOutput(object(item, "output item")));
            }
        }
        return new OpenAITransport.Response(
                responseId,
                conversationId(root.get("conversation")),
                model,
                createdAt,
                status,
                outputs,
                usage(root.get("usage")),
                metadata(root.get("metadata")),
                optional(requestId),
                nestedText(root, "incomplete_details", "reason"),
                nestedText(root, "error", "code"));
    }

    /**
     * Decodes one server-sent Responses event.
     *
     * <p>One wire event may produce zero or more transport events. Lifecycle-only events that carry
     * no framework observation return an empty list.
     *
     * @param json event JSON
     * @param requestId optional sanitized HTTP request identifier
     * @param fallbackModel model used when an agent-scoped event omits {@code model}
     * @return immutable mapped events
     */
    public static List<OpenAITransport.StreamEvent> decodeStreamEvent(
            String json, String requestId, String fallbackModel) {
        ObjectNode root = object(read(json), "stream event");
        String type = requiredText(root, "type");
        long sequence = nonNegativeLong(root.get("sequence_number"), "sequence_number", 0);
        if ("response.created".equals(type) || "response.in_progress".equals(type)) {
            ObjectNode response = object(root.get("response"), "response");
            String model = optionalText(response, "model");
            if (model == null) {
                model = required(fallbackModel, "fallbackModel");
            }
            return List.of(new OpenAITransport.ResponseStarted(
                    sequence,
                    requiredText(response, "id"),
                    conversationId(response.get("conversation")),
                    model,
                    instant(response.get("created_at")),
                    optional(requestId),
                    status(optionalText(response, "status"))));
        }
        if ("response.output_text.delta".equals(type) || "response.refusal.delta".equals(type)) {
            LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
            if ("response.refusal.delta".equals(type)) {
                values.put("openai.refusal", StateValue.bool(true));
            }
            return List.of(new OpenAITransport.TextDelta(
                    sequence, requiredText(root, "item_id"), requiredText(root, "delta"), Map.copyOf(values)));
        }
        if ("response.output_item.added".equals(type)) {
            ObjectNode item = object(root.get("item"), "item");
            if ("function_call".equals(requiredText(item, "type"))) {
                return List.of(new OpenAITransport.FunctionCallStarted(
                        sequence,
                        nonNegativeLong(root.get("output_index"), "output_index", 0),
                        requiredText(item, "id"),
                        requiredText(item, "call_id"),
                        requiredText(item, "name")));
            }
            return List.of();
        }
        if ("response.function_call_arguments.delta".equals(type)) {
            return List.of(new OpenAITransport.FunctionArgumentsDelta(
                    sequence,
                    nonNegativeLong(root.get("output_index"), "output_index", 0),
                    requiredText(root, "item_id"),
                    requiredText(root, "delta")));
        }
        if ("response.output_item.done".equals(type)) {
            ObjectNode item = object(root.get("item"), "item");
            if ("function_call".equals(requiredText(item, "type"))) {
                return List.of(new OpenAITransport.FunctionArgumentsDone(
                        sequence,
                        nonNegativeLong(root.get("output_index"), "output_index", 0),
                        requiredText(item, "id"),
                        requiredText(item, "call_id"),
                        requiredText(item, "name"),
                        parseArguments(item.get("arguments"))));
            }
            return List.of();
        }
        if ("response.image_generation_call.partial_image".equals(type)) {
            String value = requiredText(root, "partial_image_b64");
            URI uri = URI.create("data:image/png;base64," + value);
            return List.of(new OpenAITransport.ImageDelta(sequence, new OpenAITransport.ImageOutput(uri, "image/png")));
        }
        if ("response.completed".equals(type) || "response.incomplete".equals(type)) {
            OpenAITransport.Response response =
                    decodeResponse(writeNode(root.get("response")), requestId, fallbackModel);
            return List.of(new OpenAITransport.ResponseCompleted(sequence, response));
        }
        if ("response.failed".equals(type) || "error".equals(type)) {
            JsonNode response = root.get("response");
            return List.of(new OpenAITransport.ResponseFailed(
                    sequence,
                    response == null || response.isNull()
                            ? optionalText(root, "response_id")
                            : optionalText(object(response, "response"), "id"),
                    optional(requestId),
                    firstNonBlank(
                            nestedText(root, "response", "error", "code"),
                            nestedText(root, "error", "code"),
                            optionalText(root, "code"))));
        }
        if (isNoObservationEvent(type)) {
            return List.of();
        }
        throw protocol("OpenAI emitted unsupported Responses event '" + type + "'.", requestId, "unsupported_event");
    }

    private static Object encodeInput(OpenAITransport.InputItem item) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        if (item instanceof OpenAITransport.MessageInput message) {
            value.put("type", "message");
            value.put("role", message.role().name().toLowerCase(Locale.ROOT));
            value.put(
                    "content",
                    message.contents().stream()
                            .map(OpenAIResponsesJsonCodec::encodeContent)
                            .toList());
            return value;
        }
        if (item instanceof OpenAITransport.FunctionCallInput call) {
            value.put("type", "function_call");
            put(value, "id", call.providerItemId());
            value.put("call_id", call.callId());
            value.put("name", call.name());
            value.put("arguments", OpenAIStateJson.write(call.arguments()));
            return value;
        }
        if (item instanceof OpenAITransport.FunctionResultInput result) {
            value.put("type", "function_call_output");
            value.put("call_id", result.callId());
            StateValue payload = result.error() == null
                    ? result.result()
                    : StateValue.object(Map.of("error", StateValue.string(result.error()), "result", result.result()));
            if (result.items().isEmpty()) {
                value.put("output", OpenAIStateJson.write(payload));
            } else {
                ArrayList<Object> output = new ArrayList<>();
                output.add(Map.of("type", "input_text", "text", OpenAIStateJson.write(payload)));
                result.items().forEach(content -> output.add(encodeContent(content)));
                value.put("output", output);
            }
            return value;
        }
        if (item instanceof OpenAITransport.ReasoningInput reasoning) {
            value.put("type", "reasoning");
            value.put("id", reasoning.id());
            if (reasoning.text() != null) {
                value.put("summary", List.of(Map.of("type", "summary_text", "text", reasoning.text())));
            } else {
                value.put("summary", List.of());
            }
            put(value, "encrypted_content", reasoning.protectedData());
            return value;
        }
        throw protocol("OpenAI request contains an unsupported input item.", null, "unsupported_input");
    }

    private static Object encodeContent(OpenAITransport.InputContent content) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        if (content instanceof OpenAITransport.TextInput text) {
            value.put("type", "input_text");
            value.put("text", text.text());
            return value;
        }
        if (content instanceof OpenAITransport.ImageInput image) {
            value.put("type", "input_image");
            value.put("image_url", image.uri().toString());
            value.put("detail", image.detail().name().toLowerCase(Locale.ROOT));
            return value;
        }
        if (content instanceof OpenAITransport.FileInput file) {
            value.put("type", "input_file");
            value.put(
                    "data".equalsIgnoreCase(file.uri().getScheme()) ? "file_data" : "file_url",
                    file.uri().toString());
            put(value, "filename", file.filename());
            return value;
        }
        throw protocol("OpenAI request contains unsupported input content.", null, "unsupported_input");
    }

    private static List<Object> encodeTools(OpenAITransport.Request request) {
        ArrayList<Object> tools = new ArrayList<>();
        for (OpenAITransport.FunctionTool tool : request.tools()) {
            tools.add(Map.of(
                    "type",
                    "function",
                    "name",
                    tool.name(),
                    "description",
                    tool.description(),
                    "parameters",
                    OpenAIStateJson.toJava(tool.inputSchema()),
                    "strict",
                    false));
        }
        if (request.responseOptions().imageOutputFormat() != null) {
            tools.add(Map.of(
                    "type",
                    "image_generation",
                    "output_format",
                    request.responseOptions().imageOutputFormat().name().toLowerCase(Locale.ROOT)));
        }
        return List.copyOf(tools);
    }

    private static void encodeResponseOptions(OpenAIResponseOptions options, Map<String, Object> body) {
        if (options.reasoningEffort() != null || options.reasoningSummary() != null) {
            LinkedHashMap<String, Object> reasoning = new LinkedHashMap<>();
            if (options.reasoningEffort() != null) {
                reasoning.put("effort", options.reasoningEffort().name().toLowerCase(Locale.ROOT));
            }
            if (options.reasoningSummary() != null) {
                reasoning.put("summary", options.reasoningSummary().name().toLowerCase(Locale.ROOT));
            }
            body.put("reasoning", reasoning);
        }
        if (options.serviceTier() != null) {
            body.put("service_tier", options.serviceTier().name().toLowerCase(Locale.ROOT));
        }
        if (options.truncation() != null) {
            body.put("truncation", options.truncation().name().toLowerCase(Locale.ROOT));
        }
        put(body, "background", options.background());
        if (options.includeEncryptedReasoning()) {
            body.put("include", List.of("reasoning.encrypted_content"));
        }
    }

    private static List<OpenAITransport.OutputItem> decodeOutput(ObjectNode item) {
        String type = requiredText(item, "type");
        if ("message".equals(type)) {
            ArrayList<OpenAITransport.OutputItem> values = new ArrayList<>();
            JsonNode content = item.get("content");
            if (content != null && !content.isNull()) {
                for (JsonNode partValue : array(content, "message content")) {
                    ObjectNode part = object(partValue, "message content");
                    String contentType = requiredText(part, "type");
                    if ("output_text".equals(contentType)) {
                        values.add(new OpenAITransport.TextOutput(
                                requiredText(item, "id"), requiredText(part, "text"), false, Map.of()));
                    } else if ("refusal".equals(contentType)) {
                        values.add(new OpenAITransport.TextOutput(
                                requiredText(item, "id"), requiredText(part, "refusal"), true, Map.of()));
                    } else {
                        throw protocol(
                                "OpenAI returned unsupported message content '" + contentType + "'.",
                                null,
                                "unsupported_output");
                    }
                }
            }
            return List.copyOf(values);
        }
        if ("function_call".equals(type)) {
            return List.of(new OpenAITransport.FunctionCallOutput(
                    requiredText(item, "call_id"),
                    requiredText(item, "name"),
                    parseArguments(item.get("arguments")),
                    optionalText(item, "id"),
                    optionalText(item, "status")));
        }
        if ("reasoning".equals(type)) {
            String text = reasoningText(item);
            String encrypted = optionalText(item, "encrypted_content");
            if (text == null && encrypted == null) {
                throw protocol("OpenAI reasoning output has no representable content.", null, "unsupported_output");
            }
            return List.of(
                    new OpenAITransport.ReasoningOutput(requiredText(item, "id"), text, encrypted, text != null));
        }
        if ("image_generation_call".equals(type)) {
            String result = optionalText(item, "result");
            if (result == null) {
                return List.of();
            }
            return List.of(new OpenAITransport.ImageOutput(URI.create("data:image/png;base64," + result), "image/png"));
        }
        throw protocol("OpenAI returned unsupported output item '" + type + "'.", null, "unsupported_output");
    }

    private static String reasoningText(ObjectNode item) {
        JsonNode summary = item.get("summary");
        if (summary == null || summary.isNull()) {
            return null;
        }
        ArrayList<String> values = new ArrayList<>();
        for (JsonNode entry : array(summary, "reasoning summary")) {
            String text = optionalText(object(entry, "reasoning summary"), "text");
            if (text != null) {
                values.add(text);
            }
        }
        return values.isEmpty() ? null : String.join("\n", values);
    }

    private static StateValue parseArguments(JsonNode value) {
        if (value == null || value.isNull()) {
            throw protocol("OpenAI function call is missing arguments.", null, "invalid_arguments");
        }
        return value.isTextual() ? OpenAIStateJson.read(value.textValue()) : OpenAIStateJson.fromJava(value);
    }

    private static OpenAITransport.Usage usage(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        ObjectNode usage = object(value, "usage");
        long input = nonNegativeLong(usage.get("input_tokens"), "input_tokens", 0);
        long output = nonNegativeLong(usage.get("output_tokens"), "output_tokens", 0);
        long total = nonNegativeLong(usage.get("total_tokens"), "total_tokens", input + output);
        Long cached = nestedLong(usage, "input_tokens_details", "cached_tokens");
        Long reasoning = nestedLong(usage, "output_tokens_details", "reasoning_tokens");
        return new OpenAITransport.Usage(input, output, total, cached, reasoning);
    }

    private static Map<String, StateValue> metadata(JsonNode value) {
        if (value == null || value.isNull()) {
            return Map.of();
        }
        LinkedHashMap<String, StateValue> result = new LinkedHashMap<>();
        object(value, "metadata")
                .properties()
                .forEach(entry -> result.put(entry.getKey(), OpenAIStateJson.fromJava(entry.getValue())));
        return Map.copyOf(result);
    }

    private static OpenAITransport.ResponseStatus status(String value) {
        if (value == null) {
            return OpenAITransport.ResponseStatus.COMPLETED;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "completed" -> OpenAITransport.ResponseStatus.COMPLETED;
            case "incomplete" -> OpenAITransport.ResponseStatus.INCOMPLETE;
            case "failed" -> OpenAITransport.ResponseStatus.FAILED;
            case "cancelled", "canceled" -> OpenAITransport.ResponseStatus.CANCELLED;
            case "in_progress" -> OpenAITransport.ResponseStatus.IN_PROGRESS;
            case "queued" -> OpenAITransport.ResponseStatus.QUEUED;
            default ->
                throw protocol("OpenAI returned unknown response status '" + value + "'.", null, "unknown_status");
        };
    }

    private static Instant instant(JsonNode value) {
        if (value == null || value.isNull()) {
            return Instant.EPOCH;
        }
        BigDecimal seconds = value.decimalValue();
        long whole = seconds.longValue();
        int nanos =
                seconds.subtract(BigDecimal.valueOf(whole)).movePointRight(9).intValue();
        return Instant.ofEpochSecond(whole, nanos);
    }

    private static String conversationId(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return optional(value.textValue());
        }
        return optionalText(object(value, "conversation"), "id");
    }

    private static boolean isNoObservationEvent(String type) {
        return switch (type) {
            case "response.queued",
                    "response.content_part.added",
                    "response.content_part.done",
                    "response.output_text.done",
                    "response.refusal.done",
                    "response.function_call_arguments.done",
                    "response.reasoning_summary_part.added",
                    "response.reasoning_summary_part.done",
                    "response.reasoning_summary_text.delta",
                    "response.reasoning_summary_text.done",
                    "response.reasoning_text.delta",
                    "response.reasoning_text.done" -> true;
            default -> false;
        };
    }

    private static String nestedText(ObjectNode root, String objectName, String fieldName) {
        JsonNode nested = root.get(objectName);
        return nested == null || nested.isNull() ? null : optionalText(object(nested, objectName), fieldName);
    }

    private static String nestedText(ObjectNode root, String first, String second, String fieldName) {
        JsonNode firstNode = root.get(first);
        if (firstNode == null || firstNode.isNull()) {
            return null;
        }
        JsonNode secondNode = object(firstNode, first).get(second);
        return secondNode == null || secondNode.isNull() ? null : optionalText(object(secondNode, second), fieldName);
    }

    private static Long nestedLong(ObjectNode root, String objectName, String fieldName) {
        JsonNode nested = root.get(objectName);
        if (nested == null || nested.isNull()) {
            return null;
        }
        JsonNode value = object(nested, objectName).get(fieldName);
        return value == null || value.isNull() ? null : nonNegativeLong(value, fieldName, 0);
    }

    private static long nonNegativeLong(JsonNode value, String name, long fallback) {
        if (value == null || value.isNull()) {
            return fallback;
        }
        long result = value.longValue();
        if (result < 0) {
            throw protocol("OpenAI returned negative " + name + ".", null, "invalid_number");
        }
        return result;
    }

    private static String requiredText(ObjectNode root, String field) {
        return required(optionalText(root, field), field);
    }

    private static String optionalText(ObjectNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw protocol("OpenAI field '" + field + "' must be a string.", null, "invalid_json");
        }
        return optional(value.textValue());
    }

    private static String required(String value, String name) {
        String result = optional(value);
        if (result == null) {
            throw protocol("OpenAI field '" + name + "' is required.", null, "invalid_json");
        }
        return result;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String result = optional(value);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static ObjectNode object(JsonNode value, String name) {
        if (!(value instanceof ObjectNode object)) {
            throw protocol("OpenAI " + name + " must be an object.", null, "invalid_json");
        }
        return object;
    }

    private static ArrayNode array(JsonNode value, String name) {
        if (!(value instanceof ArrayNode array)) {
            throw protocol("OpenAI " + name + " must be an array.", null, "invalid_json");
        }
        return array;
    }

    private static JsonNode read(String json) {
        Objects.requireNonNull(json, "json");
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException failure) {
            throw protocol("OpenAI returned malformed JSON.", null, "invalid_json");
        }
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw protocol("Unable to encode OpenAI Responses JSON.", null, "invalid_json");
        }
    }

    private static String writeNode(JsonNode value) {
        if (value == null || value.isNull()) {
            throw protocol("OpenAI stream event is missing its response.", null, "invalid_json");
        }
        return write(value);
    }

    private static void put(Map<String, Object> target, String name, Object value) {
        if (value != null) {
            target.put(name, value);
        }
    }

    private static OpenAIProtocolException protocol(String message, String requestId, String code) {
        return new OpenAIProtocolException(message, requestId, code);
    }
}
