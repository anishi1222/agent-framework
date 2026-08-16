// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.bedrock;

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
import com.microsoft.agents.core.MetadataContent;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.AnyToolChoice;
import software.amazon.awssdk.services.bedrockruntime.model.AudioBlock;
import software.amazon.awssdk.services.bedrockruntime.model.AudioFormat;
import software.amazon.awssdk.services.bedrockruntime.model.AudioSource;
import software.amazon.awssdk.services.bedrockruntime.model.AutoToolChoice;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDeltaEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStartEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStopEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamMetadataEvent;
import software.amazon.awssdk.services.bedrockruntime.model.DocumentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.DocumentFormat;
import software.amazon.awssdk.services.bedrockruntime.model.DocumentSource;
import software.amazon.awssdk.services.bedrockruntime.model.ImageBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ImageFormat;
import software.amazon.awssdk.services.bedrockruntime.model.ImageSource;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.JsonSchemaDefinition;
import software.amazon.awssdk.services.bedrockruntime.model.MessageStartEvent;
import software.amazon.awssdk.services.bedrockruntime.model.MessageStopEvent;
import software.amazon.awssdk.services.bedrockruntime.model.OutputConfig;
import software.amazon.awssdk.services.bedrockruntime.model.OutputFormat;
import software.amazon.awssdk.services.bedrockruntime.model.OutputFormatStructure;
import software.amazon.awssdk.services.bedrockruntime.model.OutputFormatType;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningTextBlock;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolChoice;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultStatus;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

final class BedrockMapper {
    private static final Role DEVELOPER = Role.of("developer");

    private static final Set<String> PROVIDER_OPTIONS =
            Set.of("bedrock.responseSchema", "bedrock.requestMetadata", "bedrock.outputEffort");

    private BedrockMapper() {}

    static void validate(ChatClientRequest request, BedrockChatClientOptions defaults) {
        if (request.messages().isEmpty()) {
            throw new ValidationException("Bedrock requests require at least one message.");
        }
        long estimatedBytes = 0;
        for (Message message : request.messages()) {
            Role role = message.role();
            if (role.equals(DEVELOPER)) {
                throw new ValidationException("Bedrock Converse does not support the developer role.");
            }
            if (!(role.equals(Role.SYSTEM)
                    || role.equals(Role.USER)
                    || role.equals(Role.ASSISTANT)
                    || role.equals(Role.TOOL))) {
                throw new ValidationException("Bedrock does not support role '" + role.value() + "'.");
            }
            for (Content content : message.contents()) {
                estimatedBytes = Math.addExact(estimatedBytes, validateContent(role, content));
            }
        }
        if (estimatedBytes > defaults.maxRequestBytes()) {
            throw new ValidationException("Bedrock request content exceeds maxRequestBytes.");
        }
        for (ToolMetadata tool : request.tools()) {
            if (!tool.capabilities().equals(Set.of(ToolCapability.FUNCTION))) {
                throw new ValidationException(
                        "Bedrock supports only FUNCTION tools; tool '" + tool.name() + "' is unsupported.");
            }
        }
        ChatOptions options = request.options();
        if (options.seed() != null
                || options.frequencyPenalty() != null
                || options.presencePenalty() != null
                || options.allowMultipleToolCalls() != null
                || options.user() != null
                || options.store() != null
                || options.conversationId() != null) {
            throw new ValidationException("Bedrock does not support seed, frequencyPenalty, presencePenalty, "
                    + "allowMultipleToolCalls, user, store, or conversationId.");
        }
        options.metadata().forEach((key, value) -> {
            if (key.startsWith("bedrock.") && !PROVIDER_OPTIONS.contains(key)) {
                throw new ValidationException("Unsupported Bedrock option '" + key + "'.");
            }
        });
        StructuredOutputSupport.resolve(options, "bedrock.responseSchema");
        if (options.metadata().get("bedrock.requestMetadata") instanceof StateValue value
                && !(value instanceof StateValue.ObjectValue)) {
            throw new ValidationException("bedrock.requestMetadata must be a JSON object.");
        }
        if (options.metadata().get("bedrock.outputEffort") instanceof StateValue value
                && !(value instanceof StateValue.StringValue)) {
            throw new ValidationException("bedrock.outputEffort must be a string.");
        }
        if (request.tools().isEmpty()
                && (request.toolMode() == ToolMode.REQUIRED
                        || options.toolChoice() == com.microsoft.agents.core.ToolChoice.REQUIRED)) {
            throw new ValidationException("Bedrock required tool selection needs at least one tool.");
        }
    }

    static ConverseRequest request(ChatClientRequest request, BedrockChatClientOptions defaults, StrictJsonCodec json) {
        validate(request, defaults);
        ChatOptions options = request.options();
        ConverseRequest.Builder builder = ConverseRequest.builder()
                .modelId(options.model() == null ? defaults.model() : options.model())
                .messages(messages(request));
        ArrayList<SystemContentBlock> system = system(request, options);
        if (!system.isEmpty()) {
            builder.system(system);
        }
        InferenceConfiguration.Builder inference = InferenceConfiguration.builder();
        boolean hasInference = false;
        if (options.maxTokens() != null) {
            inference.maxTokens(options.maxTokens());
            hasInference = true;
        }
        if (options.temperature() != null) {
            inference.temperature(options.temperature().floatValue());
            hasInference = true;
        }
        if (options.topP() != null) {
            inference.topP(options.topP().floatValue());
            hasInference = true;
        }
        if (!options.stop().isEmpty()) {
            inference.stopSequences(options.stop());
            hasInference = true;
        }
        if (hasInference) {
            builder.inferenceConfig(inference.build());
        }
        ToolMode mode = effectiveToolMode(request);
        if (!request.tools().isEmpty() && mode != ToolMode.NONE) {
            ToolConfiguration.Builder tools = ToolConfiguration.builder()
                    .tools(request.tools().stream().map(BedrockMapper::tool).toList());
            tools.toolChoice(toolChoice(mode));
            builder.toolConfig(tools.build());
        }
        StateValue requestMetadata = options.metadata().get("bedrock.requestMetadata");
        if (requestMetadata instanceof StateValue.ObjectValue object) {
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            object.values().forEach((key, value) -> {
                if (!(value instanceof StateValue.StringValue string)) {
                    throw new ValidationException("bedrock.requestMetadata values must be strings.");
                }
                values.put(key, string.value());
            });
            builder.requestMetadata(values);
        }
        StructuredOutputOptions structuredOutput = StructuredOutputSupport.resolve(options, "bedrock.responseSchema");
        StateValue effort = options.metadata().get("bedrock.outputEffort");
        if (structuredOutput != null || effort != null) {
            OutputConfig.Builder output = OutputConfig.builder();
            if (structuredOutput != null) {
                String encoded = new String(json.write(structuredOutput.schema()), StandardCharsets.UTF_8);
                JsonSchemaDefinition.Builder definition = JsonSchemaDefinition.builder()
                        .name(structuredOutput.name())
                        .schema(encoded);
                if (structuredOutput.description() != null) {
                    definition.description(structuredOutput.description());
                }
                output.textFormat(OutputFormat.builder()
                        .type(OutputFormatType.JSON_SCHEMA)
                        .structure(OutputFormatStructure.fromJsonSchema(definition.build()))
                        .build());
            }
            if (effort instanceof StateValue.StringValue string) {
                output.effort(string.value());
            }
            builder.outputConfig(output.build());
        }
        return builder.build();
    }

    static software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest streamRequest(
            ChatClientRequest request, BedrockChatClientOptions defaults, StrictJsonCodec json) {
        ConverseRequest finite = request(request, defaults, json);
        return software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest.builder()
                .modelId(finite.modelId())
                .messages(finite.messages())
                .system(finite.system())
                .inferenceConfig(finite.inferenceConfig())
                .toolConfig(finite.toolConfig())
                .requestMetadata(finite.requestMetadata())
                .outputConfig(finite.outputConfig())
                .build();
    }

    static ChatResponse response(ConverseResponse response) {
        String requestId = response.responseMetadata() == null
                ? null
                : response.responseMetadata().requestId();
        ArrayList<Content> content = contents(response.output().message().content());
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        put(metadata, "bedrock.requestId", requestId);
        put(metadata, "bedrock.stopReason", response.stopReasonAsString());
        if (response.metrics() != null && response.metrics().latencyMs() != null) {
            metadata.put(
                    "bedrock.latencyMs", StateValue.integer(response.metrics().latencyMs()));
        }
        if (response.trace() != null) {
            metadata.put("bedrock.guardrailTracePresent", StateValue.bool(true));
        }
        if (response.additionalModelResponseFields() != null) {
            metadata.put(
                    "bedrock.additionalModelResponseFields",
                    frameworkDocument(response.additionalModelResponseFields()));
        }
        return ChatResponse.builder()
                .messages(List.of(
                        Message.builder(Role.ASSISTANT).contents(content).build()))
                .model(null)
                .finishReason(mapFinish(response.stopReason()))
                .usage(usage(response.usage()))
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
            if (!(content instanceof FunctionResultContent result)) {
                throw unsupported(role, content);
            }
            long bytes = estimate(result.result());
            for (Content item : result.items()) {
                bytes = Math.addExact(bytes, validateRich(Role.USER, item, false));
            }
            return bytes;
        }
        if (content instanceof TextContent text) {
            return text.text().getBytes(StandardCharsets.UTF_8).length;
        }
        if (content instanceof DataContent) {
            if (!role.equals(Role.USER)) {
                throw unsupported(role, content);
            }
            return validateRich(role, content, true);
        }
        if (content instanceof UriContent) {
            throw new ValidationException("Bedrock Converse does not accept remote URI content.");
        }
        if (content instanceof FunctionCallContent call) {
            if (!role.equals(Role.ASSISTANT) || !(call.arguments() instanceof StateValue.ObjectValue)) {
                throw new ValidationException("Bedrock function calls require assistant role and object arguments.");
            }
            return estimate(call.arguments());
        }
        if (content instanceof ReasoningContent reasoning) {
            if (!role.equals(Role.ASSISTANT) || reasoning.protectedData() == null) {
                throw new ValidationException("Bedrock reasoning history requires protectedData.");
            }
            return (reasoning.text() == null ? 0 : reasoning.text().length())
                    + reasoning.protectedData().length();
        }
        throw unsupported(role, content);
    }

    private static long validateRich(Role role, Content content, boolean allowAudio) {
        if (content instanceof TextContent text) {
            return text.text().getBytes(StandardCharsets.UTF_8).length;
        }
        if (!(content instanceof DataContent data)) {
            throw unsupported(role, content);
        }
        String media = data.mediaType().toLowerCase(Locale.ROOT);
        if (media.startsWith("image/")
                || media.startsWith("text/")
                || "application/pdf".equals(media)
                || "text/csv".equals(media)
                || allowAudio && media.startsWith("audio/")) {
            return data.data().length;
        }
        throw unsupported(role, content);
    }

    private static ArrayList<SystemContentBlock> system(ChatClientRequest request, ChatOptions options) {
        ArrayList<SystemContentBlock> system = new ArrayList<>();
        if (options.instructions() != null) {
            system.add(SystemContentBlock.fromText(options.instructions()));
        }
        request.messages().stream()
                .filter(message -> message.role().equals(Role.SYSTEM))
                .flatMap(message -> message.contents().stream())
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .map(SystemContentBlock::fromText)
                .forEach(system::add);
        return system;
    }

    private static List<software.amazon.awssdk.services.bedrockruntime.model.Message> messages(
            ChatClientRequest request) {
        ArrayList<software.amazon.awssdk.services.bedrockruntime.model.Message> messages = new ArrayList<>();
        for (Message message : request.messages()) {
            if (message.role().equals(Role.SYSTEM)) {
                continue;
            }
            ArrayList<ContentBlock> blocks = new ArrayList<>();
            for (Content content : message.contents()) {
                blocks.add(contentBlock(content));
            }
            ConversationRole role =
                    message.role().equals(Role.ASSISTANT) ? ConversationRole.ASSISTANT : ConversationRole.USER;
            messages.add(software.amazon.awssdk.services.bedrockruntime.model.Message.builder()
                    .role(role)
                    .content(blocks)
                    .build());
        }
        return List.copyOf(messages);
    }

    private static ContentBlock contentBlock(Content content) {
        if (content instanceof TextContent text) {
            return ContentBlock.fromText(text.text());
        }
        if (content instanceof DataContent data) {
            return dataBlock(data);
        }
        if (content instanceof FunctionCallContent call) {
            return ContentBlock.fromToolUse(ToolUseBlock.builder()
                    .toolUseId(call.callId())
                    .name(call.name())
                    .input(document(call.arguments()))
                    .build());
        }
        if (content instanceof FunctionResultContent result) {
            ArrayList<ToolResultContentBlock> values = new ArrayList<>();
            if (!result.items().isEmpty()) {
                for (Content item : result.items()) {
                    if (item instanceof TextContent text) {
                        values.add(ToolResultContentBlock.fromText(text.text()));
                    } else if (item instanceof DataContent data) {
                        ContentBlock block = dataBlock(data);
                        if (block.image() != null) {
                            values.add(ToolResultContentBlock.fromImage(block.image()));
                        } else if (block.document() != null) {
                            values.add(ToolResultContentBlock.fromDocument(block.document()));
                        } else {
                            throw unsupported(Role.TOOL, item);
                        }
                    }
                }
            } else {
                values.add(ToolResultContentBlock.fromJson(document(result.result())));
            }
            return ContentBlock.fromToolResult(ToolResultBlock.builder()
                    .toolUseId(result.callId())
                    .status(result.error() == null ? ToolResultStatus.SUCCESS : ToolResultStatus.ERROR)
                    .content(values)
                    .build());
        }
        if (content instanceof ReasoningContent reasoning) {
            if (reasoning.text() != null) {
                return ContentBlock.fromReasoningContent(
                        ReasoningContentBlock.fromReasoningText(ReasoningTextBlock.builder()
                                .text(reasoning.text())
                                .signature(reasoning.protectedData())
                                .build()));
            }
            return ContentBlock.fromReasoningContent(
                    ReasoningContentBlock.fromRedactedContent(SdkBytes.fromUtf8String(reasoning.protectedData())));
        }
        throw unsupported(Role.USER, content);
    }

    private static ContentBlock dataBlock(DataContent data) {
        String media = data.mediaType().toLowerCase(Locale.ROOT);
        SdkBytes bytes = SdkBytes.fromByteArray(data.data());
        if (media.startsWith("image/")) {
            return ContentBlock.fromImage(ImageBlock.builder()
                    .format(ImageFormat.fromValue(
                            media.substring("image/".length()).replace("jpg", "jpeg")))
                    .source(ImageSource.builder().bytes(bytes).build())
                    .build());
        }
        if (media.startsWith("audio/")) {
            return ContentBlock.fromAudio(AudioBlock.builder()
                    .format(AudioFormat.fromValue(
                            media.substring("audio/".length()).replace("mpeg", "mp3")))
                    .source(AudioSource.builder().bytes(bytes).build())
                    .build());
        }
        String format = "application/pdf".equals(media)
                ? "pdf"
                : media.startsWith("text/") ? media.substring("text/".length()).replace("plain", "txt") : "txt";
        String name = data.metadata().get("filename") instanceof StateValue.StringValue filename
                ? filename.value()
                : "document." + format;
        return ContentBlock.fromDocument(DocumentBlock.builder()
                .name(name)
                .format(DocumentFormat.fromValue(format))
                .source(DocumentSource.builder().bytes(bytes).build())
                .build());
    }

    private static Tool tool(ToolMetadata source) {
        return Tool.fromToolSpec(ToolSpecification.builder()
                .name(source.name())
                .description(source.description())
                .inputSchema(ToolInputSchema.builder()
                        .json(document(source.inputSchema()))
                        .build())
                .build());
    }

    private static ToolChoice toolChoice(ToolMode mode) {
        return switch (mode) {
            case AUTO -> ToolChoice.fromAuto(AutoToolChoice.builder().build());
            case REQUIRED -> ToolChoice.fromAny(AnyToolChoice.builder().build());
            case NONE -> throw new IllegalArgumentException("NONE tool mode omits Bedrock tool configuration.");
        };
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

    private static ArrayList<Content> contents(List<ContentBlock> blocks) {
        ArrayList<Content> content = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block.text() != null) {
                content.add(new TextContent(block.text()));
            } else if (block.toolUse() != null) {
                ToolUseBlock call = block.toolUse();
                content.add(new FunctionCallContent(call.toolUseId(), call.name(), frameworkDocument(call.input())));
            } else if (block.reasoningContent() != null) {
                var reasoning = block.reasoningContent();
                if (reasoning.reasoningText() != null) {
                    content.add(new ReasoningContent(
                            null,
                            reasoning.reasoningText().text(),
                            reasoning.reasoningText().signature(),
                            Map.of()));
                } else if (reasoning.redactedContent() != null) {
                    content.add(new ReasoningContent(
                            null, null, reasoning.redactedContent().asUtf8String(), Map.of()));
                }
            } else if (block.citationsContent() != null) {
                var citations = block.citationsContent();
                citations.content().stream()
                        .map(item -> item.text())
                        .filter(text -> text != null && !text.isEmpty())
                        .map(TextContent::new)
                        .forEach(content::add);
                content.add(new MetadataContent(Map.of(
                        "bedrock.citationCount",
                        StateValue.integer(citations.citations().size()))));
            } else if (block.guardContent() != null) {
                throw new BedrockProviderException(
                        "unsupported_guard_content", null, null, block.type().toString());
            } else {
                throw new BedrockProviderException(
                        "unsupported_response_block", null, null, block.type().toString());
            }
        }
        return content;
    }

    static FinishReason mapFinish(StopReason reason) {
        if (reason == null) {
            return null;
        }
        return switch (reason.toString()) {
            case "end_turn", "stop_sequence" -> FinishReason.STOP;
            case "max_tokens", "model_context_window_exceeded" -> FinishReason.LENGTH;
            case "tool_use" -> FinishReason.TOOL_CALLS;
            case "content_filtered", "guardrail_intervened" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.of(reason.toString());
        };
    }

    static UsageDetails usage(TokenUsage usage) {
        if (usage == null) {
            return null;
        }
        UsageDetails.Builder builder = UsageDetails.builder();
        if (usage.inputTokens() != null) {
            builder.inputTokens(usage.inputTokens());
        }
        if (usage.outputTokens() != null) {
            builder.outputTokens(usage.outputTokens());
        }
        if (usage.totalTokens() != null) {
            builder.totalTokens(usage.totalTokens());
        }
        if (usage.cacheReadInputTokens() != null) {
            builder.value(UsageDetails.CACHE_READ_INPUT_TOKENS, StateValue.integer(usage.cacheReadInputTokens()));
        }
        if (usage.cacheWriteInputTokens() != null) {
            builder.value(UsageDetails.CACHE_CREATION_INPUT_TOKENS, StateValue.integer(usage.cacheWriteInputTokens()));
        }
        UsageDetails result = builder.build();
        return result.values().isEmpty() ? null : result;
    }

    private static Document document(StateValue value) {
        return switch (value) {
            case StateValue.NullValue _ -> Document.fromNull();
            case StateValue.BooleanValue bool -> Document.fromBoolean(bool.value());
            case StateValue.NumberValue number -> Document.fromNumber(number.value());
            case StateValue.StringValue string -> Document.fromString(string.value());
            case StateValue.ArrayValue array ->
                Document.fromList(
                        array.values().stream().map(BedrockMapper::document).toList());
            case StateValue.ObjectValue object -> {
                LinkedHashMap<String, Document> values = new LinkedHashMap<>();
                object.values().forEach((key, item) -> values.put(key, document(item)));
                yield Document.fromMap(values);
            }
        };
    }

    static StateValue frameworkDocument(Document value) {
        if (value == null || value.isNull()) {
            return StateValue.nullValue();
        }
        if (value.isBoolean()) {
            return StateValue.bool(value.asBoolean());
        }
        if (value.isNumber()) {
            return StateValue.number(value.asNumber().bigDecimalValue());
        }
        if (value.isString()) {
            return StateValue.string(value.asString());
        }
        if (value.isList()) {
            return StateValue.array(value.asList().stream()
                    .map(BedrockMapper::frameworkDocument)
                    .toList());
        }
        if (value.isMap()) {
            LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
            value.asMap().forEach((key, item) -> values.put(key, frameworkDocument(item)));
            return StateValue.object(values);
        }
        throw new BedrockProviderException("unsupported_document", null, null, null);
    }

    private static long estimate(StateValue value) {
        return switch (value) {
            case StateValue.NullValue _ -> 4;
            case StateValue.BooleanValue bool -> bool.value() ? 4 : 5;
            case StateValue.NumberValue number -> number.value().toString().length();
            case StateValue.StringValue string -> string.value().getBytes(StandardCharsets.UTF_8).length;
            case StateValue.ArrayValue array ->
                array.values().stream().mapToLong(BedrockMapper::estimate).sum();
            case StateValue.ObjectValue object ->
                object.values().entrySet().stream()
                        .mapToLong(entry -> entry.getKey().length() + estimate(entry.getValue()))
                        .sum();
        };
    }

    private static ValidationException unsupported(Role role, Content content) {
        return new ValidationException(
                "Bedrock does not support content kind '" + content.kind() + "' for role '" + role.value() + "'.");
    }

    private static void put(Map<String, StateValue> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, StateValue.string(value));
        }
    }

    static final class StreamAssembler {
        private final LinkedHashMap<Integer, ToolAccumulator> tools = new LinkedHashMap<>();

        private final Set<Integer> stoppedBlocks = new HashSet<>();

        private final String requestId;

        private final StrictJsonCodec json;

        private TokenUsage usage;

        private FinishReason finish;

        private String rawFinish;

        private long sequence;

        private boolean terminal;

        private boolean messageStarted;

        private boolean messageStopped;

        StreamAssembler(String requestId, StrictJsonCodec json) {
            this.requestId = requestId;
            this.json = json;
        }

        void onMessageStart(MessageStartEvent event) {
            requireBeforeMessageStop();
            if (messageStarted) {
                throw new BedrockProviderException("duplicate_message_start", null, requestId, null);
            }
            messageStarted = true;
            if (event.role() != null && event.role() != ConversationRole.ASSISTANT) {
                throw new BedrockProviderException("unexpected_stream_role", null, requestId, event.roleAsString());
            }
        }

        List<ChatResponseUpdate> onStart(ContentBlockStartEvent event) {
            requireBeforeMessageStop();
            int index = event.contentBlockIndex();
            if (stoppedBlocks.contains(index)) {
                throw new BedrockProviderException("block_start_after_stop", null, requestId, null);
            }
            if (event.start() != null && event.start().toolUse() != null) {
                var start = event.start().toolUse();
                if (tools.putIfAbsent(index, new ToolAccumulator(index, start.toolUseId(), start.name())) != null) {
                    throw new BedrockProviderException("duplicate_tool_start", null, requestId, null);
                }
            } else if (event.start() != null) {
                throw new BedrockProviderException(
                        "unsupported_stream_block_start",
                        null,
                        requestId,
                        event.start().type().toString());
            }
            return List.of();
        }

        List<ChatResponseUpdate> onDelta(ContentBlockDeltaEvent event) {
            requireBeforeMessageStop();
            if (stoppedBlocks.contains(event.contentBlockIndex())) {
                throw new BedrockProviderException("delta_after_block_stop", null, requestId, null);
            }
            var delta = event.delta();
            if (delta == null) {
                throw new BedrockProviderException("missing_content_delta", null, requestId, null);
            }
            ArrayList<Content> content = new ArrayList<>();
            boolean handled = false;
            if (delta.text() != null) {
                handled = true;
                content.add(new TextContent(delta.text()));
            }
            if (delta.reasoningContent() != null) {
                handled = true;
                var reasoning = delta.reasoningContent();
                if (reasoning.text() != null) {
                    content.add(new ReasoningContent(null, reasoning.text(), null, Map.of()));
                }
                if (reasoning.signature() != null) {
                    content.add(new ReasoningContent(null, null, reasoning.signature(), Map.of()));
                }
                if (reasoning.redactedContent() != null) {
                    content.add(new ReasoningContent(
                            null, null, reasoning.redactedContent().asUtf8String(), Map.of()));
                }
            }
            if (delta.toolUse() != null) {
                handled = true;
                ToolAccumulator tool = tools.get(event.contentBlockIndex());
                if (tool == null) {
                    throw new BedrockProviderException("tool_delta_without_start", null, requestId, null);
                }
                if (delta.toolUse().input() != null) {
                    tool.arguments.append(delta.toolUse().input());
                }
            }
            if (!handled) {
                throw new BedrockProviderException(
                        "unsupported_stream_block_delta",
                        null,
                        requestId,
                        delta.type().toString());
            }
            return content.isEmpty() ? List.of() : List.of(update(content, null, null, Map.of()));
        }

        List<ChatResponseUpdate> onStop(ContentBlockStopEvent event) {
            requireBeforeMessageStop();
            if (!stoppedBlocks.add(event.contentBlockIndex())) {
                throw new BedrockProviderException("duplicate_content_block_stop", null, requestId, null);
            }
            ToolAccumulator tool = tools.get(event.contentBlockIndex());
            if (tool == null || tool.emitted) {
                return List.of();
            }
            tool.emitted = true;
            return List.of(update(List.of(tool.build(json)), null, null, Map.of()));
        }

        void onMessageStop(MessageStopEvent event) {
            if (messageStopped) {
                throw new BedrockProviderException("duplicate_message_stop", null, requestId, null);
            }
            messageStopped = true;
            rawFinish = event.stopReasonAsString();
            finish = mapFinish(event.stopReason());
        }

        void onMetadata(ConverseStreamMetadataEvent event) {
            if (!messageStopped) {
                throw new BedrockProviderException("metadata_before_message_stop", null, requestId, null);
            }
            if (usage != null) {
                throw new BedrockProviderException("duplicate_stream_metadata", null, requestId, null);
            }
            usage = event.usage();
        }

        ChatResponseUpdate terminal() {
            if (terminal) {
                throw new BedrockProviderException("duplicate_terminal", null, requestId, null);
            }
            terminal = true;
            if (finish == null) {
                throw new BedrockProviderException("missing_terminal", null, requestId, null);
            }
            if (!messageStopped) {
                throw new BedrockProviderException("missing_message_stop", null, requestId, null);
            }
            if (tools.keySet().stream().anyMatch(index -> !stoppedBlocks.contains(index))) {
                throw new BedrockProviderException("missing_content_block_stop", null, requestId, null);
            }
            LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
            put(metadata, "bedrock.requestId", requestId);
            put(metadata, "bedrock.stopReason", rawFinish);
            return update(List.of(), finish, usage(usage), metadata);
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
            if (finishReason != null) {
                builder.finishReason(finishReason);
            }
            if (usageDetails != null) {
                builder.usage(usageDetails);
            }
            return builder.build();
        }

        private void requireBeforeMessageStop() {
            if (messageStopped) {
                throw new BedrockProviderException("content_after_message_stop", null, requestId, null);
            }
        }
    }

    private static final class ToolAccumulator {
        private final int index;

        private final String id;

        private final String name;

        private final StringBuilder arguments = new StringBuilder();

        private boolean emitted;

        private ToolAccumulator(int index, String id, String name) {
            this.index = index;
            this.id = id;
            this.name = name;
        }

        private FunctionCallContent build(StrictJsonCodec json) {
            StateValue parsed = arguments.isEmpty()
                    ? StateValue.object(Map.of())
                    : json.parse(arguments.toString().getBytes(StandardCharsets.UTF_8));
            return new FunctionCallContent(id, name, parsed);
        }
    }
}
