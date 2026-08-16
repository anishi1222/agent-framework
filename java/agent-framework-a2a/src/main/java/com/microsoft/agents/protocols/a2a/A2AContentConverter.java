// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.MetadataContent;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.UriContent;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Converts between framework message content and framework-owned A2A v1 parts. */
public final class A2AContentConverter {
    private static final String DATA_MARKER = "a2a.data";

    private static final String MEDIA_TYPE = "a2a.mediaType";

    private static final String FILENAME = "a2a.filename";

    private A2AContentConverter() {}

    /**
     * Converts only the final user message, avoiding retransmission of caller-supplied history.
     *
     * @param messages ordered framework input
     * @param continuation optional A2A continuation
     * @param inputModes allowed remote input modes
     * @param codec bounded JSON codec
     * @return A2A user message
     */
    public static com.microsoft.agents.protocols.a2a.Message toA2AMessage(
            List<Message> messages, A2AContinuation continuation, List<String> inputModes, A2AJsonCodec codec) {
        Objects.requireNonNull(messages, "messages");
        Message source = null;
        for (int index = messages.size() - 1; index >= 0; index--) {
            Message candidate = Objects.requireNonNull(messages.get(index), "messages entry");
            if (com.microsoft.agents.core.Role.USER.equals(candidate.role())) {
                source = candidate;
                break;
            }
        }
        if (source == null) {
            throw new A2AConversionException(
                    "A2AAgent requires a user message; system, assistant, and tool history is not retransmitted.");
        }
        ArrayList<Part> parts = new ArrayList<>();
        for (Content content : source.contents()) {
            Part part = toA2APart(content, codec);
            requireMode(part.mediaType(), inputModes, "input");
            parts.add(part);
        }
        if (parts.isEmpty()) {
            throw new A2AConversionException("A2A user message must contain at least one supported content item.");
        }
        com.microsoft.agents.protocols.a2a.Message.Builder builder = com.microsoft.agents.protocols.a2a.Message.builder(
                        Role.ROLE_USER)
                .parts(parts)
                .messageId(source.messageId() == null ? UUID.randomUUID().toString() : source.messageId())
                .metadata(source.metadata());
        if (continuation != null) {
            builder.contextId(continuation.contextId());
            if (continuation.state().isTerminal()) {
                builder.referenceTaskIds(List.of(continuation.taskId()));
            } else {
                builder.taskId(continuation.taskId());
            }
        }
        return builder.build();
    }

    /**
     * Converts one A2A message to a framework message.
     *
     * @param message A2A message
     * @param outputModes accepted output modes
     * @param codec bounded JSON codec
     * @return framework message
     */
    public static Message toFrameworkMessage(
            com.microsoft.agents.protocols.a2a.Message message, List<String> outputModes, A2AJsonCodec codec) {
        ArrayList<Content> contents = new ArrayList<>();
        for (Part part : message.parts()) {
            requireMode(part.mediaType(), outputModes, "output");
            contents.add(toFrameworkContent(part, codec));
        }
        com.microsoft.agents.core.Role role =
                switch (message.role()) {
                    case ROLE_USER -> com.microsoft.agents.core.Role.USER;
                    case ROLE_AGENT -> com.microsoft.agents.core.Role.ASSISTANT;
                    case ROLE_UNSPECIFIED -> throw new A2AConversionException("A2A message role is unspecified.");
                };
        return new Message(role, contents, null, message.messageId(), message.metadata());
    }

    /**
     * Converts task artifacts to framework assistant messages without replaying task history.
     *
     * @param task task snapshot
     * @param outputModes accepted output modes
     * @param codec bounded JSON codec
     * @return ordered artifact messages
     */
    public static List<Message> toFrameworkMessages(Task task, List<String> outputModes, A2AJsonCodec codec) {
        ArrayList<Message> messages = new ArrayList<>();
        for (Artifact artifact : task.artifacts()) {
            messages.add(artifactToFrameworkMessage(artifact, outputModes, codec));
        }
        if (messages.isEmpty() && task.status().message() != null) {
            messages.add(toFrameworkMessage(task.status().message(), outputModes, codec));
        }
        return List.copyOf(messages);
    }

    /**
     * Converts one artifact to a framework assistant message.
     *
     * @param artifact artifact
     * @param outputModes accepted output modes
     * @param codec bounded JSON codec
     * @return assistant message
     */
    public static Message artifactToFrameworkMessage(Artifact artifact, List<String> outputModes, A2AJsonCodec codec) {
        ArrayList<Content> contents = new ArrayList<>();
        for (Part part : artifact.parts()) {
            requireMode(part.mediaType(), outputModes, "output");
            contents.add(toFrameworkContent(part, codec));
        }
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(artifact.metadata());
        metadata.put("a2a.artifactId", StateValue.string(artifact.artifactId()));
        if (artifact.name() != null) {
            metadata.put("a2a.artifactName", StateValue.string(artifact.name()));
        }
        return new Message(com.microsoft.agents.core.Role.ASSISTANT, contents, null, null, metadata);
    }

    /**
     * Converts assistant response content to A2A parts and rejects unsupported output modes.
     *
     * @param messages ordered framework response messages
     * @param outputModes advertised output modes
     * @param codec bounded JSON codec
     * @return ordered A2A parts
     */
    public static List<Part> toA2AParts(List<Message> messages, List<String> outputModes, A2AJsonCodec codec) {
        Objects.requireNonNull(messages, "messages");
        ArrayList<Part> parts = new ArrayList<>();
        for (Message message : messages) {
            if (!com.microsoft.agents.core.Role.ASSISTANT.equals(message.role())) {
                continue;
            }
            for (Content content : message.contents()) {
                Part part = toA2APart(content, codec);
                requireMode(part.mediaType(), outputModes, "output");
                parts.add(part);
            }
        }
        return List.copyOf(parts);
    }

    private static Part toA2APart(Content content, A2AJsonCodec codec) {
        if (content instanceof TextContent text) {
            String mediaType = metadataString(text.metadata(), MEDIA_TYPE, "text/plain");
            if (metadataBoolean(text.metadata(), DATA_MARKER)) {
                try {
                    return new DataPart(codec.parse(text.text()), mediaType, cleanMetadata(text.metadata()));
                } catch (A2AException failure) {
                    throw new A2AConversionException(
                            "Framework content marked as A2A data does not contain valid bounded JSON.", failure);
                }
            }
            return new TextPart(text.text(), mediaType, cleanMetadata(text.metadata()));
        }
        if (content instanceof DataContent data) {
            String filename = metadataString(data.metadata(), FILENAME, "content.bin");
            return FilePart.bytes(data.data(), filename, data.mediaType(), cleanMetadata(data.metadata()));
        }
        if (content instanceof UriContent uri) {
            String mediaType = uri.mediaType() == null ? "application/octet-stream" : uri.mediaType();
            String filename = metadataString(uri.metadata(), FILENAME, filename(uri.uri()));
            return FilePart.uri(uri.uri(), filename, mediaType, cleanMetadata(uri.metadata()));
        }
        if (content instanceof MetadataContent metadata) {
            return new DataPart(StateValue.object(metadata.values()), "application/json", Map.of());
        }
        throw new A2AConversionException("Unsupported framework content "
                + content.getClass().getSimpleName()
                + "; A2A supports text, URI/file bytes, and JSON data.");
    }

    private static Content toFrameworkContent(Part part, A2AJsonCodec codec) {
        if (part instanceof TextPart text) {
            LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(text.metadata());
            if (!"text/plain".equals(text.mediaType())) {
                metadata.put(MEDIA_TYPE, StateValue.string(text.mediaType()));
            }
            return new TextContent(text.text(), metadata);
        }
        if (part instanceof DataPart data) {
            LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(data.metadata());
            metadata.put(DATA_MARKER, StateValue.bool(true));
            metadata.put(MEDIA_TYPE, StateValue.string(data.mediaType()));
            return new TextContent(codec.writeString(data.data()), metadata);
        }
        FilePart file = (FilePart) part;
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(file.metadata());
        if (file.filename() != null) {
            metadata.put(FILENAME, StateValue.string(file.filename()));
        }
        return file.inline()
                ? new DataContent(file.bytes(), file.mediaType(), metadata)
                : new UriContent(file.uri(), file.mediaType(), metadata);
    }

    private static void requireMode(String mediaType, List<String> acceptedModes, String direction) {
        Objects.requireNonNull(acceptedModes, "acceptedModes");
        if (acceptedModes.isEmpty()) {
            return;
        }
        boolean supported = acceptedModes.stream().anyMatch(mode -> matches(mode, mediaType));
        if (!supported) {
            throw new A2AConversionException("A2A " + direction + " media type '" + mediaType
                    + "' is not supported; accepted modes are " + acceptedModes + ".");
        }
    }

    private static boolean matches(String pattern, String mediaType) {
        String value = A2AValidation.nonBlank(pattern, "media mode");
        if ("*/*".equals(value) || value.equalsIgnoreCase(mediaType)) {
            return true;
        }
        int slash = value.indexOf('/');
        return value.endsWith("/*") && slash > 0 && mediaType.regionMatches(true, 0, value, 0, slash + 1);
    }

    private static String metadataString(Map<String, StateValue> metadata, String name, String fallback) {
        StateValue value = metadata.get(name);
        return value instanceof StateValue.StringValue string ? string.value() : fallback;
    }

    private static boolean metadataBoolean(Map<String, StateValue> metadata, String name) {
        StateValue value = metadata.get(name);
        return value instanceof StateValue.BooleanValue bool && bool.value();
    }

    private static Map<String, StateValue> cleanMetadata(Map<String, StateValue> metadata) {
        LinkedHashMap<String, StateValue> clean = new LinkedHashMap<>(metadata);
        clean.remove(DATA_MARKER);
        clean.remove(MEDIA_TYPE);
        clean.remove(FILENAME);
        return Map.copyOf(clean);
    }

    private static String filename(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isBlank() || path.endsWith("/")) {
            return "content";
        }
        int slash = path.lastIndexOf('/');
        String value = slash < 0 ? path : path.substring(slash + 1);
        return value.isBlank() ? "content" : value;
    }
}
