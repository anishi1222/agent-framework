// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.protocols.agui.AGUIEvent;
import com.microsoft.agents.protocols.agui.AGUIEvents;
import com.microsoft.agents.protocols.agui.AGUIJsonPatch;
import com.microsoft.agents.protocols.agui.AGUIMessage;
import com.microsoft.agents.protocols.agui.AGUIMessages;
import com.microsoft.agents.protocols.agui.AGUIReasoningEncryptedSubtype;
import com.microsoft.agents.protocols.agui.AGUIRole;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

final class AGUIThreadAccumulator {
    private final LinkedHashMap<String, AGUIMessage> messages = new LinkedHashMap<>();

    private final LinkedHashMap<String, TextState> text = new LinkedHashMap<>();

    private final LinkedHashMap<String, ToolState> tools = new LinkedHashMap<>();

    private final LinkedHashMap<String, TextState> reasoning = new LinkedHashMap<>();

    private StateValue state;

    AGUIThreadAccumulator(List<AGUIMessage> initialMessages, StateValue initialState) {
        initialMessages.forEach(message -> messages.put(message.id(), message));
        state = initialState;
    }

    @SuppressWarnings("removal")
    void accept(AGUIEvent event) {
        switch (event) {
            case AGUIEvents.TextMessageStart start ->
                text.put(start.messageId(), new TextState(start.role(), start.name(), new StringBuilder(), null));
            case AGUIEvents.TextMessageContent content ->
                requireText(text, content.messageId()).content().append(content.delta());
            case AGUIEvents.TextMessageEnd end -> finishText(end.messageId());
            case AGUIEvents.ToolCallStart start ->
                tools.put(
                        start.toolCallId(),
                        new ToolState(start.toolCallName(), start.parentMessageId(), new StringBuilder(), null));
            case AGUIEvents.ToolCallArgs args ->
                requireTool(args.toolCallId()).arguments().append(args.delta());
            case AGUIEvents.ToolCallEnd end -> finishTool(end.toolCallId());
            case AGUIEvents.ToolCallResult result ->
                messages.put(
                        result.messageId(),
                        new AGUIMessages.Tool(result.messageId(), result.content(), result.toolCallId(), null, null));
            case AGUIEvents.StateSnapshot snapshot -> state = snapshot.snapshot();
            case AGUIEvents.StateDelta delta -> state = AGUIJsonPatch.apply(state, delta.delta());
            case AGUIEvents.MessagesSnapshot snapshot -> replaceMessages(snapshot.messages());
            case AGUIEvents.ActivitySnapshot activity -> activitySnapshot(activity);
            case AGUIEvents.ActivityDelta activity -> activityDelta(activity);
            case AGUIEvents.ReasoningMessageStart start ->
                reasoning.put(start.messageId(), new TextState(AGUIRole.REASONING, null, new StringBuilder(), null));
            case AGUIEvents.ReasoningMessageContent content ->
                requireText(reasoning, content.messageId()).content().append(content.delta());
            case AGUIEvents.ReasoningMessageEnd end -> finishReasoning(end.messageId());
            case AGUIEvents.ReasoningEncryptedValue encrypted -> encrypted(encrypted);
            case AGUIEvents.RunStarted _,
                    AGUIEvents.RunFinished _,
                    AGUIEvents.RunError _,
                    AGUIEvents.StepStarted _,
                    AGUIEvents.StepFinished _,
                    AGUIEvents.Raw _,
                    AGUIEvents.Custom _,
                    AGUIEvents.ReasoningStart _,
                    AGUIEvents.ReasoningEnd _,
                    AGUIEvents.ThinkingStart _,
                    AGUIEvents.ThinkingEnd _,
                    AGUIEvents.ThinkingTextMessageStart _,
                    AGUIEvents.ThinkingTextMessageContent _,
                    AGUIEvents.ThinkingTextMessageEnd _ -> {
                // These events do not mutate synchronized transcript or state.
            }
            case AGUIEvents.TextMessageChunk _, AGUIEvents.ToolCallChunk _, AGUIEvents.ReasoningMessageChunk _ ->
                throw new IllegalArgumentException("Convenience events must be normalized.");
        }
    }

    List<AGUIMessage> messages() {
        return List.copyOf(messages.values());
    }

    StateValue state() {
        return state;
    }

    boolean hasToolCall(String toolCallId) {
        return messages.values().stream()
                .filter(AGUIMessages.Assistant.class::isInstance)
                .map(AGUIMessages.Assistant.class::cast)
                .flatMap(message -> message.toolCalls().stream())
                .anyMatch(call -> call.id().equals(toolCallId));
    }

    private void finishText(String messageId) {
        TextState completed = text.remove(messageId);
        if (completed == null) {
            throw new IllegalArgumentException("Text message is not open.");
        }
        AGUIMessage message =
                switch (completed.role()) {
                    case DEVELOPER ->
                        new AGUIMessages.Developer(
                                messageId,
                                completed.content().toString(),
                                completed.name(),
                                completed.encryptedValue());
                    case SYSTEM ->
                        new AGUIMessages.System(
                                messageId,
                                completed.content().toString(),
                                completed.name(),
                                completed.encryptedValue());
                    case ASSISTANT ->
                        new AGUIMessages.Assistant(
                                messageId,
                                completed.content().toString(),
                                completed.name(),
                                completed.encryptedValue(),
                                existingToolCalls(messageId));
                    case USER ->
                        new AGUIMessages.User(
                                messageId,
                                new AGUIMessages.TextUserContent(
                                        completed.content().toString()),
                                completed.name(),
                                completed.encryptedValue());
                    case TOOL, ACTIVITY, REASONING ->
                        throw new IllegalArgumentException("Text message role is not valid.");
                };
        messages.put(messageId, message);
    }

    private void finishTool(String toolCallId) {
        ToolState completed = tools.remove(toolCallId);
        if (completed == null) {
            throw new IllegalArgumentException("Tool call is not open.");
        }
        AGUIMessages.ToolCall call = new AGUIMessages.ToolCall(
                toolCallId,
                new AGUIMessages.FunctionCall(
                        completed.name(), completed.arguments().toString()),
                completed.encryptedValue());
        String parentId =
                completed.parentMessageId() == null ? "tool-parent-" + toolCallId : completed.parentMessageId();
        AGUIMessage existing = messages.get(parentId);
        ArrayList<AGUIMessages.ToolCall> calls = new ArrayList<>();
        String content = null;
        String name = null;
        String encrypted = null;
        if (existing instanceof AGUIMessages.Assistant assistant) {
            calls.addAll(assistant.toolCalls());
            content = assistant.content();
            name = assistant.name();
            encrypted = assistant.encryptedValue();
        }
        calls.removeIf(value -> value.id().equals(toolCallId));
        calls.add(call);
        messages.put(parentId, new AGUIMessages.Assistant(parentId, content, name, encrypted, calls));
    }

    private void finishReasoning(String messageId) {
        TextState completed = reasoning.remove(messageId);
        if (completed == null) {
            throw new IllegalArgumentException("Reasoning message is not open.");
        }
        messages.put(
                messageId,
                new AGUIMessages.Reasoning(messageId, completed.content().toString(), completed.encryptedValue()));
    }

    private void replaceMessages(List<AGUIMessage> snapshot) {
        boolean hasActivity = snapshot.stream().anyMatch(AGUIMessages.Activity.class::isInstance);
        boolean hasReasoning = snapshot.stream().anyMatch(AGUIMessages.Reasoning.class::isInstance);
        List<AGUIMessage> retained = messages.values().stream()
                .filter(message -> !hasActivity && message instanceof AGUIMessages.Activity
                        || !hasReasoning && message instanceof AGUIMessages.Reasoning)
                .toList();
        messages.clear();
        snapshot.forEach(message -> messages.put(message.id(), message));
        retained.forEach(message -> messages.putIfAbsent(message.id(), message));
    }

    private void activitySnapshot(AGUIEvents.ActivitySnapshot event) {
        if (!event.replace() && messages.containsKey(event.messageId())) {
            return;
        }
        messages.put(
                event.messageId(), new AGUIMessages.Activity(event.messageId(), event.activityType(), event.content()));
    }

    private void activityDelta(AGUIEvents.ActivityDelta event) {
        AGUIMessage existing = messages.get(event.messageId());
        if (!(existing instanceof AGUIMessages.Activity activity)
                || !activity.activityType().equals(event.activityType())) {
            throw new IllegalArgumentException("Activity delta has no matching snapshot.");
        }
        StateValue patched = AGUIJsonPatch.apply(activity.content(), event.patch());
        if (!(patched instanceof StateValue.ObjectValue content)) {
            throw new IllegalArgumentException("Activity delta must retain object content.");
        }
        messages.put(event.messageId(), new AGUIMessages.Activity(event.messageId(), event.activityType(), content));
    }

    private void encrypted(AGUIEvents.ReasoningEncryptedValue event) {
        if (event.subtype() == AGUIReasoningEncryptedSubtype.MESSAGE) {
            TextState open = reasoning.get(event.entityId());
            if (open != null) {
                reasoning.put(event.entityId(), open.withEncryptedValue(event.encryptedValue()));
                return;
            }
            AGUIMessage existing = messages.get(event.entityId());
            if (existing instanceof AGUIMessages.Reasoning value) {
                messages.put(
                        value.id(), new AGUIMessages.Reasoning(value.id(), value.content(), event.encryptedValue()));
            } else if (existing instanceof AGUIMessages.Assistant value) {
                messages.put(
                        value.id(),
                        new AGUIMessages.Assistant(
                                value.id(), value.content(), value.name(), event.encryptedValue(), value.toolCalls()));
            }
            return;
        }
        tools.computeIfPresent(event.entityId(), (ignored, value) -> value.withEncryptedValue(event.encryptedValue()));
    }

    private List<AGUIMessages.ToolCall> existingToolCalls(String messageId) {
        AGUIMessage existing = messages.get(messageId);
        return existing instanceof AGUIMessages.Assistant assistant ? assistant.toolCalls() : List.of();
    }

    private static TextState requireText(LinkedHashMap<String, TextState> values, String id) {
        TextState value = values.get(id);
        if (value == null) {
            throw new IllegalArgumentException("Message content targets no open message.");
        }
        return value;
    }

    private ToolState requireTool(String id) {
        ToolState value = tools.get(id);
        if (value == null) {
            throw new IllegalArgumentException("Tool args target no open tool call.");
        }
        return value;
    }

    private record TextState(AGUIRole role, String name, StringBuilder content, String encryptedValue) {
        private TextState withEncryptedValue(String replacement) {
            return new TextState(role, name, content, replacement);
        }
    }

    private record ToolState(String name, String parentMessageId, StringBuilder arguments, String encryptedValue) {
        private ToolState withEncryptedValue(String replacement) {
            return new ToolState(name, parentMessageId, arguments, replacement);
        }
    }
}
