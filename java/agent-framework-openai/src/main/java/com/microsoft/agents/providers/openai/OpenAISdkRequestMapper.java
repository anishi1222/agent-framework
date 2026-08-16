// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import com.microsoft.agents.core.StateValue;
import com.openai.core.JsonValue;
import com.openai.models.Reasoning;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseFunctionCallOutputItem;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputFile;
import com.openai.models.responses.ResponseInputFileContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputImageContent;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseInputTextContent;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.Tool;
import com.openai.models.responses.ToolChoiceOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuppressWarnings("deprecation")
final class OpenAISdkRequestMapper {
    private OpenAISdkRequestMapper() {}

    static ResponseCreateParams map(OpenAITransport.Request request) {
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(request.model())
                .input(ResponseCreateParams.Input.ofResponse(mapInput(request.input())));
        if (request.instructions() != null) {
            builder.instructions(request.instructions());
        }
        if (request.temperature() != null) {
            builder.temperature(request.temperature());
        }
        if (request.topP() != null) {
            builder.topP(request.topP());
        }
        if (request.maxOutputTokens() != null) {
            builder.maxOutputTokens(request.maxOutputTokens());
        }
        if (!request.tools().isEmpty() || request.responseOptions().imageOutputFormat() != null) {
            builder.tools(mapTools(request.tools(), request.responseOptions().imageOutputFormat()));
        }
        if (request.toolChoice() != null) {
            builder.toolChoice(mapToolChoice(request.toolChoice()));
        }
        if (request.parallelToolCalls() != null) {
            builder.parallelToolCalls(request.parallelToolCalls());
        }
        if (request.user() != null) {
            builder.user(request.user());
        }
        if (request.store() != null) {
            builder.store(request.store());
        }
        if (request.previousResponseId() != null) {
            builder.previousResponseId(request.previousResponseId());
        }
        if (request.conversationId() != null) {
            builder.conversation(request.conversationId());
        }
        if (!request.metadata().isEmpty()) {
            ResponseCreateParams.Metadata.Builder metadata = ResponseCreateParams.Metadata.builder();
            request.metadata().forEach((key, value) -> metadata.putAdditionalProperty(key, JsonValue.from(value)));
            builder.metadata(metadata.build());
        }
        if (request.structuredOutput() != null) {
            ResponseFormatTextJsonSchemaConfig.Schema.Builder schema =
                    ResponseFormatTextJsonSchemaConfig.Schema.builder();
            request.structuredOutput()
                    .schema()
                    .values()
                    .forEach((key, value) ->
                            schema.putAdditionalProperty(key, JsonValue.from(OpenAIStateJson.toJava(value))));
            ResponseFormatTextJsonSchemaConfig.Builder format = ResponseFormatTextJsonSchemaConfig.builder()
                    .name(request.structuredOutput().name())
                    .schema(schema.build())
                    .strict(request.structuredOutput().strict());
            if (request.structuredOutput().description() != null) {
                format.description(request.structuredOutput().description());
            }
            builder.text(ResponseTextConfig.builder().format(format.build()).build());
        }
        mapResponseOptions(request.responseOptions(), builder);
        return builder.build();
    }

    private static List<ResponseInputItem> mapInput(List<OpenAITransport.InputItem> input) {
        ArrayList<ResponseInputItem> result = new ArrayList<>(input.size());
        for (OpenAITransport.InputItem item : input) {
            if (item instanceof OpenAITransport.MessageInput message) {
                List<ResponseInputContent> contents = message.contents().stream()
                        .map(OpenAISdkRequestMapper::mapContent)
                        .toList();
                EasyInputMessage sdkMessage = EasyInputMessage.builder()
                        .role(mapRole(message.role()))
                        .contentOfResponseInputMessageContentList(contents)
                        .build();
                result.add(ResponseInputItem.ofEasyInputMessage(sdkMessage));
            } else if (item instanceof OpenAITransport.FunctionCallInput call) {
                ResponseFunctionToolCall.Builder callBuilder = ResponseFunctionToolCall.builder()
                        .callId(call.callId())
                        .name(call.name())
                        .arguments(OpenAIStateJson.write(call.arguments()));
                if (call.providerItemId() != null) {
                    callBuilder.id(call.providerItemId());
                }
                result.add(ResponseInputItem.ofFunctionCall(callBuilder.build()));
            } else if (item instanceof OpenAITransport.FunctionResultInput functionResult) {
                result.add(ResponseInputItem.ofFunctionCallOutput(mapFunctionResult(functionResult)));
            } else if (item instanceof OpenAITransport.ReasoningInput reasoning) {
                ResponseReasoningItem.Builder reasoningBuilder = ResponseReasoningItem.builder()
                        .id(reasoning.id())
                        .summary(
                                reasoning.text() == null
                                        ? List.of()
                                        : List.of(ResponseReasoningItem.Summary.builder()
                                                .text(reasoning.text())
                                                .build()));
                if (reasoning.protectedData() != null) {
                    reasoningBuilder.encryptedContent(reasoning.protectedData());
                }
                result.add(ResponseInputItem.ofReasoning(reasoningBuilder.build()));
            } else {
                throw new OpenAIUnsupportedContentException("OpenAI SDK mapping does not support input item "
                        + item.getClass().getSimpleName() + ".");
            }
        }
        return List.copyOf(result);
    }

    private static ResponseInputContent mapContent(OpenAITransport.InputContent content) {
        if (content instanceof OpenAITransport.TextInput text) {
            return ResponseInputContent.ofInputText(
                    ResponseInputText.builder().text(text.text()).build());
        }
        if (content instanceof OpenAITransport.ImageInput image) {
            return ResponseInputContent.ofInputImage(ResponseInputImage.builder()
                    .detail(mapImageDetail(image.detail()))
                    .imageUrl(image.uri().toString())
                    .build());
        }
        if (content instanceof OpenAITransport.FileInput file) {
            ResponseInputFile.Builder builder = ResponseInputFile.builder();
            if ("data".equalsIgnoreCase(file.uri().getScheme())) {
                builder.fileData(file.uri().toString());
            } else {
                builder.fileUrl(file.uri().toString());
            }
            if (file.filename() != null) {
                builder.filename(file.filename());
            }
            return ResponseInputContent.ofInputFile(builder.build());
        }
        throw new OpenAIUnsupportedContentException("OpenAI SDK mapping does not support input content "
                + content.getClass().getSimpleName()
                + ".");
    }

    private static ResponseInputItem.FunctionCallOutput mapFunctionResult(OpenAITransport.FunctionResultInput result) {
        ResponseInputItem.FunctionCallOutput.Builder builder =
                ResponseInputItem.FunctionCallOutput.builder().callId(result.callId());
        StateValue payload = result.error() == null
                ? result.result()
                : StateValue.object(Map.of(
                        "error", StateValue.string(result.error()),
                        "result", result.result()));
        String payloadJson = OpenAIStateJson.write(payload);
        if (result.items().isEmpty()) {
            return builder.output(payloadJson).build();
        }
        ArrayList<ResponseFunctionCallOutputItem> items = new ArrayList<>();
        items.add(ResponseFunctionCallOutputItem.ofInputText(
                ResponseInputTextContent.builder().text(payloadJson).build()));
        result.items().forEach(item -> items.add(mapFunctionResultContent(item)));
        return builder.outputOfResponseFunctionCallOutputItemList(items).build();
    }

    private static ResponseFunctionCallOutputItem mapFunctionResultContent(OpenAITransport.InputContent content) {
        if (content instanceof OpenAITransport.TextInput text) {
            return ResponseFunctionCallOutputItem.ofInputText(
                    ResponseInputTextContent.builder().text(text.text()).build());
        }
        if (content instanceof OpenAITransport.ImageInput image) {
            return ResponseFunctionCallOutputItem.ofInputImage(ResponseInputImageContent.builder()
                    .detail(mapFunctionImageDetail(image.detail()))
                    .imageUrl(image.uri().toString())
                    .build());
        }
        if (content instanceof OpenAITransport.FileInput file) {
            ResponseInputFileContent.Builder builder = ResponseInputFileContent.builder();
            if ("data".equalsIgnoreCase(file.uri().getScheme())) {
                builder.fileData(file.uri().toString());
            } else {
                builder.fileUrl(file.uri().toString());
            }
            if (file.filename() != null) {
                builder.filename(file.filename());
            }
            return ResponseFunctionCallOutputItem.ofInputFile(builder.build());
        }
        throw new OpenAIUnsupportedContentException("OpenAI SDK mapping does not support function-result content "
                + content.getClass().getSimpleName()
                + ".");
    }

    private static EasyInputMessage.Role mapRole(OpenAITransport.InputRole role) {
        return switch (role) {
            case SYSTEM -> EasyInputMessage.Role.SYSTEM;
            case DEVELOPER -> EasyInputMessage.Role.DEVELOPER;
            case USER -> EasyInputMessage.Role.USER;
            case ASSISTANT -> EasyInputMessage.Role.ASSISTANT;
        };
    }

    private static ResponseInputImage.Detail mapImageDetail(OpenAITransport.ImageDetail detail) {
        return switch (detail) {
            case AUTO -> ResponseInputImage.Detail.AUTO;
            case LOW -> ResponseInputImage.Detail.LOW;
            case HIGH -> ResponseInputImage.Detail.HIGH;
            case ORIGINAL -> ResponseInputImage.Detail.ORIGINAL;
        };
    }

    private static ResponseInputImageContent.Detail mapFunctionImageDetail(OpenAITransport.ImageDetail detail) {
        return switch (detail) {
            case AUTO -> ResponseInputImageContent.Detail.AUTO;
            case LOW -> ResponseInputImageContent.Detail.LOW;
            case HIGH -> ResponseInputImageContent.Detail.HIGH;
            case ORIGINAL -> ResponseInputImageContent.Detail.ORIGINAL;
        };
    }

    private static List<Tool> mapTools(
            List<OpenAITransport.FunctionTool> tools, OpenAIImageOutputFormat imageOutputFormat) {
        ArrayList<Tool> result = new ArrayList<>(tools.size() + (imageOutputFormat == null ? 0 : 1));
        for (OpenAITransport.FunctionTool tool : tools) {
            FunctionTool.Parameters.Builder parameters = FunctionTool.Parameters.builder();
            tool.inputSchema()
                    .values()
                    .forEach((key, value) ->
                            parameters.putAdditionalProperty(key, JsonValue.from(OpenAIStateJson.toJava(value))));
            result.add(Tool.ofFunction(FunctionTool.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .parameters(parameters.build())
                    .strict(false)
                    .build()));
        }
        if (imageOutputFormat != null) {
            result.add(Tool.ofImageGeneration(Tool.ImageGeneration.builder()
                    .outputFormat(mapImageOutputFormat(imageOutputFormat))
                    .build()));
        }
        return List.copyOf(result);
    }

    private static Tool.ImageGeneration.OutputFormat mapImageOutputFormat(OpenAIImageOutputFormat format) {
        return switch (format) {
            case PNG -> Tool.ImageGeneration.OutputFormat.PNG;
            case JPEG -> Tool.ImageGeneration.OutputFormat.JPEG;
            case WEBP -> Tool.ImageGeneration.OutputFormat.WEBP;
        };
    }

    private static ToolChoiceOptions mapToolChoice(OpenAITransport.ToolSelection toolChoice) {
        return switch (toolChoice) {
            case AUTO -> ToolChoiceOptions.AUTO;
            case REQUIRED -> ToolChoiceOptions.REQUIRED;
            case NONE -> ToolChoiceOptions.NONE;
        };
    }

    private static void mapResponseOptions(OpenAIResponseOptions options, ResponseCreateParams.Builder builder) {
        if (options.reasoningEffort() != null || options.reasoningSummary() != null) {
            Reasoning.Builder reasoning = Reasoning.builder();
            if (options.reasoningEffort() != null) {
                reasoning.effort(
                        switch (options.reasoningEffort()) {
                            case NONE -> com.openai.models.ReasoningEffort.NONE;
                            case MINIMAL -> com.openai.models.ReasoningEffort.MINIMAL;
                            case LOW -> com.openai.models.ReasoningEffort.LOW;
                            case MEDIUM -> com.openai.models.ReasoningEffort.MEDIUM;
                            case HIGH -> com.openai.models.ReasoningEffort.HIGH;
                            case XHIGH -> com.openai.models.ReasoningEffort.XHIGH;
                            case MAX -> com.openai.models.ReasoningEffort.MAX;
                        });
            }
            if (options.reasoningSummary() != null) {
                reasoning.summary(
                        switch (options.reasoningSummary()) {
                            case AUTO -> Reasoning.Summary.AUTO;
                            case CONCISE -> Reasoning.Summary.CONCISE;
                            case DETAILED -> Reasoning.Summary.DETAILED;
                        });
            }
            builder.reasoning(reasoning.build());
        }
        if (options.serviceTier() != null) {
            builder.serviceTier(
                    switch (options.serviceTier()) {
                        case AUTO -> ResponseCreateParams.ServiceTier.AUTO;
                        case DEFAULT -> ResponseCreateParams.ServiceTier.DEFAULT;
                        case FLEX -> ResponseCreateParams.ServiceTier.FLEX;
                        case SCALE -> ResponseCreateParams.ServiceTier.SCALE;
                        case PRIORITY -> ResponseCreateParams.ServiceTier.PRIORITY;
                        case FAST -> ResponseCreateParams.ServiceTier.FAST;
                    });
        }
        if (options.truncation() != null) {
            builder.truncation(
                    switch (options.truncation()) {
                        case AUTO -> ResponseCreateParams.Truncation.AUTO;
                        case DISABLED -> ResponseCreateParams.Truncation.DISABLED;
                    });
        }
        if (options.background() != null) {
            builder.background(options.background());
        }
        if (options.includeEncryptedReasoning()) {
            builder.include(List.of(ResponseIncludable.REASONING_ENCRYPTED_CONTENT));
        }
    }
}
