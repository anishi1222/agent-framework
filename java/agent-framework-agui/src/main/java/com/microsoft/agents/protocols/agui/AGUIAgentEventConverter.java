// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.ContentStateCodec;
import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.ErrorContent;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.MetadataContent;
import com.microsoft.agents.core.ReasoningContent;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.UriContent;
import com.microsoft.agents.core.UsageContent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts provider-neutral streaming agent updates into an exact AG-UI event stream. */
public final class AGUIAgentEventConverter {
    private static final String UPDATE_EVENT = "microsoft.agent-framework/update";

    private static final String CONTENT_EVENT = "microsoft.agent-framework/content";

    private static final String ERROR_EVENT = "microsoft.agent-framework/error";

    private final String runId;

    private final AGUIJsonCodec codec;

    private final ContentStateCodec contentCodec = new ContentStateCodec();

    private final Set<String> toolCalls = new HashSet<>();

    private final Set<String> toolResults = new HashSet<>();

    private long generatedIds;

    private String textMessageId;

    private AGUIRole textRole;

    private String textName;

    private String reasoningPhaseId;

    private String reasoningMessageId;

    /**
     * Creates a stateful converter for one run.
     *
     * @param runId AG-UI run correlation identifier
     * @param codec strict codec
     */
    public AGUIAgentEventConverter(String runId, AGUIJsonCodec codec) {
        this.runId = AGUIValidation.nonBlank(runId, "runId");
        this.codec = java.util.Objects.requireNonNull(codec, "codec");
    }

    /**
     * Converts one update without adding run lifecycle events.
     *
     * @param update provider-neutral update
     * @return ordered AG-UI events
     */
    public List<AGUIEvent> accept(AgentResponseUpdate update) {
        java.util.Objects.requireNonNull(update, "update");
        ArrayList<AGUIEvent> events = new ArrayList<>();
        for (Content content : update.contents()) {
            switch (content) {
                case TextContent text -> text(update, text, events);
                case FunctionCallContent call -> toolCall(update, call, events);
                case FunctionResultContent result -> toolResult(update, result, events);
                case ReasoningContent reasoning -> reasoning(update, reasoning, events);
                case ErrorContent error -> customError(update, error, events);
                case UsageContent usage ->
                    custom(
                            update,
                            CONTENT_EVENT,
                            StateValue.object(java.util.Map.of(
                                    "kind", StateValue.string("usage"),
                                    "usage", StateValue.object(usage.usage().values()))),
                            events);
                case MetadataContent metadata ->
                    custom(
                            update,
                            CONTENT_EVENT,
                            StateValue.object(java.util.Map.of(
                                    "kind", StateValue.string("metadata"),
                                    "values", StateValue.object(metadata.values()))),
                            events);
                case DataContent _, UriContent _ -> custom(update, CONTENT_EVENT, contentCodec.encode(content), events);
            }
        }
        addUpdateMetadata(update, events);
        return List.copyOf(events);
    }

    /**
     * Closes every open message and reasoning phase at end of stream.
     *
     * @return ordered end events
     */
    public List<AGUIEvent> finish() {
        ArrayList<AGUIEvent> events = new ArrayList<>();
        closeText(null, events);
        closeReasoning(null, events);
        return List.copyOf(events);
    }

    private void text(AgentResponseUpdate update, TextContent content, List<AGUIEvent> events) {
        String messageId = update.messageId();
        if (messageId == null) {
            messageId = textMessageId == null ? generated("message") : textMessageId;
        }
        AGUIRole role = textRole(update);
        if (textMessageId != null
                && (!textMessageId.equals(messageId)
                        || textRole != role
                        || !java.util.Objects.equals(textName, update.authorName()))) {
            closeText(update, events);
        }
        if (textMessageId == null) {
            textMessageId = messageId;
            textRole = role;
            textName = update.authorName();
            events.add(new AGUIEvents.TextMessageStart(messageId, role, textName, timestamp(update), null));
        }
        events.add(new AGUIEvents.TextMessageContent(textMessageId, content.text(), timestamp(update), null));
    }

    private void toolCall(AgentResponseUpdate update, FunctionCallContent call, List<AGUIEvent> events) {
        String parentMessageId =
                textMessageId != null && textMessageId.equals(update.messageId()) ? textMessageId : null;
        closeText(update, events);
        if (!toolCalls.add(call.callId())) {
            return;
        }
        events.add(new AGUIEvents.ToolCallStart(call.callId(), call.name(), parentMessageId, timestamp(update), null));
        events.add(new AGUIEvents.ToolCallArgs(
                call.callId(),
                new String(codec.encodeValue(call.arguments()), StandardCharsets.UTF_8),
                timestamp(update),
                null));
        events.add(new AGUIEvents.ToolCallEnd(call.callId(), timestamp(update), null));
        String encrypted = stateString(call.metadata().get("agui.encryptedValue"));
        if (encrypted != null) {
            events.add(new AGUIEvents.ReasoningEncryptedValue(
                    AGUIReasoningEncryptedSubtype.TOOL_CALL, call.callId(), encrypted, timestamp(update), null));
        }
    }

    private void toolResult(AgentResponseUpdate update, FunctionResultContent result, List<AGUIEvent> events) {
        closeText(update, events);
        if (!toolResults.add(result.callId())) {
            return;
        }
        String content = result.result() instanceof StateValue.StringValue string
                ? string.value()
                : new String(codec.encodeValue(result.result()), StandardCharsets.UTF_8);
        String messageId = update.messageId() == null ? generated("tool-result") : update.messageId();
        events.add(new AGUIEvents.ToolCallResult(
                messageId, result.callId(), content, AGUIRole.TOOL, timestamp(update), null));
    }

    private void reasoning(AgentResponseUpdate update, ReasoningContent content, List<AGUIEvent> events) {
        closeText(update, events);
        if (reasoningPhaseId == null) {
            reasoningPhaseId = "reasoning-" + runId;
            events.add(new AGUIEvents.ReasoningStart(reasoningPhaseId, timestamp(update), null));
        }
        String messageId = content.id();
        if (messageId == null) {
            messageId = update.messageId();
        }
        if (messageId == null) {
            messageId = reasoningMessageId == null ? generated("reasoning-message") : reasoningMessageId;
        }
        if (reasoningMessageId != null && !reasoningMessageId.equals(messageId)) {
            events.add(new AGUIEvents.ReasoningMessageEnd(reasoningMessageId, timestamp(update), null));
            reasoningMessageId = null;
        }
        if (reasoningMessageId == null) {
            reasoningMessageId = messageId;
            events.add(new AGUIEvents.ReasoningMessageStart(messageId, AGUIRole.REASONING, timestamp(update), null));
        }
        if (content.text() != null) {
            events.add(new AGUIEvents.ReasoningMessageContent(
                    reasoningMessageId, content.text(), timestamp(update), null));
        }
        if (content.protectedData() != null) {
            events.add(new AGUIEvents.ReasoningEncryptedValue(
                    AGUIReasoningEncryptedSubtype.MESSAGE,
                    reasoningMessageId,
                    content.protectedData(),
                    timestamp(update),
                    null));
        }
    }

    private void customError(AgentResponseUpdate update, ErrorContent error, List<AGUIEvent> events) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put("message", StateValue.string(error.message()));
        put(value, "code", error.errorCode());
        put(value, "details", error.details());
        custom(update, ERROR_EVENT, StateValue.object(value), events);
    }

    private void addUpdateMetadata(AgentResponseUpdate update, List<AGUIEvent> events) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        if (update.sequence() != null) {
            value.put("sequence", StateValue.integer(update.sequence()));
        }
        put(value, "agentId", update.agentId());
        put(value, "responseId", update.responseId());
        if (update.finishReason() != null) {
            value.put("finishReason", StateValue.string(update.finishReason().value()));
        }
        if (update.usage() != null) {
            value.put("usage", StateValue.object(update.usage().values()));
        }
        if (update.continuationToken() != null) {
            value.put("continuationToken", update.continuationToken());
        }
        if (!update.metadata().isEmpty()) {
            value.put("metadata", StateValue.object(update.metadata()));
        }
        if (!value.isEmpty()) {
            custom(update, UPDATE_EVENT, StateValue.object(value), events);
        }
    }

    private static void custom(AgentResponseUpdate update, String name, StateValue value, List<AGUIEvent> events) {
        events.add(new AGUIEvents.Custom(name, value, timestamp(update), null));
    }

    private void closeText(AgentResponseUpdate update, List<AGUIEvent> events) {
        if (textMessageId == null) {
            return;
        }
        events.add(new AGUIEvents.TextMessageEnd(textMessageId, timestamp(update), null));
        textMessageId = null;
        textRole = null;
        textName = null;
    }

    private void closeReasoning(AgentResponseUpdate update, List<AGUIEvent> events) {
        if (reasoningMessageId != null) {
            events.add(new AGUIEvents.ReasoningMessageEnd(reasoningMessageId, timestamp(update), null));
            reasoningMessageId = null;
        }
        if (reasoningPhaseId != null) {
            events.add(new AGUIEvents.ReasoningEnd(reasoningPhaseId, timestamp(update), null));
            reasoningPhaseId = null;
        }
    }

    private String generated(String kind) {
        return kind + "-" + runId + "-" + generatedIds++;
    }

    private static AGUIRole textRole(AgentResponseUpdate update) {
        if (update.role() == null) {
            return AGUIRole.ASSISTANT;
        }
        return switch (update.role().value()) {
            case "developer" -> AGUIRole.DEVELOPER;
            case "system" -> AGUIRole.SYSTEM;
            case "assistant" -> AGUIRole.ASSISTANT;
            case "user" -> AGUIRole.USER;
            default ->
                throw new AGUIProtocolException(
                        AGUIErrorCode.INVALID_MODEL, "Agent text update has a non-text AG-UI role.");
        };
    }

    private static BigDecimal timestamp(AgentResponseUpdate update) {
        return update == null || update.createdAt() == null
                ? null
                : BigDecimal.valueOf(update.createdAt().toEpochMilli());
    }

    private static void put(Map<String, StateValue> target, String name, String value) {
        if (value != null) {
            target.put(name, StateValue.string(value));
        }
    }

    private static String stateString(StateValue value) {
        return value instanceof StateValue.StringValue string ? string.value() : null;
    }
}
