// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.internal.StrictJsonCodec;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CopilotStudioWireCodec {
    private static final String ADAPTIVE_CARD = "application/vnd.microsoft.card.adaptive";

    private final StrictJsonCodec json;

    CopilotStudioWireCodec(CopilotStudioLimits limits) {
        json = new StrictJsonCodec(
                limits.maxRequestBytes(),
                limits.maxEventBytes(),
                limits.maxNestingDepth(),
                limits.maxStringLength(),
                256,
                limits.maxCollectionEntries());
    }

    byte[] startRequest(String locale) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        values.put("emitStartConversationEvent", StateValue.bool(true));
        if (locale != null) {
            values.put("locale", StateValue.string(locale));
        }
        return json.write(StateValue.object(values));
    }

    byte[] activityRequest(String conversationId, CopilotStudioActivity activity) {
        LinkedHashMap<String, StateValue> raw =
                new LinkedHashMap<>(activity.raw().values());
        raw.put(
                "conversation",
                StateValue.object(Map.of("id", StateValue.string(required(conversationId, "conversationId")))));
        if (activity.id() != null) {
            raw.put("id", StateValue.string(activity.id()));
        }
        return json.write(StateValue.object(Map.of("activity", StateValue.object(raw))));
    }

    CopilotStudioActivity parseActivity(byte[] bytes) {
        StateValue parsed = json.parse(bytes);
        if (!(parsed instanceof StateValue.ObjectValue raw)) {
            throw protocol("Activity must be a JSON object.", "activity_shape");
        }
        String type = string(raw, "type");
        if (type == null || type.isBlank()) {
            throw protocol("Activity type is required.", "activity_type");
        }
        String id = string(raw, "id");
        String text = string(raw, "text");
        Instant timestamp = instant(string(raw, "timestamp"));
        CopilotStudioChannelAccount from = account(object(raw, "from"));
        CopilotStudioChannelAccount recipient = account(object(raw, "recipient"));
        StateValue.ObjectValue conversation = object(raw, "conversation");
        String conversationId = conversation == null ? null : string(conversation, "id");
        String replyToId = string(raw, "replyToId");
        String name = string(raw, "name");
        List<CopilotStudioAttachment> attachments = attachments(array(raw, "attachments"));
        List<CopilotStudioCitation> citations = citations(raw);
        StateValue value = raw.values().getOrDefault("value", StateValue.nullValue());
        LinkedHashMap<String, StateValue> properties = new LinkedHashMap<>(raw.values());
        List.of(
                        "id",
                        "type",
                        "text",
                        "timestamp",
                        "from",
                        "recipient",
                        "conversation",
                        "replyToId",
                        "name",
                        "attachments",
                        "entities",
                        "value")
                .forEach(properties::remove);
        return new CopilotStudioActivity(
                id,
                type,
                text,
                timestamp,
                from,
                recipient,
                conversationId,
                replyToId,
                name,
                attachments,
                citations,
                value,
                properties,
                raw);
    }

    CopilotStudioEventType classify(CopilotStudioActivity activity) {
        if (activity.attachments().stream()
                .map(CopilotStudioAttachment::contentType)
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .anyMatch(value -> value.contains("oauth") || value.contains("signin"))) {
            return CopilotStudioEventType.OAUTH_REQUIRED;
        }
        if (activity.attachments().stream()
                .map(CopilotStudioAttachment::adaptiveCard)
                .filter(java.util.Objects::nonNull)
                .anyMatch(card -> !card.actions().isEmpty() || containsInput(card.body()))) {
            return CopilotStudioEventType.INPUT_REQUIRED;
        }
        return switch (activity.type().toLowerCase(java.util.Locale.ROOT)) {
            case "message" -> CopilotStudioEventType.MESSAGE;
            case "typing" -> CopilotStudioEventType.TYPING;
            case "messageupdate", "update" -> CopilotStudioEventType.UPDATE;
            case "endofconversation" -> CopilotStudioEventType.END;
            case "trace" ->
                "error".equalsIgnoreCase(activity.name()) ? CopilotStudioEventType.ERROR : CopilotStudioEventType.OTHER;
            default -> CopilotStudioEventType.OTHER;
        };
    }

    private static List<CopilotStudioAttachment> attachments(StateValue.ArrayValue array) {
        if (array == null) {
            return List.of();
        }
        ArrayList<CopilotStudioAttachment> result = new ArrayList<>();
        for (StateValue value : array.values()) {
            if (!(value instanceof StateValue.ObjectValue object)) {
                throw protocol("Attachment must be a JSON object.", "attachment_shape");
            }
            String contentType = string(object, "contentType");
            if (contentType == null || contentType.isBlank()) {
                throw protocol("Attachment contentType is required.", "attachment_content_type");
            }
            String name = string(object, "name");
            URI contentUrl = uri(string(object, "contentUrl"));
            StateValue content = object.values().getOrDefault("content", StateValue.nullValue());
            CopilotStudioAdaptiveCard card =
                    ADAPTIVE_CARD.equalsIgnoreCase(contentType) && content instanceof StateValue.ObjectValue cardObject
                            ? adaptiveCard(cardObject)
                            : null;
            result.add(new CopilotStudioAttachment(contentType, name, contentUrl, content, card));
        }
        return List.copyOf(result);
    }

    private static CopilotStudioAdaptiveCard adaptiveCard(StateValue.ObjectValue raw) {
        StateValue.ArrayValue body = array(raw, "body");
        StateValue.ArrayValue actions = array(raw, "actions");
        ArrayList<CopilotStudioCardAction> mappedActions = new ArrayList<>();
        if (actions != null) {
            for (StateValue value : actions.values()) {
                if (!(value instanceof StateValue.ObjectValue action)) {
                    throw protocol("Adaptive Card action must be an object.", "card_action_shape");
                }
                mappedActions.add(new CopilotStudioCardAction(
                        required(string(action, "type"), "action.type"),
                        string(action, "title"),
                        string(action, "id"),
                        action.values()
                                .getOrDefault("data", action.values().getOrDefault("value", StateValue.nullValue()))));
            }
        }
        return new CopilotStudioAdaptiveCard(
                string(raw, "version"), body == null ? List.of() : body.values(), mappedActions, raw);
    }

    private static List<CopilotStudioCitation> citations(StateValue.ObjectValue raw) {
        StateValue.ArrayValue values = array(raw, "citations");
        if (values == null) {
            values = array(raw, "entities");
        }
        if (values == null) {
            return List.of();
        }
        ArrayList<CopilotStudioCitation> result = new ArrayList<>();
        for (StateValue value : values.values()) {
            if (!(value instanceof StateValue.ObjectValue citation)) {
                continue;
            }
            String type = string(citation, "type");
            if (type != null
                    && !type.toLowerCase(java.util.Locale.ROOT).contains("citation")
                    && !citation.values().containsKey("url")) {
                continue;
            }
            URI url = uri(string(citation, "url"));
            Integer start = integer(citation.values().get("startIndex"));
            Integer end = integer(citation.values().get("endIndex"));
            result.add(
                    new CopilotStudioCitation(string(citation, "title"), url, string(citation, "source"), start, end));
        }
        return List.copyOf(result);
    }

    private static boolean containsInput(List<StateValue> values) {
        for (StateValue value : values) {
            if (value instanceof StateValue.ObjectValue object) {
                String type = string(object, "type");
                if (type != null && type.startsWith("Input.")) {
                    return true;
                }
                StateValue.ArrayValue items = array(object, "items");
                if (items != null && containsInput(items.values())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static CopilotStudioChannelAccount account(StateValue.ObjectValue object) {
        return object == null
                ? null
                : new CopilotStudioChannelAccount(string(object, "id"), string(object, "name"), string(object, "role"));
    }

    private static StateValue.ObjectValue object(StateValue.ObjectValue value, String name) {
        StateValue child = value.values().get(name);
        return child instanceof StateValue.ObjectValue object ? object : null;
    }

    private static StateValue.ArrayValue array(StateValue.ObjectValue value, String name) {
        StateValue child = value.values().get(name);
        return child instanceof StateValue.ArrayValue array ? array : null;
    }

    private static String string(StateValue.ObjectValue value, String name) {
        StateValue child = value.values().get(name);
        return child instanceof StateValue.StringValue string ? string.value() : null;
    }

    private static Integer integer(StateValue value) {
        if (!(value instanceof StateValue.NumberValue number)) {
            return null;
        }
        try {
            return number.value().intValueExact();
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    private static URI uri(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw protocol("Activity contains an invalid URI.", "activity_uri");
        }
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw protocol("Activity timestamp is invalid.", "activity_timestamp");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw protocol(name + " is required.", "required_value");
        }
        return value;
    }

    private static CopilotStudioException protocol(String message, String code) {
        return new CopilotStudioException(message, null, CopilotStudioException.Kind.PROTOCOL, null, code);
    }
}
