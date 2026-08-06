// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.ContentStateCodec;
import com.microsoft.agents.core.DocumentKind;
import com.microsoft.agents.core.JsonStateSerializer;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.SerializationError;
import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.StateEnvelope;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes and decodes the Java version-1 agent-session document.
 *
 * <p>The codec uses {@link JsonStateSerializer} for deterministic UTF-8 JSON, parser limits, duplicate
 * key rejection, explicit document-kind/version checks, and recursively ordered object keys. Unknown
 * additive properties are ignored within version 1. Required properties and stable content
 * discriminators remain validated.
 */
public final class AgentSessionCodec {
    /** Initial Java agent-session payload version. */
    public static final int PAYLOAD_VERSION = 1;

    private final JsonStateSerializer serializer;

    private final ContentStateCodec contentCodec = new ContentStateCodec();

    /**
     * Creates a session codec using the configured safe core serializer.
     *
     * @param serializer core JSON state serializer with explicit limits
     */
    public AgentSessionCodec(JsonStateSerializer serializer) {
        this.serializer = AgentValidation.requireNonNull(serializer, "serializer");
    }

    /**
     * Encodes one detached session snapshot.
     *
     * @param snapshot session snapshot
     * @return deterministic compact UTF-8 JSON
     */
    public byte[] encode(AgentSessionSnapshot snapshot) {
        AgentValidation.requireNonNull(snapshot, "snapshot");
        LinkedHashMap<String, StateValue> payload = new LinkedHashMap<>();
        payload.put("sessionId", StateValue.string(snapshot.sessionId()));
        payload.put(
                "messages",
                StateValue.array(
                        snapshot.messages().stream().map(this::encodeMessage).toList()));
        payload.put("state", StateValue.object(snapshot.state().values()));
        if (snapshot.pendingRun() != null) {
            payload.put("pendingRun", snapshot.pendingRun());
        }
        return serializer.write(
                StateEnvelope.of(DocumentKind.AGENT_SESSION, PAYLOAD_VERSION, StateValue.object(payload)));
    }

    /**
     * Decodes one version-1 agent-session snapshot.
     *
     * @param utf8Json encoded document
     * @return detached session snapshot
     * @throws SerializationException when parser limits, envelope rules, or payload schema are
     *     violated
     */
    public AgentSessionSnapshot decode(byte[] utf8Json) {
        StateEnvelope envelope = serializer.read(utf8Json, DocumentKind.AGENT_SESSION);
        if (envelope.payloadVersion() != PAYLOAD_VERSION) {
            throw new SerializationException(
                    SerializationError.UNSUPPORTED_PAYLOAD_VERSION,
                    "Unsupported agent-session payload version " + envelope.payloadVersion() + ".");
        }
        StateValue.ObjectValue payload = requireObject(envelope.payload(), "payload");
        try {
            String sessionId = requireString(payload, "sessionId");
            List<Message> messages = decodeMessages(payload.values().get("messages"));
            AgentSessionStateBag state = new AgentSessionStateBag(
                    requireObject(payload.require("state"), "state").values());
            StateValue pending = payload.values().get("pendingRun");
            StateValue.ObjectValue pendingRun = pending == null || pending == StateValue.NullValue.INSTANCE
                    ? null
                    : requireObject(pending, "pendingRun");
            return new AgentSessionSnapshot(sessionId, messages, state, pendingRun);
        } catch (SerializationException exception) {
            throw exception;
        } catch (ValidationException | IllegalArgumentException exception) {
            throw malformed("Invalid agent-session payload.", exception);
        }
    }

    private StateValue encodeMessage(Message message) {
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

    private List<Message> decodeMessages(StateValue value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof StateValue.ArrayValue array)) {
            throw malformed("messages must be an array.");
        }
        ArrayList<Message> messages = new ArrayList<>(array.values().size());
        for (StateValue item : array.values()) {
            StateValue.ObjectValue message = requireObject(item, "message");
            Role role = Role.of(requireString(message, "role"));
            StateValue contentsValue = message.require("contents");
            if (!(contentsValue instanceof StateValue.ArrayValue contents)) {
                throw malformed("message contents must be an array.");
            }
            ArrayList<Content> decodedContents =
                    new ArrayList<>(contents.values().size());
            contents.values()
                    .forEach(content -> decodedContents.add(contentCodec.decode(content, ContentStateCodec.VERSION)));
            messages.add(new Message(
                    role,
                    List.copyOf(decodedContents),
                    optionalString(message, "authorName"),
                    optionalString(message, "messageId"),
                    optionalObject(message, "metadata")));
        }
        return List.copyOf(messages);
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
