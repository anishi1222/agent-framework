// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.anthropic;

import com.anthropic.core.JsonArray;
import com.anthropic.core.JsonBoolean;
import com.anthropic.core.JsonNull;
import com.anthropic.core.JsonNumber;
import com.anthropic.core.JsonObject;
import com.anthropic.core.JsonString;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageDeltaUsage;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.PlainTextSource;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawMessageDeltaEvent;
import com.anthropic.models.messages.RawMessageStartEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.RedactedThinkingBlockParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceAny;
import com.anthropic.models.messages.ToolChoiceAuto;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.anthropic.models.messages.UrlImageSource;
import com.anthropic.models.messages.UrlPdfSource;
import com.anthropic.models.messages.Usage;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.ReasoningContent;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StructuredOutputOptions;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.UriContent;
import com.microsoft.agents.core.UsageDetails;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.core.internal.StrictJsonCodec;
import com.microsoft.agents.core.internal.StructuredOutputSupport;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import com.microsoft.agents.tools.ToolMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AnthropicMapper {
    private static final Role DEVELOPER = Role.of("developer");

    private static final Set<String> PROVIDER_OPTIONS = Set.of(
            "anthropic.responseSchema", "anthropic.topK", "anthropic.thinkingBudgetTokens", "anthropic.serviceTier");

    static final Set<String> SDK_OUTPUT_BLOCK_VARIANTS = Set.of(
            "text",
            "thinking",
            "redactedThinking",
            "toolUse",
            "serverToolUse",
            "webSearchToolResult",
            "webFetchToolResult",
            "codeExecutionToolResult",
            "bashCodeExecutionToolResult",
            "textEditorCodeExecutionToolResult",
            "toolSearchToolResult",
            "containerUpload");

    private AnthropicMapper() {}

    static void validate(ChatClientRequest request) {
        if (request.messages().isEmpty()) {
            throw new ValidationException("Anthropic requests require at least one message.");
        }
        boolean conversational = false;
        for (com.microsoft.agents.core.Message message : request.messages()) {
            Role role = message.role();
            if (role.equals(DEVELOPER)) {
                throw new ValidationException("Anthropic Messages does not support the developer role.");
            }
            if (!(role.equals(Role.SYSTEM)
                    || role.equals(Role.USER)
                    || role.equals(Role.ASSISTANT)
                    || role.equals(Role.TOOL))) {
                throw new ValidationException("Anthropic does not support role '" + role.value() + "'.");
            }
            if (!role.equals(Role.SYSTEM)) {
                conversational = true;
            }
            if (message.contents().isEmpty()) {
                throw new ValidationException("Anthropic messages require content.");
            }
            for (Content content : message.contents()) {
                validateContent(role, content);
            }
        }
        if (!conversational) {
            throw new ValidationException("Anthropic requires at least one user, assistant, or tool message.");
        }
        for (ToolMetadata tool : request.tools()) {
            if (!tool.capabilities().equals(Set.of(ToolCapability.FUNCTION))) {
                throw new ValidationException(
                        "Anthropic supports only FUNCTION tools; tool '" + tool.name() + "' is unsupported.");
            }
        }
        ChatOptions options = request.options();
        if (options.temperature() != null && options.temperature() > 1.0) {
            throw new ValidationException("Anthropic temperature must be between 0 and 1.");
        }
        if (options.seed() != null
                || options.frequencyPenalty() != null
                || options.presencePenalty() != null
                || options.store() != null
                || options.conversationId() != null) {
            throw new ValidationException(
                    "Anthropic does not support seed, frequencyPenalty, presencePenalty, store, or conversationId.");
        }
        for (Map.Entry<String, StateValue> entry : options.metadata().entrySet()) {
            if (entry.getKey().startsWith("anthropic.") && !PROVIDER_OPTIONS.contains(entry.getKey())) {
                throw new ValidationException("Unsupported Anthropic option '" + entry.getKey() + "'.");
            }
        }
        StructuredOutputSupport.resolve(options, "anthropic.responseSchema");
        requireIntegerOption(options, "anthropic.topK", 1);
        requireIntegerOption(options, "anthropic.thinkingBudgetTokens", 1024);
        StateValue tier = options.metadata().get("anthropic.serviceTier");
        if (tier != null && !(tier instanceof StateValue.StringValue)) {
            throw new ValidationException("anthropic.serviceTier must be a string.");
        }
        if (request.tools().isEmpty()
                && (request.toolMode() == ToolMode.REQUIRED
                        || options.toolChoice() == com.microsoft.agents.core.ToolChoice.REQUIRED)) {
            throw new ValidationException("Anthropic required tool selection needs at least one tool.");
        }
    }

    @SuppressWarnings("deprecation")
    static MessageCreateParams request(ChatClientRequest request, AnthropicChatClientOptions defaults) {
        validate(request);
        ChatOptions options = request.options();
        StrictJsonCodec json = new StrictJsonCodec(
                defaults.maxRequestBytes(),
                defaults.maxResponseBytes(),
                defaults.maxNestingDepth(),
                defaults.maxStringLength(),
                1_000,
                defaults.maxCollectionEntries());
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(options.model() == null ? defaults.model() : options.model())
                .maxTokens(options.maxTokens() == null ? defaults.defaultMaxTokens() : options.maxTokens());
        String system = systemText(request, options);
        if (!system.isEmpty()) {
            builder.system(system);
        }
        for (MessageParam message : messages(request, json)) {
            builder.addMessage(message);
        }
        if (options.temperature() != null) {
            builder.temperature(options.temperature());
        }
        if (options.topP() != null) {
            builder.topP(options.topP());
        }
        options.stop().forEach(builder::addStopSequence);
        StateValue topK = options.metadata().get("anthropic.topK");
        if (topK instanceof StateValue.NumberValue number) {
            builder.topK(number.value().longValueExact());
        }
        StateValue thinking = options.metadata().get("anthropic.thinkingBudgetTokens");
        if (thinking instanceof StateValue.NumberValue number) {
            builder.thinking(ThinkingConfigEnabled.builder()
                    .budgetTokens(number.value().longValueExact())
                    .build());
        }
        StateValue tier = options.metadata().get("anthropic.serviceTier");
        if (tier instanceof StateValue.StringValue string) {
            builder.serviceTier(MessageCreateParams.ServiceTier.of(string.value()));
        }
        StructuredOutputOptions structuredOutput = StructuredOutputSupport.resolve(options, "anthropic.responseSchema");
        if (structuredOutput != null) {
            JsonOutputFormat.Schema.Builder schemaBuilder = JsonOutputFormat.Schema.builder();
            structuredOutput
                    .schema()
                    .values()
                    .forEach((key, value) -> schemaBuilder.putAdditionalProperty(key, sdkJson(value)));
            builder.outputConfig(OutputConfig.builder()
                    .format(JsonOutputFormat.builder()
                            .schema(schemaBuilder.build())
                            .build())
                    .build());
        }
        request.tools().forEach(tool -> builder.addTool(tool(tool)));
        if (!request.tools().isEmpty()) {
            applyToolChoice(builder, request);
        }
        if (options.user() != null) {
            builder.metadata(com.anthropic.models.messages.Metadata.builder()
                    .userId(options.user())
                    .build());
        }
        return builder.build();
    }

    static ChatResponse response(Message message, String requestId) {
        ArrayList<Content> content = contents(message.content());
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        put(metadata, "anthropic.requestId", requestId);
        message.stopReason()
                .ifPresent(reason -> metadata.put("anthropic.stopReason", StateValue.string(reason.toString())));
        message.stopSequence().ifPresent(value -> metadata.put("anthropic.stopSequence", StateValue.string(value)));
        message.stopDetails().ifPresent(value -> metadata.put("anthropic.refusal", StateValue.bool(true)));
        return ChatResponse.builder()
                .messages(List.of(com.microsoft.agents.core.Message.builder(Role.ASSISTANT)
                        .contents(content)
                        .messageId(message.id())
                        .build()))
                .responseId(message.id())
                .model(message.model().toString())
                .finishReason(mapFinish(message.stopReason().orElse(null)))
                .usage(usage(message.usage()))
                .metadata(metadata)
                .build();
    }

    private static void validateContent(Role role, Content content) {
        if (role.equals(Role.SYSTEM)) {
            if (!(content instanceof TextContent)) {
                throw unsupported(role, content);
            }
            return;
        }
        if (role.equals(Role.TOOL)) {
            if (!(content instanceof FunctionResultContent result)) {
                throw unsupported(role, content);
            }
            for (Content item : result.items()) {
                if (!(item instanceof TextContent || item instanceof DataContent || item instanceof UriContent)) {
                    throw unsupported(role, item);
                }
                validateRichInput(Role.USER, item);
            }
            return;
        }
        if (content instanceof TextContent) {
            return;
        }
        if (content instanceof DataContent || content instanceof UriContent) {
            if (!role.equals(Role.USER)) {
                throw unsupported(role, content);
            }
            validateRichInput(role, content);
            return;
        }
        if (content instanceof FunctionCallContent call) {
            if (!role.equals(Role.ASSISTANT) || !(call.arguments() instanceof StateValue.ObjectValue)) {
                throw new ValidationException(
                        "Anthropic function-call arguments must be JSON objects on assistant messages.");
            }
            return;
        }
        if (content instanceof ReasoningContent reasoning) {
            if (!role.equals(Role.ASSISTANT) || reasoning.protectedData() == null) {
                throw new ValidationException(
                        "Anthropic reasoning history requires protectedData containing the provider signature.");
            }
            return;
        }
        throw unsupported(role, content);
    }

    private static void validateRichInput(Role role, Content content) {
        String mediaType;
        if (content instanceof DataContent data) {
            mediaType = data.mediaType();
        } else if (content instanceof UriContent uri) {
            mediaType = uri.mediaType();
            if (!"https".equalsIgnoreCase(uri.uri().getScheme())) {
                throw new ValidationException("Anthropic remote media URIs require HTTPS.");
            }
        } else {
            throw unsupported(role, content);
        }
        if (mediaType == null
                || !(mediaType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")
                        || "application/pdf".equalsIgnoreCase(mediaType)
                        || mediaType.toLowerCase(java.util.Locale.ROOT).startsWith("text/"))) {
            throw unsupported(role, content);
        }
        if (content instanceof UriContent
                && mediaType.toLowerCase(java.util.Locale.ROOT).startsWith("text/")) {
            throw new ValidationException("Anthropic text documents must be supplied inline, not by URI.");
        }
    }

    private static String systemText(ChatClientRequest request, ChatOptions options) {
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

    private static List<MessageParam> messages(ChatClientRequest request, StrictJsonCodec json) {
        ArrayList<MessageParam> messages = new ArrayList<>();
        for (com.microsoft.agents.core.Message source : request.messages()) {
            if (source.role().equals(Role.SYSTEM)) {
                continue;
            }
            ArrayList<ContentBlockParam> blocks = new ArrayList<>();
            for (Content content : source.contents()) {
                if (content instanceof TextContent text) {
                    blocks.add(ContentBlockParam.ofText(
                            TextBlockParam.builder().text(text.text()).build()));
                } else if (content instanceof DataContent || content instanceof UriContent) {
                    blocks.add(richBlock(content));
                } else if (content instanceof FunctionCallContent call) {
                    ToolUseBlockParam.Input.Builder input = ToolUseBlockParam.Input.builder();
                    ((StateValue.ObjectValue) call.arguments())
                            .values()
                            .forEach((key, value) -> input.putAdditionalProperty(key, sdkJson(value)));
                    blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                            .id(call.callId())
                            .name(call.name())
                            .input(input.build())
                            .build()));
                } else if (content instanceof FunctionResultContent result) {
                    blocks.add(ContentBlockParam.ofToolResult(toolResult(result, json)));
                } else if (content instanceof ReasoningContent reasoning) {
                    if (reasoning.text() == null) {
                        blocks.add(ContentBlockParam.ofRedactedThinking(RedactedThinkingBlockParam.builder()
                                .data(reasoning.protectedData())
                                .build()));
                    } else {
                        blocks.add(ContentBlockParam.ofThinking(ThinkingBlockParam.builder()
                                .thinking(reasoning.text())
                                .signature(reasoning.protectedData())
                                .build()));
                    }
                }
            }
            MessageParam.Role role =
                    source.role().equals(Role.ASSISTANT) ? MessageParam.Role.ASSISTANT : MessageParam.Role.USER;
            messages.add(MessageParam.builder()
                    .role(role)
                    .contentOfBlockParams(blocks)
                    .build());
        }
        return List.copyOf(messages);
    }

    private static ToolResultBlockParam toolResult(FunctionResultContent result, StrictJsonCodec json) {
        ToolResultBlockParam.Builder builder =
                ToolResultBlockParam.builder().toolUseId(result.callId()).isError(result.error() != null);
        if (!result.items().isEmpty()) {
            ArrayList<ToolResultBlockParam.Content.Block> blocks = new ArrayList<>();
            for (Content item : result.items()) {
                ContentBlockParam block = richOrTextBlock(item);
                if (block.text().isPresent()) {
                    blocks.add(ToolResultBlockParam.Content.Block.ofText(block.asText()));
                } else if (block.image().isPresent()) {
                    blocks.add(ToolResultBlockParam.Content.Block.ofImage(block.asImage()));
                } else if (block.document().isPresent()) {
                    blocks.add(ToolResultBlockParam.Content.Block.ofDocument(block.asDocument()));
                }
            }
            builder.contentOfBlocks(blocks);
        } else if (result.error() != null) {
            builder.content("Error: " + result.error());
        } else if (result.result() instanceof StateValue.StringValue text) {
            builder.content(text.value());
        } else {
            builder.content(new String(json.write(result.result()), StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    private static ContentBlockParam richOrTextBlock(Content content) {
        if (content instanceof TextContent text) {
            return ContentBlockParam.ofText(
                    TextBlockParam.builder().text(text.text()).build());
        }
        return richBlock(content);
    }

    private static ContentBlockParam richBlock(Content content) {
        if (content instanceof DataContent data) {
            String media = data.mediaType().toLowerCase(java.util.Locale.ROOT);
            String encoded = Base64.getEncoder().encodeToString(data.data());
            if (media.startsWith("image/")) {
                return ContentBlockParam.ofImage(ImageBlockParam.builder()
                        .source(Base64ImageSource.builder()
                                .mediaType(Base64ImageSource.MediaType.of(media))
                                .data(encoded)
                                .build())
                        .build());
            }
            if ("application/pdf".equals(media)) {
                return ContentBlockParam.ofDocument(DocumentBlockParam.builder()
                        .source(Base64PdfSource.builder().data(encoded).build())
                        .build());
            }
            if (media.startsWith("text/")) {
                return ContentBlockParam.ofDocument(DocumentBlockParam.builder()
                        .source(PlainTextSource.builder()
                                .data(decodeUtf8(data.data()))
                                .build())
                        .build());
            }
        }
        if (content instanceof UriContent uri) {
            String media = uri.mediaType().toLowerCase(java.util.Locale.ROOT);
            if (media.startsWith("image/")) {
                return ContentBlockParam.ofImage(ImageBlockParam.builder()
                        .source(UrlImageSource.builder()
                                .url(uri.uri().toString())
                                .build())
                        .build());
            }
            if ("application/pdf".equals(media)) {
                return ContentBlockParam.ofDocument(DocumentBlockParam.builder()
                        .source(UrlPdfSource.builder().url(uri.uri().toString()).build())
                        .build());
            }
        }
        throw new ValidationException("Unsupported Anthropic rich content.");
    }

    private static Tool tool(ToolMetadata source) {
        Tool.InputSchema.Builder schema = Tool.InputSchema.builder();
        source.inputSchema().values().forEach((key, value) -> {
            if ("type".equals(key)) {
                schema.type(sdkJson(value));
            } else {
                schema.putAdditionalProperty(key, sdkJson(value));
            }
        });
        return Tool.builder()
                .name(source.name())
                .description(source.description())
                .inputSchema(schema.build())
                .build();
    }

    private static void applyToolChoice(MessageCreateParams.Builder builder, ChatClientRequest request) {
        ToolMode mode = request.toolMode();
        if (mode == ToolMode.NONE && request.options().toolChoice() != null) {
            mode = switch (request.options().toolChoice()) {
                case NONE -> ToolMode.NONE;
                case AUTO -> ToolMode.AUTO;
                case REQUIRED -> ToolMode.REQUIRED;
            };
        }
        boolean disableParallel = Boolean.FALSE.equals(request.options().allowMultipleToolCalls());
        switch (mode) {
            case NONE -> builder.toolChoice(ToolChoiceNone.builder().build());
            case AUTO ->
                builder.toolChoice(ToolChoiceAuto.builder()
                        .disableParallelToolUse(disableParallel)
                        .build());
            case REQUIRED ->
                builder.toolChoice(ToolChoiceAny.builder()
                        .disableParallelToolUse(disableParallel)
                        .build());
        }
    }

    private static ArrayList<Content> contents(List<ContentBlock> blocks) {
        ArrayList<Content> result = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block.text().isPresent()) {
                com.anthropic.models.messages.TextBlock text = block.asText();
                LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
                if (text.citations().isPresent()) {
                    metadata.put(
                            "anthropic.citationCount",
                            StateValue.integer(text.citations().orElseThrow().size()));
                }
                result.add(new TextContent(text.text(), metadata));
            } else if (block.thinking().isPresent()) {
                var thinking = block.asThinking();
                result.add(new ReasoningContent(null, thinking.thinking(), thinking.signature(), Map.of()));
            } else if (block.redactedThinking().isPresent()) {
                result.add(new ReasoningContent(
                        null, null, block.asRedactedThinking().data(), Map.of()));
            } else if (block.toolUse().isPresent()) {
                var call = block.asToolUse();
                result.add(new FunctionCallContent(call.id(), call.name(), frameworkJson(call._input())));
            } else if (block.serverToolUse().isPresent()) {
                throw unsupportedOutputBlock("server_tool_use");
            } else if (block.webSearchToolResult().isPresent()) {
                throw unsupportedOutputBlock("web_search_tool_result");
            } else if (block.webFetchToolResult().isPresent()) {
                throw unsupportedOutputBlock("web_fetch_tool_result");
            } else if (block.codeExecutionToolResult().isPresent()) {
                throw unsupportedOutputBlock("code_execution_tool_result");
            } else if (block.bashCodeExecutionToolResult().isPresent()) {
                throw unsupportedOutputBlock("bash_code_execution_tool_result");
            } else if (block.textEditorCodeExecutionToolResult().isPresent()) {
                throw unsupportedOutputBlock("text_editor_code_execution_tool_result");
            } else if (block.toolSearchToolResult().isPresent()) {
                throw unsupportedOutputBlock("tool_search_tool_result");
            } else if (block.containerUpload().isPresent()) {
                throw unsupportedOutputBlock("container_upload");
            } else {
                throw unsupportedOutputBlock(block._json().isPresent() ? "unknown" : "invalid");
            }
        }
        return result;
    }

    static FinishReason mapFinish(StopReason reason) {
        if (reason == null) {
            return null;
        }
        return switch (reason.toString()) {
            case "end_turn", "stop_sequence", "pause_turn" -> FinishReason.STOP;
            case "max_tokens", "model_context_window_exceeded" -> FinishReason.LENGTH;
            case "tool_use" -> FinishReason.TOOL_CALLS;
            case "refusal" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.of(reason.toString());
        };
    }

    static UsageDetails usage(Usage usage) {
        UsageDetails.Builder builder = UsageDetails.builder()
                .inputTokens(usage.inputTokens())
                .outputTokens(usage.outputTokens())
                .totalTokens(Math.addExact(usage.inputTokens(), usage.outputTokens()));
        usage.cacheCreationInputTokens()
                .ifPresent(value -> builder.value(UsageDetails.CACHE_CREATION_INPUT_TOKENS, StateValue.integer(value)));
        usage.cacheReadInputTokens()
                .ifPresent(value -> builder.value(UsageDetails.CACHE_READ_INPUT_TOKENS, StateValue.integer(value)));
        return builder.build();
    }

    private static UsageDetails usage(Usage initial, MessageDeltaUsage delta) {
        long output = delta.outputTokens();
        UsageDetails.Builder builder = UsageDetails.builder()
                .inputTokens(initial.inputTokens())
                .outputTokens(output)
                .totalTokens(Math.addExact(initial.inputTokens(), output));
        initial.cacheCreationInputTokens()
                .ifPresent(value -> builder.value(UsageDetails.CACHE_CREATION_INPUT_TOKENS, StateValue.integer(value)));
        initial.cacheReadInputTokens()
                .ifPresent(value -> builder.value(UsageDetails.CACHE_READ_INPUT_TOKENS, StateValue.integer(value)));
        return builder.build();
    }

    private static JsonValue sdkJson(StateValue value) {
        return JsonValue.from(toJava(value));
    }

    private static Object toJava(StateValue value) {
        return switch (value) {
            case StateValue.NullValue _ -> null;
            case StateValue.BooleanValue bool -> bool.value();
            case StateValue.NumberValue number -> number.value();
            case StateValue.StringValue string -> string.value();
            case StateValue.ArrayValue array ->
                array.values().stream().map(AnthropicMapper::toJava).toList();
            case StateValue.ObjectValue object -> {
                LinkedHashMap<String, Object> values = new LinkedHashMap<>();
                object.values().forEach((key, item) -> values.put(key, toJava(item)));
                yield values;
            }
        };
    }

    private static StateValue frameworkJson(JsonValue value) {
        if (value instanceof JsonNull) {
            return StateValue.nullValue();
        }
        if (value instanceof JsonBoolean bool) {
            return StateValue.bool(bool.value());
        }
        if (value instanceof JsonNumber number) {
            return StateValue.number(new java.math.BigDecimal(number.value().toString()));
        }
        if (value instanceof JsonString string) {
            return StateValue.string(string.value());
        }
        if (value instanceof JsonArray array) {
            return StateValue.array(
                    array.values().stream().map(AnthropicMapper::frameworkJson).toList());
        }
        if (value instanceof JsonObject object) {
            LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
            object.values().forEach((key, item) -> values.put(key, frameworkJson(item)));
            return StateValue.object(values);
        }
        throw new AnthropicProviderException("unsupported_sdk_json", null, null, null);
    }

    private static String decodeUtf8(byte[] value) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new ValidationException("Anthropic text document is not valid UTF-8.", exception);
        }
    }

    private static void requireIntegerOption(ChatOptions options, String name, long minimum) {
        StateValue value = options.metadata().get(name);
        if (value == null) {
            return;
        }
        if (!(value instanceof StateValue.NumberValue number)) {
            throw new ValidationException(name + " must be an integer.");
        }
        try {
            if (number.value().longValueExact() < minimum) {
                throw new ValidationException(name + " must be at least " + minimum + ".");
            }
        } catch (ArithmeticException exception) {
            throw new ValidationException(name + " must be an integer.", exception);
        }
    }

    private static ValidationException unsupported(Role role, Content content) {
        return new ValidationException(
                "Anthropic does not support content kind '" + content.kind() + "' for role '" + role.value() + "'.");
    }

    private static AnthropicProviderException unsupportedOutputBlock(String blockType) {
        return new AnthropicProviderException("unsupported_output_block", null, null, blockType);
    }

    private static void put(Map<String, StateValue> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, StateValue.string(value));
        }
    }

    static final class StreamAssembler {
        private final StrictJsonCodec codec;

        private final String requestId;

        private final LinkedHashMap<Long, ToolAccumulator> tools = new LinkedHashMap<>();

        private String responseId;

        private String model;

        private Usage initialUsage;

        private MessageDeltaUsage deltaUsage;

        private FinishReason finish;

        private String rawFinish;

        private long sequence;

        private boolean stopped;

        StreamAssembler(StrictJsonCodec codec, String requestId) {
            this.codec = codec;
            this.requestId = requestId;
        }

        List<ChatResponseUpdate> accept(RawMessageStreamEvent event) {
            if (stopped) {
                throw new AnthropicProviderException("event_after_terminal", null, null, null);
            }
            ArrayList<ChatResponseUpdate> updates = new ArrayList<>();
            String kind = "unknown";
            try {
                if (event.messageStart().isPresent()) {
                    kind = "message_start";
                    onStart(event.asMessageStart());
                } else if (event.contentBlockStart().isPresent()) {
                    kind = "content_block_start";
                    ChatResponseUpdate update = onContentStart(event.asContentBlockStart());
                    if (update != null) {
                        updates.add(update);
                    }
                } else if (event.contentBlockDelta().isPresent()) {
                    kind = "content_block_delta";
                    ChatResponseUpdate update = onContentDelta(event.asContentBlockDelta());
                    if (update != null) {
                        updates.add(update);
                    }
                } else if (event.contentBlockStop().isPresent()) {
                    kind = "content_block_stop";
                    ToolAccumulator tool = tools.get(event.asContentBlockStop().index());
                    if (tool != null && !tool.emitted) {
                        tool.emitted = true;
                        updates.add(update(List.of(tool.build()), null, null));
                    }
                } else if (event.messageDelta().isPresent()) {
                    kind = "message_delta";
                    onMessageDelta(event.asMessageDelta());
                } else if (event.messageStop().isPresent()) {
                    kind = "message_stop";
                    if (stopped) {
                        throw new AnthropicProviderException("duplicate_terminal", null, null, null);
                    }
                    stopped = true;
                    tools.values().stream()
                            .filter(tool -> !tool.emitted)
                            .sorted(Comparator.comparingLong(tool -> tool.index))
                            .forEach(tool -> {
                                tool.emitted = true;
                                updates.add(update(List.of(tool.build()), null, null));
                            });
                    updates.add(update(
                            List.of(),
                            finish == null ? FinishReason.STOP : finish,
                            initialUsage == null
                                    ? null
                                    : deltaUsage == null ? usage(initialUsage) : usage(initialUsage, deltaUsage)));
                } else {
                    throw new AnthropicProviderException("unsupported_stream_event", null, null, "unknown");
                }
            } catch (com.anthropic.errors.AnthropicInvalidDataException exception) {
                throw new AnthropicProviderException("invalid_stream_" + kind, null, null, null);
            }
            return List.copyOf(updates);
        }

        void requireTerminal() {
            if (!stopped) {
                throw new AnthropicProviderException("missing_terminal", null, null, null);
            }
        }

        private void onStart(RawMessageStartEvent event) {
            if (responseId != null) {
                throw new AnthropicProviderException("duplicate_message_start", null, null, null);
            }
            Message message = event.message();
            responseId = message.id();
            model = message.model().toString();
            initialUsage = message.usage();
        }

        private ChatResponseUpdate onContentStart(RawContentBlockStartEvent event) {
            var block = event.contentBlock();
            if (block.text().isPresent()) {
                return block.asText().text().isEmpty()
                        ? null
                        : update(List.of(new TextContent(block.asText().text())), null, null);
            }
            if (block.thinking().isPresent()) {
                var thinking = block.asThinking();
                return update(
                        List.of(new ReasoningContent(null, thinking.thinking(), thinking.signature(), Map.of())),
                        null,
                        null);
            }
            if (block.redactedThinking().isPresent()) {
                return update(
                        List.of(new ReasoningContent(
                                null, null, block.asRedactedThinking().data(), Map.of())),
                        null,
                        null);
            }
            if (block.toolUse().isPresent()) {
                var call = block.asToolUse();
                ToolAccumulator tool = new ToolAccumulator(event.index(), call.id(), call.name(), codec);
                if (call._input() instanceof JsonObject object
                        && !object.values().isEmpty()) {
                    tool.arguments.append(new String(codec.write(frameworkJson(object)), StandardCharsets.UTF_8));
                }
                tools.put(event.index(), tool);
                return null;
            }
            if (block.serverToolUse().isPresent()) {
                throw unsupportedOutputBlock("server_tool_use");
            }
            if (block.webSearchToolResult().isPresent()) {
                throw unsupportedOutputBlock("web_search_tool_result");
            }
            if (block.webFetchToolResult().isPresent()) {
                throw unsupportedOutputBlock("web_fetch_tool_result");
            }
            if (block.codeExecutionToolResult().isPresent()) {
                throw unsupportedOutputBlock("code_execution_tool_result");
            }
            if (block.bashCodeExecutionToolResult().isPresent()) {
                throw unsupportedOutputBlock("bash_code_execution_tool_result");
            }
            if (block.textEditorCodeExecutionToolResult().isPresent()) {
                throw unsupportedOutputBlock("text_editor_code_execution_tool_result");
            }
            if (block.toolSearchToolResult().isPresent()) {
                throw unsupportedOutputBlock("tool_search_tool_result");
            }
            if (block.containerUpload().isPresent()) {
                throw unsupportedOutputBlock("container_upload");
            }
            throw unsupportedOutputBlock(block._json().isPresent() ? "unknown" : "invalid");
        }

        private ChatResponseUpdate onContentDelta(RawContentBlockDeltaEvent event) {
            var delta = event.delta();
            if (delta.text().isPresent()) {
                return update(List.of(new TextContent(delta.asText().text())), null, null);
            }
            if (delta.thinking().isPresent()) {
                return update(
                        List.of(new ReasoningContent(null, delta.asThinking().thinking(), null, Map.of())), null, null);
            }
            if (delta.signature().isPresent()) {
                return update(
                        List.of(new ReasoningContent(
                                null, null, delta.asSignature().signature(), Map.of())),
                        null,
                        null);
            }
            if (delta.inputJson().isPresent()) {
                ToolAccumulator tool = tools.get(event.index());
                if (tool == null) {
                    throw new AnthropicProviderException("tool_delta_without_start", null, null, null);
                }
                tool.arguments.append(delta.asInputJson().partialJson());
                return null;
            }
            if (delta.citations().isPresent()) {
                throw new AnthropicProviderException("unsupported_stream_delta", null, null, "citations_delta");
            }
            throw new AnthropicProviderException("unsupported_stream_delta", null, null, "unknown");
        }

        private void onMessageDelta(RawMessageDeltaEvent event) {
            rawFinish = event.delta().stopReason().map(Object::toString).orElse(null);
            finish = mapFinish(event.delta().stopReason().orElse(null));
            deltaUsage = event.usage();
        }

        private ChatResponseUpdate update(
                List<? extends Content> content, FinishReason finishReason, UsageDetails usageDetails) {
            LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
            put(metadata, "anthropic.requestId", requestId);
            put(metadata, "anthropic.stopReason", rawFinish);
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
            if (finishReason != null) {
                builder.finishReason(finishReason);
            }
            if (usageDetails != null) {
                builder.usage(usageDetails);
            }
            return builder.build();
        }
    }

    private static final class ToolAccumulator {
        private final long index;

        private final String id;

        private final String name;

        private final StrictJsonCodec codec;

        private final StringBuilder arguments = new StringBuilder();

        private boolean emitted;

        private ToolAccumulator(long index, String id, String name, StrictJsonCodec codec) {
            this.index = index;
            this.id = id;
            this.name = name;
            this.codec = codec;
        }

        private FunctionCallContent build() {
            StateValue parsed;
            if (arguments.isEmpty()) {
                parsed = StateValue.object(Map.of());
            } else {
                try {
                    parsed = codec.parse(arguments.toString().getBytes(StandardCharsets.UTF_8));
                } catch (com.microsoft.agents.core.SerializationException exception) {
                    throw new AnthropicProviderException("malformed_tool_arguments", null, null, null);
                }
            }
            return new FunctionCallContent(id, name, parsed);
        }
    }
}
