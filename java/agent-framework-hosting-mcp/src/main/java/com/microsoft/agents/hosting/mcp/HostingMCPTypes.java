// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.protocols.mcp.MCPContent;
import com.microsoft.agents.protocols.mcp.MCPLimits;
import com.microsoft.agents.protocols.mcp.MCPPromptMessage;
import com.microsoft.agents.protocols.mcp.MCPReadResourceResult;
import com.microsoft.agents.protocols.mcp.MCPResourceContents;
import com.microsoft.agents.protocols.mcp.MCPRole;
import io.modelcontextprotocol.spec.McpSchema;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class HostingMCPTypes {
    private HostingMCPTypes() {}

    static StateValue.ObjectValue fromMap(Map<String, ?> values, MCPLimits limits) {
        StateValue result = fromJava(values, limits, new Counter(limits), 0);
        if (result instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw new ValidationException("MCP arguments must be a JSON object.");
    }

    static Object toJava(StateValue value, MCPLimits limits) {
        return toJava(value, new Counter(limits), 0);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> toJavaMap(StateValue.ObjectValue value, MCPLimits limits) {
        return (Map<String, Object>) toJava(value, limits);
    }

    static List<McpSchema.Content> toSdkContents(List<MCPContent> contents, MCPLimits limits) {
        checkCollection(contents.size(), limits);
        return contents.stream().map(content -> toSdkContent(content, limits)).toList();
    }

    static List<McpSchema.ResourceContents> toSdkResourceContents(MCPReadResourceResult result, MCPLimits limits) {
        checkCollection(result.contents().size(), limits);
        return result.contents().stream()
                .map(content -> toSdkResourceContents(content, limits))
                .toList();
    }

    static List<McpSchema.PromptMessage> toSdkPromptMessages(List<MCPPromptMessage> messages, MCPLimits limits) {
        checkCollection(messages.size(), limits);
        return messages.stream()
                .map(message -> McpSchema.PromptMessage.builder(
                                message.role() == MCPRole.USER ? McpSchema.Role.USER : McpSchema.Role.ASSISTANT,
                                toSdkContent(message.content(), limits))
                        .build())
                .toList();
    }

    private static StateValue fromJava(Object value, MCPLimits limits, Counter counter, int depth) {
        counter.depth(depth);
        if (value == null) {
            counter.item();
            return StateValue.nullValue();
        }
        if (value instanceof String string) {
            counter.text(string);
            return StateValue.string(string);
        }
        if (value instanceof Boolean bool) {
            counter.item();
            return StateValue.bool(bool);
        }
        if (value instanceof BigDecimal decimal) {
            counter.text(decimal.toPlainString());
            return StateValue.number(decimal);
        }
        if (value instanceof BigInteger integer) {
            counter.text(integer.toString());
            return StateValue.integer(integer);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            counter.item();
            return StateValue.integer(((Number) value).longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw new ValidationException("MCP number must be finite.");
            }
            return StateValue.number(BigDecimal.valueOf(number));
        }
        if (value instanceof Map<?, ?> map) {
            checkCollection(map.size(), limits);
            LinkedHashMap<String, StateValue> converted = new LinkedHashMap<>();
            map.forEach((key, child) -> {
                if (!(key instanceof String name) || name.isBlank()) {
                    throw new ValidationException("MCP argument object keys must be non-blank strings.");
                }
                counter.text(name);
                converted.put(name, fromJava(child, limits, counter, depth + 1));
            });
            return StateValue.object(converted);
        }
        if (value instanceof Iterable<?> iterable) {
            ArrayList<StateValue> converted = new ArrayList<>();
            for (Object child : iterable) {
                checkCollection(converted.size() + 1, limits);
                converted.add(fromJava(child, limits, counter, depth + 1));
            }
            return StateValue.array(converted);
        }
        throw new ValidationException(
                "MCP argument has unsupported runtime type '" + value.getClass().getName() + "'.");
    }

    private static Object toJava(StateValue value, Counter counter, int depth) {
        counter.depth(depth);
        return switch (value) {
            case StateValue.NullValue nullValue -> {
                Objects.requireNonNull(nullValue, "nullValue");
                counter.item();
                yield null;
            }
            case StateValue.StringValue string -> {
                counter.text(string.value());
                yield string.value();
            }
            case StateValue.BooleanValue bool -> {
                counter.item();
                yield bool.value();
            }
            case StateValue.NumberValue number -> {
                counter.text(number.value().toPlainString());
                yield number.value();
            }
            case StateValue.ArrayValue array -> {
                counter.collection(array.values().size());
                ArrayList<Object> values = new ArrayList<>(array.values().size());
                array.values().forEach(child -> values.add(toJava(child, counter, depth + 1)));
                yield Collections.unmodifiableList(values);
            }
            case StateValue.ObjectValue object -> {
                counter.collection(object.values().size());
                LinkedHashMap<String, Object> values = new LinkedHashMap<>();
                object.values().forEach((name, child) -> {
                    counter.text(name);
                    values.put(name, toJava(child, counter, depth + 1));
                });
                yield Collections.unmodifiableMap(values);
            }
        };
    }

    private static McpSchema.Content toSdkContent(MCPContent content, MCPLimits limits) {
        if (content instanceof MCPContent.Text text) {
            checkText(text.text(), limits);
            return McpSchema.TextContent.builder(text.text())
                    .meta(toJavaMetadata(text.metadata(), limits))
                    .build();
        }
        if (content instanceof MCPContent.Image image) {
            checkBytes(image.data(), limits);
            return McpSchema.ImageContent.builder(Base64.getEncoder().encodeToString(image.data()), image.mediaType())
                    .meta(toJavaMetadata(image.metadata(), limits))
                    .build();
        }
        if (content instanceof MCPContent.Audio audio) {
            checkBytes(audio.data(), limits);
            return McpSchema.AudioContent.builder(Base64.getEncoder().encodeToString(audio.data()), audio.mediaType())
                    .meta(toJavaMetadata(audio.metadata(), limits))
                    .build();
        }
        if (content instanceof MCPContent.EmbeddedResource embedded) {
            return McpSchema.EmbeddedResource.builder(toSdkResourceContents(embedded.resource(), limits))
                    .meta(toJavaMetadata(embedded.metadata(), limits))
                    .build();
        }
        if (content instanceof MCPContent.ResourceLink link) {
            McpSchema.ResourceLink.Builder builder = McpSchema.ResourceLink.builder()
                    .uri(link.uri().toString())
                    .name(link.name())
                    .meta(toJavaMetadata(link.metadata(), limits));
            if (link.title() != null) {
                builder.title(link.title());
            }
            if (!link.description().isEmpty()) {
                builder.description(link.description());
            }
            if (link.mediaType() != null) {
                builder.mimeType(link.mediaType());
            }
            if (link.size() != null) {
                builder.size(link.size());
            }
            return builder.build();
        }
        throw new ValidationException("Unsupported MCP content implementation.");
    }

    private static McpSchema.ResourceContents toSdkResourceContents(MCPResourceContents contents, MCPLimits limits) {
        if (contents instanceof MCPResourceContents.Text text) {
            checkText(text.text(), limits);
            McpSchema.TextResourceContents.Builder builder = McpSchema.TextResourceContents.builder(
                            text.uri().toString(), text.text())
                    .meta(toJavaMetadata(text.metadata(), limits));
            if (text.mediaType() != null) {
                builder.mimeType(text.mediaType());
            }
            return builder.build();
        }
        if (contents instanceof MCPResourceContents.Binary binary) {
            checkBytes(binary.data(), limits);
            McpSchema.BlobResourceContents.Builder builder = McpSchema.BlobResourceContents.builder(
                            binary.uri().toString(), Base64.getEncoder().encodeToString(binary.data()))
                    .meta(toJavaMetadata(binary.metadata(), limits));
            if (binary.mediaType() != null) {
                builder.mimeType(binary.mediaType());
            }
            return builder.build();
        }
        throw new ValidationException("Unsupported MCP resource contents implementation.");
    }

    private static Map<String, Object> toJavaMetadata(Map<String, StateValue> metadata, MCPLimits limits) {
        return toJavaMap(StateValue.object(metadata), limits);
    }

    private static void checkText(String text, MCPLimits limits) {
        if (text.getBytes(StandardCharsets.UTF_8).length > limits.maxPayloadBytes()) {
            throw new ValidationException("MCP text exceeds the configured payload limit.");
        }
    }

    private static void checkBytes(byte[] bytes, MCPLimits limits) {
        if (bytes.length > limits.maxPayloadBytes()) {
            throw new ValidationException("MCP binary content exceeds the configured payload limit.");
        }
    }

    private static void checkCollection(int size, MCPLimits limits) {
        if (size > limits.maxCollectionItems()) {
            throw new ValidationException("MCP collection exceeds the configured item limit.");
        }
    }

    private static final class Counter {
        private final MCPLimits limits;

        private int items;

        private long bytes;

        private Counter(MCPLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
        }

        private void depth(int depth) {
            if (depth > limits.maxNestingDepth()) {
                throw new ValidationException("MCP value exceeds maximum nesting depth.");
            }
        }

        private void collection(int size) {
            checkCollection(size, limits);
        }

        private void item() {
            items++;
            if (items > limits.maxCollectionItems()) {
                throw new ValidationException("MCP value exceeds aggregate item limit.");
            }
        }

        private void text(String text) {
            item();
            bytes += text.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > limits.maxPayloadBytes()) {
                throw new ValidationException("MCP value exceeds payload limit.");
            }
        }
    }
}
