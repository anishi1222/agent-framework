// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Strictly decodes supported ChatKit thread items and deterministically encodes thread events.
 *
 * <p>The default configuration rejects duplicate keys, trailing tokens, unknown fields on
 * supported objects, non-finite values, and inputs exceeding configured document, string,
 * collection, numeric, or nesting limits. Unsupported item bodies are never retained.
 */
public final class ChatKitJsonCodec {
    private static final Set<String> USER_FIELDS =
            Set.of("attachments", "content", "created_at", "id", "quoted_text", "thread_id", "type");
    private static final Set<String> ASSISTANT_FIELDS = Set.of("content", "created_at", "id", "thread_id", "type");
    private static final Set<String> HIDDEN_CONTEXT_FIELDS = Set.of("content", "created_at", "id", "thread_id", "type");
    private static final Set<String> USER_CONTENT_FIELDS = Set.of("text", "type");
    private static final Set<String> ASSISTANT_CONTENT_FIELDS = Set.of("annotations", "text", "type");
    private static final Set<String> ATTACHMENT_FIELDS = Set.of("id", "mime_type", "name", "preview_url", "type");

    private final ChatKitJsonLimits limits;
    private final ChatKitUnknownFieldPolicy unknownFieldPolicy;
    private final ChatKitUnsupportedItemPolicy unsupportedItemPolicy;
    private final ObjectMapper mapper;

    /** Creates a codec with strict default limits and rejection policies. */
    public ChatKitJsonCodec() {
        this(ChatKitJsonLimits.defaults(), ChatKitUnknownFieldPolicy.REJECT, ChatKitUnsupportedItemPolicy.REJECT);
    }

    /**
     * Creates a codec with explicit limits and protocol-drift policies.
     *
     * @param limits JSON resource limits
     * @param unknownFieldPolicy unknown-field policy for supported wire objects
     * @param unsupportedItemPolicy unsupported discriminator policy
     */
    public ChatKitJsonCodec(
            ChatKitJsonLimits limits,
            ChatKitUnknownFieldPolicy unknownFieldPolicy,
            ChatKitUnsupportedItemPolicy unsupportedItemPolicy) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.unknownFieldPolicy = Objects.requireNonNull(unknownFieldPolicy, "unknownFieldPolicy");
        this.unsupportedItemPolicy = Objects.requireNonNull(unsupportedItemPolicy, "unsupportedItemPolicy");

        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(limits.maxNestingDepth())
                        .maxStringLength(limits.maxStringCharacters())
                        .maxNumberLength(limits.maxNumberCharacters())
                        .build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        mapper = JsonMapper.builder(factory)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
    }

    /**
     * Decodes one supported or policy-retained ChatKit thread item.
     *
     * @param json JSON object document
     * @return decoded framework-owned item
     */
    public ChatKitThreadItem decodeThreadItem(String json) {
        JsonNode root = parse(json);
        requireObject(root, "$");
        return decodeThreadItem(root, "$");
    }

    /**
     * Decodes an ordered array of ChatKit thread items.
     *
     * @param json JSON array document
     * @return immutable items in wire order
     */
    public List<ChatKitThreadItem> decodeThreadItems(String json) {
        JsonNode root = parse(json);
        requireArray(root, "$");
        ArrayList<ChatKitThreadItem> result = new ArrayList<>(root.size());
        for (int index = 0; index < root.size(); index++) {
            JsonNode item = root.get(index);
            requireObject(item, "$[" + index + "]");
            result.add(decodeThreadItem(item, "$[" + index + "]"));
        }
        return List.copyOf(result);
    }

    /**
     * Deterministically encodes a supported ChatKit thread item.
     *
     * @param item item to encode
     * @return canonical compact JSON
     */
    public String encodeThreadItem(ChatKitThreadItem item) {
        Objects.requireNonNull(item, "item");
        if (item instanceof ChatKitUnsupportedThreadItem) {
            throw new IllegalArgumentException("Unsupported thread-item payloads cannot be encoded.");
        }
        return write(buildThreadItem(item));
    }

    /**
     * Deterministically encodes an outbound ChatKit thread event.
     *
     * @param event event to encode
     * @return canonical compact JSON
     */
    public String encodeThreadEvent(ChatKitThreadEvent event) {
        Objects.requireNonNull(event, "event");
        ObjectNode root = mapper.createObjectNode();
        root.put("type", event.type());
        if (event instanceof ChatKitThreadItemAddedEvent added) {
            root.set("item", buildAssistantItem(added.item()));
        } else if (event instanceof ChatKitThreadItemUpdatedEvent updated) {
            root.put("item_id", updated.itemId());
            ObjectNode update = mapper.createObjectNode();
            update.put("content_index", updated.contentIndex());
            update.put("delta", updated.delta());
            root.set("update", update);
        } else if (event instanceof ChatKitThreadItemDoneEvent done) {
            root.set("item", buildAssistantItem(done.item()));
        } else {
            throw new IllegalArgumentException("Unsupported ChatKit event implementation.");
        }
        return write(root);
    }

    private JsonNode parse(String json) {
        Objects.requireNonNull(json, "json");
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > limits.maxDocumentBytes()) {
            throw new IllegalArgumentException("ChatKit JSON exceeds the document-size limit.");
        }
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null) {
                throw new IllegalArgumentException("ChatKit JSON must not be empty.");
            }
            validateNode(root, 1);
            return root;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid ChatKit JSON.", exception);
        }
    }

    private ChatKitThreadItem decodeThreadItem(JsonNode node, String path) {
        String type = requiredString(node, "type", path);
        return switch (type) {
            case "user_message" -> decodeUserMessage(node, path);
            case "assistant_message" -> decodeAssistantMessage(node, path);
            case "hidden_context_item", "sdk_hidden_context" -> decodeHiddenContext(node, path, type);
            default -> decodeUnsupported(node, path, type);
        };
    }

    private ChatKitUserMessageItem decodeUserMessage(JsonNode node, String path) {
        enforceKnownFields(node, USER_FIELDS, path);
        List<String> textParts = decodeUserContent(node.get("content"), path + ".content");
        List<ChatKitAttachment> attachments = decodeAttachments(node.get("attachments"), path + ".attachments");
        return new ChatKitUserMessageItem(
                requiredString(node, "id", path),
                requiredString(node, "thread_id", path),
                textParts,
                attachments,
                optionalString(node, "quoted_text", path),
                optionalInstant(node, "created_at", path));
    }

    private ChatKitAssistantMessageItem decodeAssistantMessage(JsonNode node, String path) {
        enforceKnownFields(node, ASSISTANT_FIELDS, path);
        return new ChatKitAssistantMessageItem(
                requiredString(node, "id", path),
                requiredString(node, "thread_id", path),
                decodeAssistantContent(node.get("content"), path + ".content"),
                optionalInstant(node, "created_at", path));
    }

    private ChatKitHiddenContextItem decodeHiddenContext(JsonNode node, String path, String type) {
        enforceKnownFields(node, HIDDEN_CONTEXT_FIELDS, path);
        return new ChatKitHiddenContextItem(
                requiredString(node, "id", path),
                requiredString(node, "thread_id", path),
                ChatKitHiddenContextType.fromWireValue(type),
                requiredString(node, "content", path),
                optionalInstant(node, "created_at", path));
    }

    private ChatKitThreadItem decodeUnsupported(JsonNode node, String path, String type) {
        if (unsupportedItemPolicy == ChatKitUnsupportedItemPolicy.REJECT) {
            throw new IllegalArgumentException("Unsupported ChatKit thread-item type: " + type);
        }
        return new ChatKitUnsupportedThreadItem(
                requiredString(node, "id", path), requiredString(node, "thread_id", path), type);
    }

    private List<String> decodeUserContent(JsonNode node, String path) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        requireArray(node, path);
        ArrayList<String> result = new ArrayList<>(node.size());
        for (int index = 0; index < node.size(); index++) {
            JsonNode part = node.get(index);
            String partPath = path + "[" + index + "]";
            requireObject(part, partPath);
            enforceKnownFields(part, USER_CONTENT_FIELDS, partPath);
            String type = requiredString(part, "type", partPath);
            if (!"text".equals(type)) {
                throw new IllegalArgumentException("Unsupported user content type: " + type);
            }
            result.add(requiredString(part, "text", partPath));
        }
        return List.copyOf(result);
    }

    private List<String> decodeAssistantContent(JsonNode node, String path) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        requireArray(node, path);
        ArrayList<String> result = new ArrayList<>(node.size());
        for (int index = 0; index < node.size(); index++) {
            JsonNode part = node.get(index);
            String partPath = path + "[" + index + "]";
            requireObject(part, partPath);
            enforceKnownFields(part, ASSISTANT_CONTENT_FIELDS, partPath);
            String type = requiredString(part, "type", partPath);
            if (!"output_text".equals(type)) {
                throw new IllegalArgumentException("Unsupported assistant content type: " + type);
            }
            JsonNode annotations = part.get("annotations");
            if (annotations != null && !annotations.isNull()) {
                requireArray(annotations, partPath + ".annotations");
                if (!annotations.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Assistant annotations are outside the supported protocol subset.");
                }
            }
            result.add(requiredString(part, "text", partPath));
        }
        return List.copyOf(result);
    }

    private List<ChatKitAttachment> decodeAttachments(JsonNode node, String path) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        requireArray(node, path);
        ArrayList<ChatKitAttachment> result = new ArrayList<>(node.size());
        for (int index = 0; index < node.size(); index++) {
            JsonNode attachment = node.get(index);
            String attachmentPath = path + "[" + index + "]";
            requireObject(attachment, attachmentPath);
            enforceKnownFields(attachment, ATTACHMENT_FIELDS, attachmentPath);
            String preview = optionalString(attachment, "preview_url", attachmentPath);
            result.add(new ChatKitAttachment(
                    requiredString(attachment, "id", attachmentPath),
                    ChatKitAttachmentKind.fromWireValue(requiredString(attachment, "type", attachmentPath)),
                    optionalString(attachment, "name", attachmentPath),
                    requiredString(attachment, "mime_type", attachmentPath),
                    preview == null ? null : parseUri(preview)));
        }
        return List.copyOf(result);
    }

    private ObjectNode buildThreadItem(ChatKitThreadItem item) {
        if (item instanceof ChatKitUserMessageItem user) {
            return buildUserItem(user);
        }
        if (item instanceof ChatKitAssistantMessageItem assistant) {
            return buildAssistantItem(assistant);
        }
        if (item instanceof ChatKitHiddenContextItem hidden) {
            ObjectNode root = commonItem(hidden);
            root.put("content", hidden.content());
            putInstant(root, hidden.createdAt());
            return root;
        }
        throw new IllegalArgumentException("Unsupported ChatKit thread-item implementation.");
    }

    private ObjectNode buildUserItem(ChatKitUserMessageItem item) {
        ObjectNode root = commonItem(item);
        ArrayNode content = mapper.createArrayNode();
        for (String text : item.textParts()) {
            ObjectNode part = mapper.createObjectNode();
            part.put("type", "text");
            part.put("text", text);
            content.add(part);
        }
        root.set("content", content);

        ArrayNode attachments = mapper.createArrayNode();
        for (ChatKitAttachment attachment : item.attachments()) {
            ObjectNode value = mapper.createObjectNode();
            value.put("id", attachment.id());
            value.put("type", attachment.kind().wireValue());
            if (attachment.name() != null) {
                value.put("name", attachment.name());
            }
            value.put("mime_type", attachment.mediaType());
            if (attachment.previewUri() != null) {
                value.put("preview_url", attachment.previewUri().toString());
            }
            attachments.add(value);
        }
        root.set("attachments", attachments);
        if (item.quotedText() != null) {
            root.put("quoted_text", item.quotedText());
        }
        putInstant(root, item.createdAt());
        return root;
    }

    private ObjectNode buildAssistantItem(ChatKitAssistantMessageItem item) {
        ObjectNode root = commonItem(item);
        ArrayNode content = mapper.createArrayNode();
        for (String text : item.outputTextParts()) {
            ObjectNode part = mapper.createObjectNode();
            part.put("type", "output_text");
            part.put("text", text);
            part.set("annotations", mapper.createArrayNode());
            content.add(part);
        }
        root.set("content", content);
        putInstant(root, item.createdAt());
        return root;
    }

    private ObjectNode commonItem(ChatKitThreadItem item) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", item.id());
        root.put("thread_id", item.threadId());
        root.put("type", item.type());
        return root;
    }

    private void putInstant(ObjectNode node, Instant instant) {
        if (instant != null) {
            node.put("created_at", instant.toString());
        }
    }

    private String write(JsonNode value) {
        JsonNode canonical = canonicalize(value);
        validateNode(canonical, 1);
        try {
            String json = mapper.writeValueAsString(canonical);
            if (json.getBytes(StandardCharsets.UTF_8).length > limits.maxDocumentBytes()) {
                throw new IllegalArgumentException("Encoded ChatKit JSON exceeds the document-size limit.");
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to encode ChatKit JSON.", exception);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ArrayList<String> names = new ArrayList<>();
            Iterator<String> iterator = node.fieldNames();
            iterator.forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            ObjectNode result = mapper.createObjectNode();
            for (String name : names) {
                result.set(name, canonicalize(node.get(name)));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            for (JsonNode child : node) {
                result.add(canonicalize(child));
            }
            return result;
        }
        return node;
    }

    private void validateNode(JsonNode node, int depth) {
        if (depth > limits.maxNestingDepth()) {
            throw new IllegalArgumentException("ChatKit JSON exceeds the nesting-depth limit.");
        }
        if (node.isTextual() && node.textValue().length() > limits.maxStringCharacters()) {
            throw new IllegalArgumentException("ChatKit JSON exceeds the string-length limit.");
        }
        if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue())) {
            throw new IllegalArgumentException("Non-finite JSON numbers are not supported.");
        }
        if (node.isNumber() && node.asText().length() > limits.maxNumberCharacters()) {
            throw new IllegalArgumentException("ChatKit JSON exceeds the numeric-token length limit.");
        }
        if (node.isContainerNode() && node.size() > limits.maxCollectionSize()) {
            throw new IllegalArgumentException("ChatKit JSON exceeds the collection-size limit.");
        }
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (name.length() > limits.maxStringCharacters()) {
                    throw new IllegalArgumentException("ChatKit JSON exceeds the field-name length limit.");
                }
                validateNode(node.get(name), depth + 1);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                validateNode(child, depth + 1);
            }
        }
    }

    private void enforceKnownFields(JsonNode node, Set<String> allowed, String path) {
        if (unknownFieldPolicy == ChatKitUnknownFieldPolicy.IGNORE) {
            return;
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("Unknown field at " + path + ": " + name);
            }
        }
    }

    private static String requiredString(JsonNode node, String field, String path) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(path + "." + field + " must be a string.");
        }
        return value.textValue();
    }

    private static String optionalString(JsonNode node, String field, String path) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(path + "." + field + " must be a string or null.");
        }
        return value.textValue();
    }

    private static Instant optionalInstant(JsonNode node, String field, String path) {
        String value = optionalString(node, field, path);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(path + "." + field + " must be an instant.", exception);
        }
    }

    private static URI parseUri(String value) {
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute()) {
                throw new IllegalArgumentException("Attachment preview_url must be absolute.");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Attachment preview_url is invalid.", exception);
        }
    }

    private static void requireObject(JsonNode node, String path) {
        if (!node.isObject()) {
            throw new IllegalArgumentException(path + " must be a JSON object.");
        }
    }

    private static void requireArray(JsonNode node, String path) {
        if (!node.isArray()) {
            throw new IllegalArgumentException(path + " must be a JSON array.");
        }
    }
}
