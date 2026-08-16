// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core.internal;

import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.ContentStateCodec;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.SerializationError;
import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes and decodes framework messages as explicit JSON-shaped state values.
 *
 * <p>This internal cross-module codec has no reflective or polymorphic fallback. Content values use
 * the stable {@link ContentStateCodec} discriminators, and malformed message shapes fail closed.
 */
public final class MessageStateCodec {
    private final ContentStateCodec contentCodec = new ContentStateCodec();

    /**
     * Encodes one immutable message.
     *
     * @param message message to encode
     * @return deterministic JSON-shaped message value
     */
    public StateValue.ObjectValue encode(Message message) {
        if (message == null) {
            throw new NullPointerException("message");
        }
        LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
        fields.put("role", StateValue.string(message.role().value()));
        fields.put(
                "contents",
                StateValue.array(
                        message.contents().stream().map(contentCodec::encode).toList()));
        if (message.authorName() != null) {
            fields.put("authorName", StateValue.string(message.authorName()));
        }
        if (message.messageId() != null) {
            fields.put("messageId", StateValue.string(message.messageId()));
        }
        if (!message.metadata().isEmpty()) {
            fields.put("metadata", StateValue.object(message.metadata()));
        }
        return StateValue.object(fields);
    }

    /**
     * Decodes one detached immutable message.
     *
     * @param value encoded message value
     * @return detached message
     * @throws SerializationException when the message shape or content discriminator is invalid
     */
    public Message decode(StateValue value) {
        StateValue.ObjectValue message = requireObject(value, "message");
        try {
            StateValue contentsValue = message.require("contents");
            if (!(contentsValue instanceof StateValue.ArrayValue contents)) {
                throw malformed("message contents must be an array.");
            }
            ArrayList<Content> decodedContents =
                    new ArrayList<>(contents.values().size());
            contents.values()
                    .forEach(content -> decodedContents.add(contentCodec.decode(content, ContentStateCodec.VERSION)));
            return new Message(
                    Role.of(requireString(message, "role")),
                    List.copyOf(decodedContents),
                    optionalString(message, "authorName"),
                    optionalString(message, "messageId"),
                    optionalObject(message, "metadata"));
        } catch (SerializationException exception) {
            throw exception;
        } catch (ValidationException | IllegalArgumentException exception) {
            throw malformed("Invalid message payload.", exception);
        }
    }

    private static StateValue.ObjectValue requireObject(StateValue value, String name) {
        if (value instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw malformed(name + " must be an object.");
    }

    private static String requireString(StateValue.ObjectValue object, String name) {
        StateValue value = object.require(name);
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw malformed(name + " must be a string.");
    }

    private static String optionalString(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null || value == StateValue.NullValue.INSTANCE) {
            return null;
        }
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw malformed(name + " must be a string when present.");
    }

    private static Map<String, StateValue> optionalObject(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        return value == null ? Map.of() : requireObject(value, name).values();
    }

    private static SerializationException malformed(String message) {
        return new SerializationException(SerializationError.MALFORMED_DOCUMENT, message);
    }

    private static SerializationException malformed(String message, Throwable cause) {
        return new SerializationException(SerializationError.MALFORMED_DOCUMENT, message, cause);
    }
}
