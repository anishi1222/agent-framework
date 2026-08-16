// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.MetadataContent;
import com.microsoft.agents.core.ReasoningContent;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.UriContent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts between AG-UI messages and provider-neutral Agent Framework messages. */
public final class AGUIMessageConverter {
    private static final String ENCRYPTED_VALUE = "agui.encryptedValue";

    private static final String INPUT_TYPE = "agui.inputType";

    private final AGUIJsonCodec codec;

    /**
     * Creates a converter with strict JSON argument parsing.
     *
     * @param codec AG-UI codec
     */
    public AGUIMessageConverter(AGUIJsonCodec codec) {
        this.codec = java.util.Objects.requireNonNull(codec, "codec");
    }

    /**
     * Converts ordered AG-UI messages and omits frontend-only activity messages.
     *
     * @param messages AG-UI messages
     * @return provider-neutral messages
     */
    public List<Message> toCoreMessages(List<? extends AGUIMessage> messages) {
        ArrayList<Message> result = new ArrayList<>();
        for (AGUIMessage message : AGUIValidation.list(messages, "messages")) {
            if (!(message instanceof AGUIMessages.Activity)) {
                result.add(toCoreMessage(message));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Converts one non-activity AG-UI message.
     *
     * @param message AG-UI message
     * @return provider-neutral message
     */
    public Message toCoreMessage(AGUIMessage message) {
        java.util.Objects.requireNonNull(message, "message");
        if (message instanceof AGUIMessages.Activity) {
            throw new AGUIProtocolException(
                    AGUIErrorCode.INVALID_MODEL,
                    "Activity messages are frontend-only and must not be forwarded to an agent.");
        }
        List<Content> contents =
                switch (message) {
                    case AGUIMessages.Developer developer -> List.of(new TextContent(developer.content()));
                    case AGUIMessages.System system -> List.of(new TextContent(system.content()));
                    case AGUIMessages.Assistant assistant -> assistantContents(assistant);
                    case AGUIMessages.User user -> userContents(user.content());
                    case AGUIMessages.Tool tool ->
                        List.of(new FunctionResultContent(
                                tool.toolCallId(),
                                StateValue.string(tool.content()),
                                List.of(),
                                tool.error(),
                                Map.of()));
                    case AGUIMessages.Reasoning reasoning ->
                        List.of(new ReasoningContent(
                                reasoning.id(), reasoning.content(), reasoning.encryptedValue(), Map.of()));
                    case AGUIMessages.Activity _ ->
                        throw new AssertionError("Activity was rejected before conversion.");
                };
        return new Message(
                Role.of(message.role().value()),
                contents,
                authorName(message),
                message.id(),
                encryptedMetadata(message));
    }

    /**
     * Converts provider-neutral messages using deterministic fallback identifiers.
     *
     * @param messages provider-neutral messages
     * @return AG-UI messages
     */
    public List<AGUIMessage> toAGUIMessages(List<Message> messages) {
        List<Message> checked = AGUIValidation.list(messages, "messages");
        ArrayList<AGUIMessage> result = new ArrayList<>(checked.size());
        for (int index = 0; index < checked.size(); index++) {
            result.add(toAGUIMessage(checked.get(index), "message-" + index));
        }
        return List.copyOf(result);
    }

    /**
     * Converts one provider-neutral message.
     *
     * @param message message
     * @param fallbackId deterministic identifier used when the source has none
     * @return AG-UI message
     */
    public AGUIMessage toAGUIMessage(Message message, String fallbackId) {
        java.util.Objects.requireNonNull(message, "message");
        String id =
                message.messageId() == null ? AGUIValidation.nonBlank(fallbackId, "fallbackId") : message.messageId();
        String encrypted = stateString(message.metadata().get(ENCRYPTED_VALUE));
        return switch (message.role().value()) {
            case "developer" -> new AGUIMessages.Developer(id, message.text(), message.authorName(), encrypted);
            case "system" -> new AGUIMessages.System(id, message.text(), message.authorName(), encrypted);
            case "assistant" -> assistantMessage(message, id, encrypted);
            case "user" ->
                new AGUIMessages.User(id, toUserContent(message.contents()), message.authorName(), encrypted);
            case "tool" -> toolMessage(message, id, encrypted);
            case "reasoning" -> reasoningMessage(message, id);
            default ->
                throw new AGUIProtocolException(
                        AGUIErrorCode.INVALID_MODEL, "Core message role is not representable in AG-UI.");
        };
    }

    private List<Content> assistantContents(AGUIMessages.Assistant assistant) {
        ArrayList<Content> result = new ArrayList<>();
        if (assistant.content() != null) {
            result.add(new TextContent(assistant.content()));
        }
        for (AGUIMessages.ToolCall call : assistant.toolCalls()) {
            StateValue arguments = call.function().arguments().isBlank()
                    ? StateValue.nullValue()
                    : codec.decodeValue(call.function().arguments().getBytes(StandardCharsets.UTF_8));
            Map<String, StateValue> metadata = call.encryptedValue() == null
                    ? Map.of()
                    : Map.of(ENCRYPTED_VALUE, StateValue.string(call.encryptedValue()));
            result.add(new FunctionCallContent(call.id(), call.function().name(), arguments, false, metadata));
        }
        return List.copyOf(result);
    }

    private List<Content> userContents(AGUIUserContent content) {
        return switch (content) {
            case AGUIMessages.TextUserContent text -> List.of(new TextContent(text.text()));
            case AGUIMessages.PartsUserContent parts ->
                parts.parts().stream().map(this::toCoreContent).toList();
        };
    }

    @SuppressWarnings("removal")
    private Content toCoreContent(AGUIInputContent input) {
        return switch (input) {
            case AGUIMessages.TextInput text -> new TextContent(text.text());
            case AGUIMessages.MediaInput media -> mediaContent(media);
            case AGUIMessages.LegacyBinaryInput binary -> legacyBinaryContent(binary);
        };
    }

    private Content mediaContent(AGUIMessages.MediaInput media) {
        Map<String, StateValue> metadata = inputMetadata(media.type(), media.metadata());
        return switch (media.source()) {
            case AGUIMessages.DataSource data -> new DataContent(decodeBase64(data.value()), data.mimeType(), metadata);
            case AGUIMessages.UrlSource url -> new UriContent(URI.create(url.value()), url.mimeType(), metadata);
        };
    }

    @SuppressWarnings("removal")
    private Content legacyBinaryContent(AGUIMessages.LegacyBinaryInput binary) {
        Map<String, StateValue> metadata = new LinkedHashMap<>();
        metadata.put(INPUT_TYPE, StateValue.string("binary"));
        put(metadata, "agui.binaryId", binary.id());
        put(metadata, "agui.filename", binary.filename());
        if (binary.data() != null) {
            return new DataContent(decodeBase64(binary.data()), binary.mimeType(), metadata);
        }
        if (binary.url() != null) {
            return new UriContent(URI.create(binary.url()), binary.mimeType(), metadata);
        }
        return new MetadataContent(metadata);
    }

    private byte[] decodeBase64(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length > codec.limits().maxRequestBytes()) {
                throw new AGUIProtocolException(
                        AGUIErrorCode.LIMIT_EXCEEDED, "Decoded AG-UI data exceeds maxRequestBytes.");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new AGUIProtocolException(
                    AGUIErrorCode.INVALID_MODEL, "AG-UI data source is not valid base64.", exception);
        }
    }

    private static Map<String, StateValue> inputMetadata(String type, StateValue metadata) {
        LinkedHashMap<String, StateValue> result = new LinkedHashMap<>();
        result.put(INPUT_TYPE, StateValue.string(type));
        if (metadata instanceof StateValue.ObjectValue object) {
            result.putAll(object.values());
        } else if (metadata != null) {
            result.put("agui.metadata", metadata);
        }
        return Map.copyOf(result);
    }

    private AGUIMessage assistantMessage(Message message, String id, String encrypted) {
        String text = message.contents().stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .reduce("", String::concat);
        List<AGUIMessages.ToolCall> calls = message.contents().stream()
                .filter(FunctionCallContent.class::isInstance)
                .map(FunctionCallContent.class::cast)
                .map(call -> new AGUIMessages.ToolCall(
                        call.callId(),
                        new AGUIMessages.FunctionCall(
                                call.name(), new String(codec.encodeValue(call.arguments()), StandardCharsets.UTF_8)),
                        stateString(call.metadata().get(ENCRYPTED_VALUE))))
                .toList();
        return new AGUIMessages.Assistant(id, text.isEmpty() ? null : text, message.authorName(), encrypted, calls);
    }

    private AGUIMessage toolMessage(Message message, String id, String encrypted) {
        FunctionResultContent result = message.contents().stream()
                .filter(FunctionResultContent.class::isInstance)
                .map(FunctionResultContent.class::cast)
                .findFirst()
                .orElseThrow(() -> new AGUIProtocolException(
                        AGUIErrorCode.INVALID_MODEL, "Tool message requires FunctionResultContent."));
        String content = result.result() instanceof StateValue.StringValue string
                ? string.value()
                : new String(codec.encodeValue(result.result()), StandardCharsets.UTF_8);
        return new AGUIMessages.Tool(id, content, result.callId(), result.error(), encrypted);
    }

    private static AGUIMessage reasoningMessage(Message message, String id) {
        ReasoningContent reasoning = message.contents().stream()
                .filter(ReasoningContent.class::isInstance)
                .map(ReasoningContent.class::cast)
                .findFirst()
                .orElseThrow(() -> new AGUIProtocolException(
                        AGUIErrorCode.INVALID_MODEL, "Reasoning message requires ReasoningContent."));
        return new AGUIMessages.Reasoning(
                id, reasoning.text() == null ? "" : reasoning.text(), reasoning.protectedData());
    }

    private AGUIUserContent toUserContent(List<Content> contents) {
        boolean onlyText = contents.stream().allMatch(TextContent.class::isInstance);
        if (onlyText) {
            String text = contents.stream()
                    .map(TextContent.class::cast)
                    .map(TextContent::text)
                    .reduce("", String::concat);
            return new AGUIMessages.TextUserContent(text);
        }
        return new AGUIMessages.PartsUserContent(
                contents.stream().map(this::toInputContent).toList());
    }

    private AGUIInputContent toInputContent(Content content) {
        return switch (content) {
            case TextContent text -> new AGUIMessages.TextInput(text.text());
            case DataContent data ->
                new AGUIMessages.MediaInput(
                        mediaKind(data.metadata()),
                        new AGUIMessages.DataSource(Base64.getEncoder().encodeToString(data.data()), data.mediaType()),
                        StateValue.object(data.metadata()));
            case UriContent uri ->
                new AGUIMessages.MediaInput(
                        mediaKind(uri.metadata()),
                        new AGUIMessages.UrlSource(uri.uri().toString(), uri.mediaType()),
                        StateValue.object(uri.metadata()));
            default ->
                throw new AGUIProtocolException(
                        AGUIErrorCode.INVALID_MODEL, "Core user content is not representable as AG-UI input.");
        };
    }

    private static AGUIMediaKind mediaKind(Map<String, StateValue> metadata) {
        String type = stateString(metadata.get(INPUT_TYPE));
        return type == null ? AGUIMediaKind.DOCUMENT : AGUIMediaKind.fromValue(type);
    }

    private static String authorName(AGUIMessage message) {
        return switch (message) {
            case AGUIMessages.Developer developer -> developer.name();
            case AGUIMessages.System system -> system.name();
            case AGUIMessages.Assistant assistant -> assistant.name();
            case AGUIMessages.User user -> user.name();
            case AGUIMessages.Tool _, AGUIMessages.Activity _, AGUIMessages.Reasoning _ -> null;
        };
    }

    private static Map<String, StateValue> encryptedMetadata(AGUIMessage message) {
        String encrypted =
                switch (message) {
                    case AGUIMessages.Developer developer -> developer.encryptedValue();
                    case AGUIMessages.System system -> system.encryptedValue();
                    case AGUIMessages.Assistant assistant -> assistant.encryptedValue();
                    case AGUIMessages.User user -> user.encryptedValue();
                    case AGUIMessages.Tool tool -> tool.encryptedValue();
                    case AGUIMessages.Reasoning reasoning -> reasoning.encryptedValue();
                    case AGUIMessages.Activity _ -> null;
                };
        return encrypted == null ? Map.of() : Map.of(ENCRYPTED_VALUE, StateValue.string(encrypted));
    }

    private static void put(Map<String, StateValue> values, String key, String value) {
        if (value != null) {
            values.put(key, StateValue.string(value));
        }
    }

    private static String stateString(StateValue value) {
        return value instanceof StateValue.StringValue string ? string.value() : null;
    }
}
