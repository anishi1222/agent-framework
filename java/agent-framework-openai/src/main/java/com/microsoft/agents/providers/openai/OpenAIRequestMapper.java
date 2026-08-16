// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.ReasoningContent;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.ToolChoice;
import com.microsoft.agents.core.UriContent;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import com.microsoft.agents.tools.ToolMode;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class OpenAIRequestMapper {
    private static final Role DEVELOPER = Role.of("developer");

    private OpenAIRequestMapper() {}

    static OpenAITransport.Request map(
            ChatClientRequest request, OpenAIChatClientOptions defaults, OpenAIResponseOptions responseOptions) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(defaults, "defaults");
        ChatOptions options = request.options();
        rejectUnsupportedOptions(options);
        String model = options.model() == null ? defaults.model() : options.model();
        List<OpenAITransport.InputItem> input = mapMessages(request.messages());
        List<OpenAITransport.FunctionTool> tools = mapTools(request.tools());
        OpenAITransport.ToolSelection toolSelection = mapToolSelection(request.toolMode(), options.toolChoice(), tools);
        String previousResponseId = null;
        String conversationId = null;
        if (options.conversationId() != null) {
            if (options.conversationId().startsWith("conv_")) {
                conversationId = options.conversationId();
            } else {
                previousResponseId = options.conversationId();
            }
        }
        return new OpenAITransport.Request(
                model,
                input,
                options.instructions(),
                options.temperature(),
                options.topP(),
                options.maxTokens() == null ? null : options.maxTokens().longValue(),
                tools,
                tools.isEmpty() ? null : toolSelection,
                tools.isEmpty() ? null : options.allowMultipleToolCalls(),
                options.user(),
                options.store(),
                previousResponseId,
                conversationId,
                mapMetadata(options.metadata()),
                options.structuredOutput(),
                responseOptions);
    }

    private static void rejectUnsupportedOptions(ChatOptions options) {
        ArrayList<String> unsupported = new ArrayList<>();
        if (!options.stop().isEmpty()) {
            unsupported.add("stop");
        }
        if (options.seed() != null) {
            unsupported.add("seed");
        }
        if (options.frequencyPenalty() != null) {
            unsupported.add("frequencyPenalty");
        }
        if (options.presencePenalty() != null) {
            unsupported.add("presencePenalty");
        }
        if (!unsupported.isEmpty()) {
            throw new ValidationException(
                    "OpenAI Responses does not support ChatOptions " + String.join(", ", unsupported) + ".");
        }
    }

    private static List<OpenAITransport.InputItem> mapMessages(List<Message> messages) {
        if (messages.isEmpty()) {
            throw new ValidationException("OpenAI requests require at least one message.");
        }
        ArrayList<OpenAITransport.InputItem> input = new ArrayList<>();
        for (Message message : messages) {
            mapMessage(message, input);
        }
        if (input.isEmpty()) {
            throw new ValidationException("OpenAI requests require at least one representable input item.");
        }
        return List.copyOf(input);
    }

    private static void mapMessage(Message message, List<OpenAITransport.InputItem> target) {
        Role role = message.role();
        if (role.equals(Role.TOOL)) {
            for (Content content : message.contents()) {
                if (!(content instanceof FunctionResultContent result)) {
                    throw unsupported(role, content);
                }
                target.add(mapFunctionResult(result));
            }
            return;
        }
        OpenAITransport.InputRole inputRole = mapRole(role);
        ArrayList<OpenAITransport.InputContent> messageContents = new ArrayList<>();
        for (Content content : message.contents()) {
            if (content instanceof FunctionCallContent call) {
                requireRole(role, Role.ASSISTANT, content);
                flushMessage(inputRole, messageContents, target);
                target.add(new OpenAITransport.FunctionCallInput(
                        call.callId(), call.name(), call.arguments(), providerItemId(call.metadata())));
            } else if (content instanceof ReasoningContent reasoning) {
                requireRole(role, Role.ASSISTANT, content);
                flushMessage(inputRole, messageContents, target);
                if (reasoning.id() == null) {
                    throw new OpenAIUnsupportedContentException(
                            "OpenAI reasoning history requires a stable reasoning id.");
                }
                target.add(new OpenAITransport.ReasoningInput(
                        reasoning.id(), reasoning.text(), reasoning.protectedData()));
            } else if (content instanceof FunctionResultContent) {
                throw unsupported(role, content);
            } else {
                messageContents.add(mapInputContent(role, content));
            }
        }
        flushMessage(inputRole, messageContents, target);
    }

    private static void flushMessage(
            OpenAITransport.InputRole role,
            List<OpenAITransport.InputContent> contents,
            List<OpenAITransport.InputItem> target) {
        if (!contents.isEmpty()) {
            target.add(new OpenAITransport.MessageInput(role, List.copyOf(contents)));
            contents.clear();
        }
    }

    private static OpenAITransport.InputContent mapInputContent(Role role, Content content) {
        if (content instanceof TextContent text) {
            return new OpenAITransport.TextInput(text.text());
        }
        if (content instanceof DataContent data) {
            requireRichInputRole(role, content);
            return mapUri(data.dataUri(), data.mediaType(), data.metadata());
        }
        if (content instanceof UriContent uri) {
            requireRichInputRole(role, content);
            if (uri.mediaType() == null) {
                throw new OpenAIUnsupportedContentException("OpenAI URI content requires an explicit media type.");
            }
            return mapUri(uri.uri(), uri.mediaType(), uri.metadata());
        }
        throw unsupported(role, content);
    }

    private static OpenAITransport.InputContent mapUri(URI uri, String mediaType, Map<String, StateValue> metadata) {
        String normalized = mediaType.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("image/")) {
            return new OpenAITransport.ImageInput(uri, mediaType, imageDetail(metadata.get("detail")));
        }
        if (normalized.startsWith("application/") || normalized.startsWith("text/")) {
            return new OpenAITransport.FileInput(uri, mediaType, metadataString(metadata, "filename"));
        }
        throw new OpenAIUnsupportedContentException(
                "OpenAI Responses does not support media type '" + mediaType + "'.");
    }

    private static OpenAITransport.FunctionResultInput mapFunctionResult(FunctionResultContent result) {
        ArrayList<OpenAITransport.InputContent> items = new ArrayList<>();
        for (Content item : result.items()) {
            items.add(mapInputContent(Role.USER, item));
        }
        return new OpenAITransport.FunctionResultInput(result.callId(), result.result(), items, result.error());
    }

    private static List<OpenAITransport.FunctionTool> mapTools(List<ToolMetadata> tools) {
        ArrayList<OpenAITransport.FunctionTool> result = new ArrayList<>(tools.size());
        for (ToolMetadata tool : tools) {
            if (!tool.capabilities().contains(ToolCapability.FUNCTION)
                    || tool.capabilities().size() != 1) {
                throw new ValidationException(
                        "OpenAIChatClient currently supports only FUNCTION tool declarations; tool '"
                                + tool.name()
                                + "' declares "
                                + tool.capabilities()
                                + ".");
            }
            result.add(new OpenAITransport.FunctionTool(tool.name(), tool.description(), tool.inputSchema()));
        }
        return List.copyOf(result);
    }

    private static OpenAITransport.ToolSelection mapToolSelection(
            ToolMode requestMode, ToolChoice optionChoice, List<OpenAITransport.FunctionTool> tools) {
        if (tools.isEmpty()) {
            if (requestMode == ToolMode.REQUIRED || optionChoice == ToolChoice.REQUIRED) {
                throw new ValidationException("OpenAI tool choice REQUIRED requires at least one tool.");
            }
            return OpenAITransport.ToolSelection.NONE;
        }
        ToolMode mode = requestMode;
        if (mode == ToolMode.NONE && optionChoice != null) {
            mode = switch (optionChoice) {
                case AUTO -> ToolMode.AUTO;
                case REQUIRED -> ToolMode.REQUIRED;
                case NONE -> ToolMode.NONE;
            };
        }
        return switch (mode) {
            case AUTO -> OpenAITransport.ToolSelection.AUTO;
            case REQUIRED -> OpenAITransport.ToolSelection.REQUIRED;
            case NONE -> OpenAITransport.ToolSelection.NONE;
        };
    }

    private static Map<String, String> mapMetadata(Map<String, StateValue> metadata) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (!(value instanceof StateValue.StringValue string)) {
                throw new ValidationException("OpenAI request metadata value '" + key + "' must be a string.");
            }
            result.put(key, string.value());
        });
        return Map.copyOf(result);
    }

    private static OpenAITransport.InputRole mapRole(Role role) {
        if (role.equals(Role.SYSTEM)) {
            return OpenAITransport.InputRole.SYSTEM;
        }
        if (role.equals(DEVELOPER)) {
            return OpenAITransport.InputRole.DEVELOPER;
        }
        if (role.equals(Role.USER)) {
            return OpenAITransport.InputRole.USER;
        }
        if (role.equals(Role.ASSISTANT)) {
            return OpenAITransport.InputRole.ASSISTANT;
        }
        throw new OpenAIUnsupportedContentException(
                "OpenAI Responses does not support message role '" + role.value() + "'.");
    }

    private static void requireRole(Role actual, Role expected, Content content) {
        if (!actual.equals(expected)) {
            throw unsupported(actual, content);
        }
    }

    private static void requireRichInputRole(Role role, Content content) {
        if (!role.equals(Role.USER)) {
            throw unsupported(role, content);
        }
    }

    private static OpenAIUnsupportedContentException unsupported(Role role, Content content) {
        return new OpenAIUnsupportedContentException("OpenAI Responses does not support content kind '"
                + content.kind()
                + "' for role '"
                + role.value()
                + "'.");
    }

    private static String providerItemId(Map<String, StateValue> metadata) {
        String itemId = metadataString(metadata, "openai.itemId");
        return itemId == null ? metadataString(metadata, "fc_id") : itemId;
    }

    private static String metadataString(Map<String, StateValue> metadata, String key) {
        StateValue value = metadata.get(key);
        return value instanceof StateValue.StringValue string ? string.value() : null;
    }

    private static OpenAITransport.ImageDetail imageDetail(StateValue value) {
        if (value == null) {
            return OpenAITransport.ImageDetail.AUTO;
        }
        if (!(value instanceof StateValue.StringValue string)) {
            throw new ValidationException("OpenAI image detail metadata must be a string.");
        }
        try {
            return OpenAITransport.ImageDetail.valueOf(string.value().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("Unsupported OpenAI image detail '" + string.value() + "'.");
        }
    }
}
