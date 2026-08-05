// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes the sealed {@link Content} hierarchy with explicit stable discriminators.
 *
 * <p>Unknown additive properties are ignored when decoding version 1. Unknown discriminators are
 * rejected and never interpreted as Java class names.
 */
public final class ContentStateCodec implements StateCodec<Content> {
    /** Stable registry identifier for content values. */
    public static final String TYPE_ID = "com.microsoft.agents.core.content";

    /** Version of the initial content schema. */
    public static final int VERSION = 1;

    @Override
    public String typeId() {
        return TYPE_ID;
    }

    @Override
    public int currentVersion() {
        return VERSION;
    }

    @Override
    public StateValue encode(Content content) {
        if (content == null) {
            throw new NullPointerException("content");
        }
        LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
        fields.put("kind", StateValue.string(content.kind()));
        switch (content) {
            case TextContent text -> fields.put("text", StateValue.string(text.text()));
            case ReasoningContent reasoning -> {
                putString(fields, "id", reasoning.id());
                putString(fields, "text", reasoning.text());
                putString(fields, "protectedData", reasoning.protectedData());
            }
            case DataContent data -> {
                fields.put("mediaType", StateValue.string(data.mediaType()));
                fields.put("uri", StateValue.string(data.dataUri().toString()));
            }
            case UriContent uri -> {
                fields.put("uri", StateValue.string(uri.uri().toString()));
                putString(fields, "mediaType", uri.mediaType());
            }
            case ErrorContent error -> {
                fields.put("message", StateValue.string(error.message()));
                putString(fields, "errorCode", error.errorCode());
                putString(fields, "details", error.details());
            }
            case FunctionCallContent call -> {
                fields.put("callId", StateValue.string(call.callId()));
                fields.put("name", StateValue.string(call.name()));
                fields.put("arguments", call.arguments());
                fields.put("informationalOnly", StateValue.bool(call.informationalOnly()));
            }
            case FunctionResultContent result -> {
                fields.put("callId", StateValue.string(result.callId()));
                fields.put("result", result.result());
                fields.put(
                        "items",
                        StateValue.array(
                                result.items().stream().map(this::encode).toList()));
                putString(fields, "error", result.error());
            }
            case UsageContent usage ->
                fields.put("usage", StateValue.object(usage.usage().values()));
            case MetadataContent metadata -> fields.put("values", StateValue.object(metadata.values()));
        }
        if (!content.metadata().isEmpty()) {
            fields.put("metadata", StateValue.object(content.metadata()));
        }
        return StateValue.object(fields);
    }

    @Override
    public StateValue migrate(StateValue value, int fromVersion, int toVersion) {
        throw new SerializationException(
                SerializationError.CODEC_MIGRATION,
                "Content schema migration from version " + fromVersion + " to " + toVersion + " is unavailable.");
    }

    @Override
    public Content decode(StateValue value, int version) {
        if (version != VERSION) {
            throw new SerializationException(
                    SerializationError.CODEC_MIGRATION, "Unsupported content codec version " + version + ".");
        }
        StateValue.ObjectValue object = requireObject(value, "content");
        String kind = requireString(object, "kind");
        Map<String, StateValue> metadata = optionalObject(object, "metadata");
        try {
            return switch (kind) {
                case "text" -> new TextContent(requireString(object, "text"), metadata);
                case "reasoning" ->
                    new ReasoningContent(
                            optionalString(object, "id"),
                            optionalString(object, "text"),
                            optionalString(object, "protectedData"),
                            metadata);
                case "data" -> decodeData(object, metadata);
                case "uri" ->
                    new UriContent(
                            URI.create(requireString(object, "uri")), optionalString(object, "mediaType"), metadata);
                case "error" ->
                    new ErrorContent(
                            requireString(object, "message"),
                            optionalString(object, "errorCode"),
                            optionalString(object, "details"),
                            metadata);
                case "functionCall" ->
                    new FunctionCallContent(
                            requireString(object, "callId"),
                            requireString(object, "name"),
                            object.require("arguments"),
                            optionalBoolean(object, "informationalOnly", false),
                            metadata);
                case "functionResult" ->
                    new FunctionResultContent(
                            requireString(object, "callId"),
                            object.require("result"),
                            decodeItems(object),
                            optionalString(object, "error"),
                            metadata);
                case "usage" ->
                    new UsageContent(
                            new UsageDetails(requireObject(object.require("usage"), "usage")
                                    .values()),
                            metadata);
                case "metadata" ->
                    new MetadataContent(
                            requireObject(object.require("values"), "values").values());
                default -> throw malformed("Unknown content discriminator '" + kind + "'.");
            };
        } catch (ValidationException exception) {
            throw malformed("Invalid " + kind + " content.", exception);
        } catch (IllegalArgumentException exception) {
            throw malformed("Invalid " + kind + " content.", exception);
        }
    }

    private List<Content> decodeItems(StateValue.ObjectValue object) {
        StateValue items = object.require("items");
        if (!(items instanceof StateValue.ArrayValue array)) {
            throw malformed("Function result items must be an array.");
        }
        ArrayList<Content> decoded = new ArrayList<>(array.values().size());
        array.values().forEach(item -> decoded.add(decode(item, VERSION)));
        return List.copyOf(decoded);
    }

    private static DataContent decodeData(StateValue.ObjectValue object, Map<String, StateValue> metadata) {
        DataContent decoded = DataContent.fromDataUri(requireString(object, "uri"));
        String mediaType = requireString(object, "mediaType");
        if (!decoded.mediaType().equals(mediaType)) {
            throw malformed("Data URI media type does not match mediaType.");
        }
        return new DataContent(decoded.data(), mediaType, metadata);
    }

    private static void putString(Map<String, StateValue> fields, String name, String value) {
        if (value != null) {
            fields.put(name, StateValue.string(value));
        }
    }

    private static StateValue.ObjectValue requireObject(StateValue value, String name) {
        if (value instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw malformed(name + " must be an object.");
    }

    private static Map<String, StateValue> optionalObject(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null) {
            return Map.of();
        }
        return requireObject(value, name).values();
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

    private static boolean optionalBoolean(StateValue.ObjectValue object, String name, boolean defaultValue) {
        StateValue value = object.values().get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof StateValue.BooleanValue bool) {
            return bool.value();
        }
        throw malformed(name + " must be a Boolean when present.");
    }

    private static SerializationException malformed(String message) {
        return new SerializationException(SerializationError.MALFORMED_DOCUMENT, message);
    }

    private static SerializationException malformed(String message, Throwable cause) {
        return new SerializationException(SerializationError.MALFORMED_DOCUMENT, message, cause);
    }
}
