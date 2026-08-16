// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.gemini;

import com.google.genai.types.Candidate;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionCallingConfig;
import com.google.genai.types.FunctionCallingConfigMode;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.SafetyRating;
import com.google.genai.types.ThinkingConfig;
import com.google.genai.types.Tool;
import com.google.genai.types.ToolConfig;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.ErrorContent;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.MetadataContent;
import com.microsoft.agents.core.ReasoningContent;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StructuredOutputOptions;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.UriContent;
import com.microsoft.agents.core.UsageDetails;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.core.internal.StructuredOutputSupport;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import com.microsoft.agents.tools.ToolMode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GeminiMapper {
    private static final Role DEVELOPER = Role.of("developer");

    private static final Set<String> PROVIDER_OPTIONS =
            Set.of("gemini.responseSchema", "gemini.topK", "gemini.thinkingBudget", "gemini.includeThoughts");

    private GeminiMapper() {}

    static void validate(ChatClientRequest request, GeminiChatClientOptions defaults) {
        if (request.messages().isEmpty()) {
            throw new ValidationException("Gemini requests require at least one message.");
        }
        long bytes = 0;
        for (Message message : request.messages()) {
            Role role = message.role();
            if (role.equals(DEVELOPER)) {
                throw new ValidationException("Gemini generateContent does not support the developer role.");
            }
            if (!(role.equals(Role.SYSTEM)
                    || role.equals(Role.USER)
                    || role.equals(Role.ASSISTANT)
                    || role.equals(Role.TOOL))) {
                throw new ValidationException("Gemini does not support role '" + role.value() + "'.");
            }
            for (Content content : message.contents()) {
                bytes = Math.addExact(bytes, validateContent(role, content));
            }
        }
        if (bytes > defaults.maxRequestBytes()) {
            throw new ValidationException("Gemini request content exceeds maxRequestBytes.");
        }
        for (ToolMetadata tool : request.tools()) {
            if (!tool.capabilities().equals(Set.of(ToolCapability.FUNCTION))) {
                throw new ValidationException(
                        "Gemini supports only FUNCTION tools; tool '" + tool.name() + "' is unsupported.");
            }
        }
        ChatOptions options = request.options();
        if (options.allowMultipleToolCalls() != null
                || options.user() != null
                || options.store() != null
                || options.conversationId() != null) {
            throw new ValidationException(
                    "Gemini does not support allowMultipleToolCalls, user, store, or conversationId.");
        }
        options.metadata().forEach((key, value) -> {
            if (key.startsWith("gemini.") && !PROVIDER_OPTIONS.contains(key)) {
                throw new ValidationException("Unsupported Gemini option '" + key + "'.");
            }
        });
        StructuredOutputSupport.resolve(options, "gemini.responseSchema");
        requireInteger(options.metadata().get("gemini.topK"), "gemini.topK", 1);
        requireInteger(options.metadata().get("gemini.thinkingBudget"), "gemini.thinkingBudget", -1);
        StateValue includeThoughts = options.metadata().get("gemini.includeThoughts");
        if (includeThoughts != null && !(includeThoughts instanceof StateValue.BooleanValue)) {
            throw new ValidationException("gemini.includeThoughts must be a Boolean.");
        }
        if (request.tools().isEmpty()
                && (request.toolMode() == ToolMode.REQUIRED
                        || options.toolChoice() == com.microsoft.agents.core.ToolChoice.REQUIRED)) {
            throw new ValidationException("Gemini required tool selection needs at least one tool.");
        }
    }

    static MappedRequest request(ChatClientRequest request, GeminiChatClientOptions defaults) {
        validate(request, defaults);
        ChatOptions options = request.options();
        GenerateContentConfig.Builder config = GenerateContentConfig.builder();
        String system = system(request, options);
        if (!system.isEmpty()) {
            config.systemInstruction(com.google.genai.types.Content.builder()
                    .role("user")
                    .parts(Part.fromText(system))
                    .build());
        }
        if (options.temperature() != null) {
            config.temperature(options.temperature().floatValue());
        }
        if (options.topP() != null) {
            config.topP(options.topP().floatValue());
        }
        if (options.maxTokens() != null) {
            config.maxOutputTokens(options.maxTokens());
        }
        if (!options.stop().isEmpty()) {
            config.stopSequences(options.stop());
        }
        if (options.seed() != null) {
            config.seed(Math.toIntExact(options.seed()));
        }
        if (options.frequencyPenalty() != null) {
            config.frequencyPenalty(options.frequencyPenalty().floatValue());
        }
        if (options.presencePenalty() != null) {
            config.presencePenalty(options.presencePenalty().floatValue());
        }
        StateValue topK = options.metadata().get("gemini.topK");
        if (topK instanceof StateValue.NumberValue number) {
            config.topK(number.value().floatValue());
        }
        StructuredOutputOptions structuredOutput = StructuredOutputSupport.resolve(options, "gemini.responseSchema");
        if (structuredOutput != null) {
            config.responseMimeType("application/json").responseJsonSchema(toJava(structuredOutput.schema()));
        }
        StateValue budget = options.metadata().get("gemini.thinkingBudget");
        StateValue include = options.metadata().get("gemini.includeThoughts");
        if (budget != null || include != null) {
            ThinkingConfig.Builder thinking = ThinkingConfig.builder();
            if (budget instanceof StateValue.NumberValue number) {
                thinking.thinkingBudget(number.value().intValueExact());
            }
            if (include instanceof StateValue.BooleanValue bool) {
                thinking.includeThoughts(bool.value());
            }
            config.thinkingConfig(thinking.build());
        }
        if (!request.tools().isEmpty()) {
            config.tools(List.of(Tool.builder()
                    .functionDeclarations(
                            request.tools().stream().map(GeminiMapper::tool).toList())
                    .build()));
            config.toolConfig(ToolConfig.builder()
                    .functionCallingConfig(FunctionCallingConfig.builder()
                            .mode(toolMode(request))
                            .build())
                    .build());
        }
        return new MappedRequest(
                options.model() == null ? defaults.model() : options.model(), messages(request), config.build());
    }

    static ChatResponse response(GenerateContentResponse response) {
        ArrayList<Message> messages = new ArrayList<>();
        FinishReason finish = null;
        LinkedHashMap<String, StateValue> metadata = responseMetadata(response);
        for (Candidate candidate : response.candidates().orElse(List.of())) {
            com.google.genai.types.FinishReason providerFinish =
                    candidate.finishReason().orElse(null);
            boolean toolFailure = isToolFailure(providerFinish);
            ArrayList<Content> content = candidate
                    .content()
                    .map(value -> toolFailure
                            ? partsWithoutFunctionCalls(
                                    value.parts().orElse(List.of()),
                                    candidate.index().orElse(0))
                            : parts(
                                    value.parts().orElse(List.of()),
                                    candidate.index().orElse(0)))
                    .orElseGet(ArrayList::new);
            if (toolFailure) {
                content.add(toolFailure(candidate, providerFinish));
                metadata.put("gemini.finishReason", StateValue.string(providerFinish.toString()));
                candidate
                        .finishMessage()
                        .ifPresent(message -> metadata.put("gemini.finishMessage", StateValue.string(message)));
            }
            addCandidateMetadata(content, candidate);
            messages.add(Message.builder(Role.ASSISTANT).contents(content).build());
            FinishReason current = providerFinish == null ? null : finish(providerFinish);
            if (finish == null) {
                finish = current;
            }
        }
        if (messages.isEmpty()) {
            messages.add(Message.builder(Role.ASSISTANT).contents(List.of()).build());
            if (response.promptFeedback().flatMap(value -> value.blockReason()).isPresent()) {
                finish = FinishReason.CONTENT_FILTER;
            }
        }
        return ChatResponse.builder()
                .messages(messages)
                .responseId(response.responseId().orElse(null))
                .model(response.modelVersion().orElse(null))
                .createdAt(response.createTime().orElse(null))
                .finishReason(finish)
                .usage(usage(response.usageMetadata().orElse(null)))
                .metadata(metadata)
                .build();
    }

    private static long validateContent(Role role, Content content) {
        if (role.equals(Role.SYSTEM)) {
            if (!(content instanceof TextContent text)) {
                throw unsupported(role, content);
            }
            return text.text().getBytes(StandardCharsets.UTF_8).length;
        }
        if (role.equals(Role.TOOL)) {
            if (!(content instanceof FunctionResultContent result)
                    || !result.items().isEmpty()) {
                throw unsupported(role, content);
            }
            return estimate(result.result());
        }
        if (content instanceof TextContent text) {
            return text.text().getBytes(StandardCharsets.UTF_8).length;
        }
        if (content instanceof DataContent data) {
            if (!role.equals(Role.USER)) {
                throw unsupported(role, content);
            }
            return data.data().length;
        }
        if (content instanceof UriContent uri) {
            if (!role.equals(Role.USER)
                    || !Set.of("https", "gs").contains(uri.uri().getScheme().toLowerCase(java.util.Locale.ROOT))) {
                throw unsupported(role, content);
            }
            return uri.uri().toString().length();
        }
        if (content instanceof FunctionCallContent call) {
            if (!role.equals(Role.ASSISTANT) || !(call.arguments() instanceof StateValue.ObjectValue)) {
                throw new ValidationException("Gemini function calls require assistant role and object arguments.");
            }
            return estimate(call.arguments());
        }
        if (content instanceof ReasoningContent reasoning) {
            if (!role.equals(Role.ASSISTANT)) {
                throw unsupported(role, content);
            }
            return (reasoning.text() == null ? 0 : reasoning.text().length())
                    + (reasoning.protectedData() == null
                            ? 0
                            : reasoning.protectedData().length());
        }
        throw unsupported(role, content);
    }

    private static String system(ChatClientRequest request, ChatOptions options) {
        ArrayList<String> values = new ArrayList<>();
        if (options.instructions() != null) {
            values.add(options.instructions());
        }
        request.messages().stream()
                .filter(message -> message.role().equals(Role.SYSTEM))
                .flatMap(message -> message.contents().stream())
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .forEach(values::add);
        return String.join("\n", values);
    }

    private static List<com.google.genai.types.Content> messages(ChatClientRequest request) {
        ArrayList<com.google.genai.types.Content> messages = new ArrayList<>();
        LinkedHashMap<String, String> callNames = new LinkedHashMap<>();
        ArrayList<Part> pendingToolResults = new ArrayList<>();
        for (Message message : request.messages()) {
            if (message.role().equals(Role.SYSTEM)) {
                continue;
            }
            if (message.role().equals(Role.TOOL)) {
                for (Content content : message.contents()) {
                    FunctionResultContent result = (FunctionResultContent) content;
                    String name = callNames.get(result.callId());
                    if (name == null) {
                        throw new ValidationException(
                                "Gemini tool result '" + result.callId() + "' has no preceding function call.");
                    }
                    Map<String, Object> response =
                            result.error() == null ? resultMap(result.result()) : Map.of("error", result.error());
                    pendingToolResults.add(Part.builder()
                            .functionResponse(FunctionResponse.builder()
                                    .id(result.callId())
                                    .name(name)
                                    .response(response)
                                    .build())
                            .build());
                }
                continue;
            }
            if (!pendingToolResults.isEmpty()) {
                messages.add(com.google.genai.types.Content.builder()
                        .role("user")
                        .parts(pendingToolResults)
                        .build());
                pendingToolResults.clear();
            }
            ArrayList<Part> parts = new ArrayList<>();
            for (Content content : message.contents()) {
                if (content instanceof TextContent text) {
                    parts.add(Part.fromText(text.text()));
                } else if (content instanceof DataContent data) {
                    parts.add(Part.fromBytes(data.data(), data.mediaType()));
                } else if (content instanceof UriContent uri) {
                    parts.add(Part.fromUri(uri.uri().toString(), uri.mediaType()));
                } else if (content instanceof FunctionCallContent call) {
                    callNames.put(call.callId(), call.name());
                    parts.add(Part.builder()
                            .functionCall(FunctionCall.builder()
                                    .id(call.callId())
                                    .name(call.name())
                                    .args(castMap(toJava(call.arguments())))
                                    .build())
                            .build());
                } else if (content instanceof ReasoningContent reasoning) {
                    Part.Builder thought = Part.builder().thought(true);
                    if (reasoning.text() != null) {
                        thought.text(reasoning.text());
                    }
                    if (reasoning.protectedData() != null) {
                        thought.thoughtSignature(Base64.getDecoder().decode(reasoning.protectedData()));
                    }
                    parts.add(thought.build());
                }
            }
            messages.add(com.google.genai.types.Content.builder()
                    .role(message.role().equals(Role.ASSISTANT) ? "model" : "user")
                    .parts(parts)
                    .build());
        }
        if (!pendingToolResults.isEmpty()) {
            messages.add(com.google.genai.types.Content.builder()
                    .role("user")
                    .parts(pendingToolResults)
                    .build());
        }
        return List.copyOf(messages);
    }

    private static FunctionDeclaration tool(ToolMetadata tool) {
        return FunctionDeclaration.builder()
                .name(tool.name())
                .description(tool.description())
                .parametersJsonSchema(toJava(tool.inputSchema()))
                .build();
    }

    private static FunctionCallingConfigMode toolMode(ChatClientRequest request) {
        ToolMode mode = request.toolMode();
        if (mode == ToolMode.NONE && request.options().toolChoice() != null) {
            mode = switch (request.options().toolChoice()) {
                case NONE -> ToolMode.NONE;
                case AUTO -> ToolMode.AUTO;
                case REQUIRED -> ToolMode.REQUIRED;
            };
        }
        return switch (mode) {
            case NONE -> new FunctionCallingConfigMode(FunctionCallingConfigMode.Known.NONE);
            case AUTO -> new FunctionCallingConfigMode(FunctionCallingConfigMode.Known.AUTO);
            case REQUIRED -> new FunctionCallingConfigMode(FunctionCallingConfigMode.Known.ANY);
        };
    }

    private static ArrayList<Content> parts(List<Part> parts, int candidateIndex) {
        ArrayList<Content> result = new ArrayList<>();
        int index = 0;
        for (Part part : parts) {
            if (part.thought().orElse(false)) {
                String signature = part.thoughtSignature()
                        .map(value -> Base64.getEncoder().encodeToString(value))
                        .orElse(null);
                if (part.text().isPresent() || signature != null) {
                    result.add(new ReasoningContent(
                            "gemini-thought-" + candidateIndex + "-" + index,
                            part.text().orElse(null),
                            signature,
                            Map.of()));
                }
            } else if (part.text().isPresent()) {
                result.add(new TextContent(part.text().orElseThrow()));
            } else if (part.functionCall().isPresent()) {
                FunctionCall call = part.functionCall().orElseThrow();
                String id = call.id().orElse("gemini-call-" + candidateIndex + "-" + index);
                LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
                if (call.id().isEmpty()) {
                    metadata.put("gemini.syntheticCallId", StateValue.bool(true));
                }
                result.add(new FunctionCallContent(
                        id,
                        call.name().orElseThrow(() -> new GeminiProviderException("missing_tool_name", null, null)),
                        fromJava(call.args().orElse(Map.of())),
                        false,
                        metadata));
            } else if (part.functionResponse().isPresent()) {
                FunctionResponse response = part.functionResponse().orElseThrow();
                result.add(new FunctionResultContent(
                        response.id().orElse("gemini-result-" + candidateIndex + "-" + index),
                        fromJava(response.response().orElse(Map.of()))));
            } else if (part.inlineData().isPresent()) {
                var blob = part.inlineData().orElseThrow();
                if (blob.data().isPresent() && blob.mimeType().isPresent()) {
                    result.add(new DataContent(
                            blob.data().orElseThrow(), blob.mimeType().orElseThrow()));
                }
            } else if (part.fileData().isPresent()) {
                var file = part.fileData().orElseThrow();
                if (file.fileUri().isPresent()) {
                    result.add(new UriContent(
                            java.net.URI.create(file.fileUri().orElseThrow()),
                            file.mimeType().orElse(null)));
                }
            }
            index++;
        }
        return result;
    }

    private static void addCandidateMetadata(ArrayList<Content> content, Candidate candidate) {
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        candidate.groundingMetadata().ifPresent(grounding -> {
            metadata.put(
                    "gemini.groundingChunkCount",
                    StateValue.integer(
                            grounding.groundingChunks().orElse(List.of()).size()));
            metadata.put(
                    "gemini.groundingSupportCount",
                    StateValue.integer(
                            grounding.groundingSupports().orElse(List.of()).size()));
            if (grounding.webSearchQueries().isPresent()) {
                metadata.put(
                        "gemini.webSearchQueries",
                        StateValue.array(grounding.webSearchQueries().orElseThrow().stream()
                                .map(StateValue::string)
                                .toList()));
            }
        });
        candidate.safetyRatings().ifPresent(ratings -> metadata.put("gemini.safety", safety(ratings)));
        if (!metadata.isEmpty()) {
            content.add(new MetadataContent(metadata));
        }
    }

    private static ErrorContent toolFailure(Candidate candidate, com.google.genai.types.FinishReason reason) {
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        metadata.put("gemini.finishReason", StateValue.string(reason.toString()));
        candidate
                .finishMessage()
                .ifPresent(message -> metadata.put("gemini.finishMessage", StateValue.string(message)));
        return new ErrorContent(
                "Gemini rejected a generated function call.",
                reason.toString(),
                candidate.finishMessage().orElse(null),
                metadata);
    }

    private static ArrayList<Content> partsWithoutFunctionCalls(List<Part> parts, int candidateIndex) {
        ArrayList<Content> content = new ArrayList<>();
        for (Part part : parts) {
            if (part.functionCall().isEmpty()) {
                content.addAll(parts(List.of(part), candidateIndex));
            }
        }
        return content;
    }

    private static StateValue safety(List<SafetyRating> ratings) {
        return StateValue.array(ratings.stream()
                .map(rating -> {
                    LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
                    rating.category().ifPresent(item -> value.put("category", StateValue.string(item.toString())));
                    rating.probability()
                            .ifPresent(item -> value.put("probability", StateValue.string(item.toString())));
                    rating.severity().ifPresent(item -> value.put("severity", StateValue.string(item.toString())));
                    rating.blocked().ifPresent(item -> value.put("blocked", StateValue.bool(item)));
                    rating.probabilityScore()
                            .ifPresent(
                                    item -> value.put("probabilityScore", StateValue.number(BigDecimal.valueOf(item))));
                    return StateValue.object(value);
                })
                .toList());
    }

    private static LinkedHashMap<String, StateValue> responseMetadata(GenerateContentResponse response) {
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        response.sdkHttpResponse().flatMap(value -> value.headers()).ifPresent(headers -> {
            String requestId = firstHeader(headers, "x-request-id", "x-goog-request-id");
            if (requestId != null) {
                metadata.put("gemini.requestId", StateValue.string(requestId));
            }
        });
        response.promptFeedback().ifPresent(feedback -> {
            feedback.blockReason()
                    .ifPresent(value -> metadata.put("gemini.promptBlockReason", StateValue.string(value.toString())));
            feedback.safetyRatings().ifPresent(value -> metadata.put("gemini.promptSafety", safety(value)));
        });
        return metadata;
    }

    private static String firstHeader(Map<String, String> headers, String... names) {
        for (String name : names) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static FinishReason finish(com.google.genai.types.FinishReason reason) {
        return switch (reason.toString()) {
            case "STOP" -> FinishReason.STOP;
            case "MAX_TOKENS" -> FinishReason.LENGTH;
            case "MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL" -> FinishReason.of("error");
            case "SAFETY",
                    "RECITATION",
                    "LANGUAGE",
                    "BLOCKLIST",
                    "PROHIBITED_CONTENT",
                    "SPII",
                    "IMAGE_SAFETY",
                    "IMAGE_PROHIBITED_CONTENT",
                    "IMAGE_RECITATION" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.of(reason.toString());
        };
    }

    private static boolean isToolFailure(com.google.genai.types.FinishReason reason) {
        return reason != null
                && ("MALFORMED_FUNCTION_CALL".equals(reason.toString())
                        || "UNEXPECTED_TOOL_CALL".equals(reason.toString()));
    }

    static UsageDetails usage(GenerateContentResponseUsageMetadata usage) {
        if (usage == null) {
            return null;
        }
        UsageDetails.Builder builder = UsageDetails.builder();
        usage.promptTokenCount().ifPresent(builder::inputTokens);
        usage.candidatesTokenCount().ifPresent(builder::outputTokens);
        usage.totalTokenCount().ifPresent(builder::totalTokens);
        usage.cachedContentTokenCount()
                .ifPresent(value -> builder.value(UsageDetails.CACHE_READ_INPUT_TOKENS, StateValue.integer(value)));
        usage.thoughtsTokenCount()
                .ifPresent(value -> builder.value(UsageDetails.REASONING_OUTPUT_TOKENS, StateValue.integer(value)));
        UsageDetails result = builder.build();
        return result.values().isEmpty() ? null : result;
    }

    private static Map<String, Object> resultMap(StateValue value) {
        Object converted = toJava(value);
        return converted instanceof Map<?, ?> map
                ? castMap(map)
                : Map.of("result", converted == null ? "null" : converted);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Object toJava(StateValue value) {
        return switch (value) {
            case StateValue.NullValue _ -> null;
            case StateValue.BooleanValue bool -> bool.value();
            case StateValue.NumberValue number -> number.value();
            case StateValue.StringValue string -> string.value();
            case StateValue.ArrayValue array ->
                array.values().stream().map(GeminiMapper::toJava).toList();
            case StateValue.ObjectValue object -> {
                LinkedHashMap<String, Object> values = new LinkedHashMap<>();
                object.values().forEach((key, item) -> values.put(key, toJava(item)));
                yield values;
            }
        };
    }

    private static StateValue fromJava(Object value) {
        if (value == null) {
            return StateValue.nullValue();
        }
        if (value instanceof Boolean bool) {
            return StateValue.bool(bool);
        }
        if (value instanceof Number number) {
            return StateValue.number(new BigDecimal(number.toString()));
        }
        if (value instanceof String string) {
            return StateValue.string(string);
        }
        if (value instanceof List<?> list) {
            return StateValue.array(list.stream().map(GeminiMapper::fromJava).toList());
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
            map.forEach((key, item) -> values.put(String.valueOf(key), fromJava(item)));
            return StateValue.object(values);
        }
        throw new GeminiProviderException("unsupported_json_value", null, null);
    }

    private static long estimate(StateValue value) {
        return switch (value) {
            case StateValue.NullValue _ -> 4;
            case StateValue.BooleanValue bool -> bool.value() ? 4 : 5;
            case StateValue.NumberValue number -> number.value().toString().length();
            case StateValue.StringValue string -> string.value().getBytes(StandardCharsets.UTF_8).length;
            case StateValue.ArrayValue array ->
                array.values().stream().mapToLong(GeminiMapper::estimate).sum();
            case StateValue.ObjectValue object ->
                object.values().entrySet().stream()
                        .mapToLong(entry -> entry.getKey().length() + estimate(entry.getValue()))
                        .sum();
        };
    }

    private static void requireInteger(StateValue value, String name, int minimum) {
        if (value == null) {
            return;
        }
        if (!(value instanceof StateValue.NumberValue number)) {
            throw new ValidationException(name + " must be an integer.");
        }
        try {
            if (number.value().intValueExact() < minimum) {
                throw new ValidationException(name + " must be at least " + minimum + ".");
            }
        } catch (ArithmeticException exception) {
            throw new ValidationException(name + " must be an integer.", exception);
        }
    }

    private static ValidationException unsupported(Role role, Content content) {
        return new ValidationException(
                "Gemini does not support content kind '" + content.kind() + "' for role '" + role.value() + "'.");
    }

    record MappedRequest(String model, List<com.google.genai.types.Content> contents, GenerateContentConfig config) {}

    static final class StreamAssembler {
        private final LinkedHashMap<Integer, ToolAccumulator> tools = new LinkedHashMap<>();

        private String responseId;

        private String model;

        private Instant createdAt;

        private FinishReason finish;

        private UsageDetails usage;

        private Map<String, StateValue> terminalMetadata = Map.of();

        private ErrorContent terminalError;

        private long sequence;

        List<ChatResponseUpdate> accept(GenerateContentResponse response) {
            responseId = stable(responseId, response.responseId().orElse(null), "response_id");
            model = stable(model, response.modelVersion().orElse(null), "model");
            createdAt = stable(createdAt, response.createTime().orElse(null), "created_at");
            UsageDetails incomingUsage = usage(response.usageMetadata().orElse(null));
            if (incomingUsage != null) {
                usage = incomingUsage;
            }
            LinkedHashMap<String, StateValue> responseMetadata = responseMetadata(response);
            if (!responseMetadata.isEmpty()) {
                terminalMetadata = Map.copyOf(responseMetadata);
            }
            ArrayList<ChatResponseUpdate> updates = new ArrayList<>();
            List<Candidate> candidates = response.candidates().orElse(List.of());
            if (candidates.size() > 1) {
                throw new GeminiProviderException("multiple_stream_candidates", null, null);
            }
            if (!candidates.isEmpty()) {
                Candidate candidate = candidates.getFirst();
                int candidateIndex = candidate.index().orElse(0);
                com.google.genai.types.FinishReason providerFinish =
                        candidate.finishReason().orElse(null);
                boolean toolFailure = isToolFailure(providerFinish);
                if (candidate.content().isPresent()) {
                    ArrayList<Content> contents = new ArrayList<>();
                    int partIndex = 0;
                    for (Part part : candidate.content().orElseThrow().parts().orElse(List.of())) {
                        if (part.functionCall().isPresent()) {
                            if (!toolFailure) {
                                acceptTool(part.functionCall().orElseThrow(), partIndex, candidateIndex);
                            }
                        } else {
                            contents.addAll(parts(List.of(part), candidateIndex));
                        }
                        partIndex++;
                    }
                    if (!contents.isEmpty()) {
                        updates.add(update(contents, null, null, Map.of()));
                    }
                }
                if (providerFinish != null) {
                    if (finish != null) {
                        throw new GeminiProviderException("duplicate_terminal", null, null);
                    }
                    finish = GeminiMapper.finish(providerFinish);
                    if (toolFailure) {
                        tools.clear();
                        terminalError = toolFailure(candidate, providerFinish);
                        LinkedHashMap<String, StateValue> failureMetadata = new LinkedHashMap<>(terminalMetadata);
                        failureMetadata.put("gemini.finishReason", StateValue.string(providerFinish.toString()));
                        candidate
                                .finishMessage()
                                .ifPresent(message ->
                                        failureMetadata.put("gemini.finishMessage", StateValue.string(message)));
                        terminalMetadata = Map.copyOf(failureMetadata);
                    }
                    LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(terminalMetadata);
                    ArrayList<Content> candidateMetadata = new ArrayList<>();
                    addCandidateMetadata(candidateMetadata, candidate);
                    if (!candidateMetadata.isEmpty()
                            && candidateMetadata.getLast() instanceof MetadataContent metadataContent) {
                        metadata.putAll(metadataContent.values());
                    }
                    terminalMetadata = Map.copyOf(metadata);
                }
            }
            return List.copyOf(updates);
        }

        ChatResponseUpdate finish() {
            if (finish == null) {
                throw new GeminiProviderException("missing_terminal", null, null);
            }
            ArrayList<Content> content = new ArrayList<>();
            if (terminalError != null) {
                content.add(terminalError);
            }
            tools.values().stream()
                    .sorted(Comparator.comparingInt(tool -> tool.index))
                    .map(ToolAccumulator::build)
                    .forEach(content::add);
            return update(content, finish, usage, terminalMetadata);
        }

        private void acceptTool(FunctionCall call, int partIndex, int candidateIndex) {
            int index = partIndex;
            ToolAccumulator tool = tools.computeIfAbsent(index, ignored -> new ToolAccumulator(index));
            tool.id = fragment(tool.id, call.id().orElse("gemini-call-" + candidateIndex + "-" + partIndex));
            tool.name = fragment(tool.name, call.name().orElse(null));
            call.args().ifPresent(args -> tool.arguments.putAll(args));
        }

        private ChatResponseUpdate update(
                List<? extends Content> content,
                FinishReason finishReason,
                UsageDetails usageDetails,
                Map<String, StateValue> metadata) {
            ChatResponseUpdate.Builder builder = ChatResponseUpdate.builder()
                    .sequence(sequence++)
                    .contents(content)
                    .role(Role.ASSISTANT)
                    .metadata(metadata);
            if (responseId != null) {
                builder.responseId(responseId).messageId(responseId);
            }
            if (model != null) {
                builder.model(model);
            }
            if (createdAt != null) {
                builder.createdAt(createdAt);
            }
            if (finishReason != null) {
                builder.finishReason(finishReason);
            }
            if (usageDetails != null) {
                builder.usage(usageDetails);
            }
            return builder.build();
        }

        private static <T> T stable(T current, T incoming, String name) {
            if (incoming == null) {
                return current;
            }
            if (current != null && !current.equals(incoming)) {
                throw new GeminiProviderException("inconsistent_" + name, null, null);
            }
            return incoming;
        }

        private static String fragment(String current, String incoming) {
            if (incoming == null || incoming.isEmpty()) {
                return current;
            }
            if (current == null || current.isEmpty()) {
                return incoming;
            }
            if (current.equals(incoming) || current.endsWith(incoming)) {
                return current;
            }
            if (incoming.startsWith(current)) {
                return incoming;
            }
            return current + incoming;
        }
    }

    private static final class ToolAccumulator {
        private final int index;

        private final LinkedHashMap<String, Object> arguments = new LinkedHashMap<>();

        private String id;

        private String name;

        private ToolAccumulator(int index) {
            this.index = index;
        }

        private FunctionCallContent build() {
            if (id == null || name == null) {
                throw new GeminiProviderException("incomplete_tool_call", null, null);
            }
            return new FunctionCallContent(id, name, fromJava(arguments));
        }
    }
}
