// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import com.microsoft.agents.core.StateValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AGUIWireValues {
    private static final Set<String> RUN_INPUT_FIELDS = Set.of(
            "threadId", "runId", "parentRunId", "state", "messages", "tools", "context", "forwardedProps", "resume");

    private AGUIWireValues() {}

    @SuppressWarnings("removal")
    static AGUIEvent decodeEvent(StateValue.ObjectValue object) {
        AGUIEventType type = AGUIEventType.fromValue(requiredString(object, "type"));
        BigDecimal timestamp = optionalNumber(object, "timestamp");
        StateValue rawEvent = optionalValue(object, "rawEvent");
        AGUIEvent event =
                switch (type) {
                    case TEXT_MESSAGE_START ->
                        new AGUIEvents.TextMessageStart(
                                requiredString(object, "messageId"),
                                optionalRole(object, "role", AGUIRole.ASSISTANT),
                                optionalString(object, "name"),
                                timestamp,
                                rawEvent);
                    case TEXT_MESSAGE_CONTENT ->
                        new AGUIEvents.TextMessageContent(
                                requiredString(object, "messageId"),
                                requiredStringAllowEmpty(object, "delta"),
                                timestamp,
                                rawEvent);
                    case TEXT_MESSAGE_END ->
                        new AGUIEvents.TextMessageEnd(requiredString(object, "messageId"), timestamp, rawEvent);
                    case TEXT_MESSAGE_CHUNK ->
                        new AGUIEvents.TextMessageChunk(
                                optionalString(object, "messageId"),
                                optionalRole(object, "role", null),
                                optionalStringAllowEmpty(object, "delta"),
                                optionalString(object, "name"),
                                timestamp,
                                rawEvent);
                    case TOOL_CALL_START ->
                        new AGUIEvents.ToolCallStart(
                                requiredString(object, "toolCallId"),
                                requiredString(object, "toolCallName"),
                                optionalString(object, "parentMessageId"),
                                timestamp,
                                rawEvent);
                    case TOOL_CALL_ARGS ->
                        new AGUIEvents.ToolCallArgs(
                                requiredString(object, "toolCallId"),
                                requiredStringAllowEmpty(object, "delta"),
                                timestamp,
                                rawEvent);
                    case TOOL_CALL_END ->
                        new AGUIEvents.ToolCallEnd(requiredString(object, "toolCallId"), timestamp, rawEvent);
                    case TOOL_CALL_CHUNK ->
                        new AGUIEvents.ToolCallChunk(
                                optionalString(object, "toolCallId"),
                                optionalString(object, "toolCallName"),
                                optionalString(object, "parentMessageId"),
                                optionalStringAllowEmpty(object, "delta"),
                                timestamp,
                                rawEvent);
                    case TOOL_CALL_RESULT ->
                        new AGUIEvents.ToolCallResult(
                                requiredString(object, "messageId"),
                                requiredString(object, "toolCallId"),
                                requiredStringAllowEmpty(object, "content"),
                                optionalLiteralRole(object, "role", AGUIRole.TOOL),
                                timestamp,
                                rawEvent);
                    case THINKING_START ->
                        new AGUIEvents.ThinkingStart(optionalString(object, "title"), timestamp, rawEvent);
                    case THINKING_END -> new AGUIEvents.ThinkingEnd(timestamp, rawEvent);
                    case THINKING_TEXT_MESSAGE_START -> new AGUIEvents.ThinkingTextMessageStart(timestamp, rawEvent);
                    case THINKING_TEXT_MESSAGE_CONTENT ->
                        new AGUIEvents.ThinkingTextMessageContent(
                                requiredStringAllowEmpty(object, "delta"), timestamp, rawEvent);
                    case THINKING_TEXT_MESSAGE_END -> new AGUIEvents.ThinkingTextMessageEnd(timestamp, rawEvent);
                    case STATE_SNAPSHOT ->
                        new AGUIEvents.StateSnapshot(requiredValue(object, "snapshot"), timestamp, rawEvent);
                    case STATE_DELTA ->
                        new AGUIEvents.StateDelta(decodePatch(requiredArray(object, "delta")), timestamp, rawEvent);
                    case MESSAGES_SNAPSHOT ->
                        new AGUIEvents.MessagesSnapshot(
                                requiredArray(object, "messages").stream()
                                        .map(value -> decodeMessage(requireObject(value, "message")))
                                        .toList(),
                                timestamp,
                                rawEvent);
                    case ACTIVITY_SNAPSHOT ->
                        new AGUIEvents.ActivitySnapshot(
                                requiredString(object, "messageId"),
                                requiredString(object, "activityType"),
                                requireObject(requiredValue(object, "content"), "content"),
                                optionalBoolean(object, "replace", true),
                                timestamp,
                                rawEvent);
                    case ACTIVITY_DELTA ->
                        new AGUIEvents.ActivityDelta(
                                requiredString(object, "messageId"),
                                requiredString(object, "activityType"),
                                decodePatch(requiredArray(object, "patch")),
                                timestamp,
                                rawEvent);
                    case RAW ->
                        new AGUIEvents.Raw(
                                requiredValue(object, "event"), optionalString(object, "source"), timestamp, rawEvent);
                    case CUSTOM ->
                        new AGUIEvents.Custom(
                                requiredString(object, "name"), requiredValue(object, "value"), timestamp, rawEvent);
                    case RUN_STARTED ->
                        new AGUIEvents.RunStarted(
                                requiredString(object, "threadId"),
                                requiredString(object, "runId"),
                                optionalString(object, "parentRunId"),
                                optionalObject(object, "input") == null
                                        ? null
                                        : decodeRunAgentInput(optionalObject(object, "input")),
                                timestamp,
                                rawEvent);
                    case RUN_FINISHED ->
                        new AGUIEvents.RunFinished(
                                requiredString(object, "threadId"),
                                requiredString(object, "runId"),
                                optionalValue(object, "result"),
                                decodeOptionalOutcome(object),
                                timestamp,
                                rawEvent);
                    case RUN_ERROR ->
                        new AGUIEvents.RunError(
                                requiredString(object, "message"), optionalString(object, "code"), timestamp, rawEvent);
                    case STEP_STARTED ->
                        new AGUIEvents.StepStarted(requiredString(object, "stepName"), timestamp, rawEvent);
                    case STEP_FINISHED ->
                        new AGUIEvents.StepFinished(requiredString(object, "stepName"), timestamp, rawEvent);
                    case REASONING_START ->
                        new AGUIEvents.ReasoningStart(requiredString(object, "messageId"), timestamp, rawEvent);
                    case REASONING_MESSAGE_START ->
                        new AGUIEvents.ReasoningMessageStart(
                                requiredString(object, "messageId"),
                                requiredLiteralRole(object, "role", AGUIRole.REASONING),
                                timestamp,
                                rawEvent);
                    case REASONING_MESSAGE_CONTENT ->
                        new AGUIEvents.ReasoningMessageContent(
                                requiredString(object, "messageId"),
                                requiredStringAllowEmpty(object, "delta"),
                                timestamp,
                                rawEvent);
                    case REASONING_MESSAGE_END ->
                        new AGUIEvents.ReasoningMessageEnd(requiredString(object, "messageId"), timestamp, rawEvent);
                    case REASONING_MESSAGE_CHUNK ->
                        new AGUIEvents.ReasoningMessageChunk(
                                optionalString(object, "messageId"),
                                optionalStringAllowEmpty(object, "delta"),
                                timestamp,
                                rawEvent);
                    case REASONING_END ->
                        new AGUIEvents.ReasoningEnd(requiredString(object, "messageId"), timestamp, rawEvent);
                    case REASONING_ENCRYPTED_VALUE ->
                        new AGUIEvents.ReasoningEncryptedValue(
                                AGUIReasoningEncryptedSubtype.fromValue(requiredString(object, "subtype")),
                                requiredString(object, "entityId"),
                                requiredString(object, "encryptedValue"),
                                timestamp,
                                rawEvent);
                };
        LinkedHashMap<String, StateValue> additional = new LinkedHashMap<>(object.values());
        encodeEvent(event).values().keySet().forEach(additional::remove);
        AGUIEventMetadata.attach(event, additional);
        return event;
    }

    @SuppressWarnings("removal")
    static StateValue.ObjectValue encodeEvent(AGUIEvent event) {
        LinkedHashMap<String, StateValue> value = base(event);
        switch (event) {
            case AGUIEvents.TextMessageStart text -> {
                value.put("messageId", string(text.messageId()));
                value.put("role", string(text.role().value()));
                putString(value, "name", text.name());
            }
            case AGUIEvents.TextMessageContent text -> {
                value.put("messageId", string(text.messageId()));
                value.put("delta", string(text.delta()));
            }
            case AGUIEvents.TextMessageEnd text -> value.put("messageId", string(text.messageId()));
            case AGUIEvents.TextMessageChunk text -> {
                putString(value, "messageId", text.messageId());
                if (text.role() != null) {
                    value.put("role", string(text.role().value()));
                }
                putStringAllowEmpty(value, "delta", text.delta());
                putString(value, "name", text.name());
            }
            case AGUIEvents.ToolCallStart tool -> {
                value.put("toolCallId", string(tool.toolCallId()));
                value.put("toolCallName", string(tool.toolCallName()));
                putString(value, "parentMessageId", tool.parentMessageId());
            }
            case AGUIEvents.ToolCallArgs tool -> {
                value.put("toolCallId", string(tool.toolCallId()));
                value.put("delta", string(tool.delta()));
            }
            case AGUIEvents.ToolCallEnd tool -> value.put("toolCallId", string(tool.toolCallId()));
            case AGUIEvents.ToolCallChunk tool -> {
                putString(value, "toolCallId", tool.toolCallId());
                putString(value, "toolCallName", tool.toolCallName());
                putString(value, "parentMessageId", tool.parentMessageId());
                putStringAllowEmpty(value, "delta", tool.delta());
            }
            case AGUIEvents.ToolCallResult tool -> {
                value.put("messageId", string(tool.messageId()));
                value.put("toolCallId", string(tool.toolCallId()));
                value.put("content", string(tool.content()));
                if (tool.role() != null) {
                    value.put("role", string(tool.role().value()));
                }
            }
            case AGUIEvents.ThinkingStart thinking -> putString(value, "title", thinking.title());
            case AGUIEvents.ThinkingEnd _,
                    AGUIEvents.ThinkingTextMessageStart _,
                    AGUIEvents.ThinkingTextMessageEnd _ -> {
                // Base fields only.
            }
            case AGUIEvents.ThinkingTextMessageContent thinking -> value.put("delta", string(thinking.delta()));
            case AGUIEvents.StateSnapshot state -> value.put("snapshot", state.snapshot());
            case AGUIEvents.StateDelta state -> value.put("delta", encodePatch(state.delta()));
            case AGUIEvents.MessagesSnapshot messages ->
                value.put(
                        "messages",
                        StateValue.array(messages.messages().stream()
                                .map(AGUIWireValues::encodeMessage)
                                .toList()));
            case AGUIEvents.ActivitySnapshot activity -> {
                value.put("messageId", string(activity.messageId()));
                value.put("activityType", string(activity.activityType()));
                value.put("content", activity.content());
                value.put("replace", StateValue.bool(activity.replace()));
            }
            case AGUIEvents.ActivityDelta activity -> {
                value.put("messageId", string(activity.messageId()));
                value.put("activityType", string(activity.activityType()));
                value.put("patch", encodePatch(activity.patch()));
            }
            case AGUIEvents.Raw raw -> {
                value.put("event", raw.event());
                putString(value, "source", raw.source());
            }
            case AGUIEvents.Custom custom -> {
                value.put("name", string(custom.name()));
                value.put("value", custom.value());
            }
            case AGUIEvents.RunStarted run -> {
                value.put("threadId", string(run.threadId()));
                value.put("runId", string(run.runId()));
                putString(value, "parentRunId", run.parentRunId());
                if (run.input() != null) {
                    value.put("input", encodeRunAgentInput(run.input()));
                }
            }
            case AGUIEvents.RunFinished run -> {
                value.put("threadId", string(run.threadId()));
                value.put("runId", string(run.runId()));
                if (run.result() != null) {
                    value.put("result", run.result());
                }
                if (run.outcome() != null) {
                    value.put("outcome", encodeOutcome(run.outcome()));
                }
            }
            case AGUIEvents.RunError error -> {
                value.put("message", string(error.message()));
                putString(value, "code", error.code());
            }
            case AGUIEvents.StepStarted step -> value.put("stepName", string(step.stepName()));
            case AGUIEvents.StepFinished step -> value.put("stepName", string(step.stepName()));
            case AGUIEvents.ReasoningStart reasoning -> value.put("messageId", string(reasoning.messageId()));
            case AGUIEvents.ReasoningMessageStart reasoning -> {
                value.put("messageId", string(reasoning.messageId()));
                value.put("role", string(reasoning.role().value()));
            }
            case AGUIEvents.ReasoningMessageContent reasoning -> {
                value.put("messageId", string(reasoning.messageId()));
                value.put("delta", string(reasoning.delta()));
            }
            case AGUIEvents.ReasoningMessageEnd reasoning -> value.put("messageId", string(reasoning.messageId()));
            case AGUIEvents.ReasoningMessageChunk reasoning -> {
                putString(value, "messageId", reasoning.messageId());
                putStringAllowEmpty(value, "delta", reasoning.delta());
            }
            case AGUIEvents.ReasoningEnd reasoning -> value.put("messageId", string(reasoning.messageId()));
            case AGUIEvents.ReasoningEncryptedValue reasoning -> {
                value.put("subtype", string(reasoning.subtype().value()));
                value.put("entityId", string(reasoning.entityId()));
                value.put("encryptedValue", string(reasoning.encryptedValue()));
            }
        }
        event.additionalProperties().forEach((name, additional) -> {
            if (value.putIfAbsent(name, additional) != null) {
                throw invalid("Additional event member '" + name + "' collides with a recognized member.");
            }
        });
        return StateValue.object(value);
    }

    static RunAgentInput decodeRunAgentInput(StateValue.ObjectValue object) {
        rejectUnknown(object, RUN_INPUT_FIELDS, "RunAgentInput");
        return new RunAgentInput(
                requiredString(object, "threadId"),
                requiredString(object, "runId"),
                optionalString(object, "parentRunId"),
                requiredValue(object, "state"),
                requiredArray(object, "messages").stream()
                        .map(value -> decodeMessage(requireObject(value, "message")))
                        .toList(),
                requiredArray(object, "tools").stream()
                        .map(value -> decodeTool(requireObject(value, "tool")))
                        .toList(),
                requiredArray(object, "context").stream()
                        .map(value -> decodeContext(requireObject(value, "context")))
                        .toList(),
                requiredValue(object, "forwardedProps"),
                optionalArray(object, "resume").stream()
                        .map(value -> decodeResume(requireObject(value, "resume entry")))
                        .toList());
    }

    static StateValue.ObjectValue encodeRunAgentInput(RunAgentInput input) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put("threadId", string(input.threadId()));
        value.put("runId", string(input.runId()));
        putString(value, "parentRunId", input.parentRunId());
        value.put("state", input.state());
        value.put(
                "messages",
                StateValue.array(input.messages().stream()
                        .map(AGUIWireValues::encodeMessage)
                        .toList()));
        value.put(
                "tools",
                StateValue.array(
                        input.tools().stream().map(AGUIWireValues::encodeTool).toList()));
        value.put(
                "context",
                StateValue.array(input.context().stream()
                        .map(AGUIWireValues::encodeContext)
                        .toList()));
        value.put("forwardedProps", input.forwardedProps());
        if (!input.resume().isEmpty()) {
            value.put(
                    "resume",
                    StateValue.array(input.resume().stream()
                            .map(AGUIWireValues::encodeResume)
                            .toList()));
        }
        return StateValue.object(value);
    }

    private static AGUIRunFinishedOutcome decodeOptionalOutcome(StateValue.ObjectValue event) {
        StateValue value = event.values().get("outcome");
        if (value == null || value == StateValue.NullValue.INSTANCE) {
            return null;
        }
        StateValue.ObjectValue object = requireObject(value, "outcome");
        String type = requiredString(object, "type");
        return switch (type) {
            case "success" -> {
                rejectUnknown(object, Set.of("type"), "success outcome");
                yield new AGUIRunOutcomes.Success();
            }
            case "interrupt" -> {
                rejectUnknown(object, Set.of("type", "interrupts"), "interrupt outcome");
                yield new AGUIRunOutcomes.Interrupt(requiredArray(object, "interrupts").stream()
                        .map(item -> decodeInterrupt(requireObject(item, "interrupt")))
                        .toList());
            }
            default -> throw invalid("Unknown RUN_FINISHED outcome type.");
        };
    }

    private static StateValue.ObjectValue encodeOutcome(AGUIRunFinishedOutcome outcome) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put("type", string(outcome.type()));
        if (outcome instanceof AGUIRunOutcomes.Interrupt interrupt) {
            value.put(
                    "interrupts",
                    StateValue.array(interrupt.interrupts().stream()
                            .map(AGUIWireValues::encodeInterrupt)
                            .toList()));
        }
        return StateValue.object(value);
    }

    private static List<AGUIJsonPatchOperation> decodePatch(List<StateValue> values) {
        return values.stream()
                .map(value -> decodePatchOperation(requireObject(value, "patch operation")))
                .toList();
    }

    private static AGUIJsonPatchOperation decodePatchOperation(StateValue.ObjectValue object) {
        rejectUnknown(object, Set.of("op", "path", "from", "value"), "JSON Patch operation");
        return new AGUIJsonPatchOperation(
                AGUIJsonPatchOperation.Operation.fromValue(requiredString(object, "op")),
                requiredStringAllowEmpty(object, "path"),
                optionalStringAllowEmpty(object, "from"),
                optionalValue(object, "value"));
    }

    private static StateValue.ArrayValue encodePatch(List<AGUIJsonPatchOperation> operations) {
        return StateValue.array(operations.stream()
                .map(operation -> {
                    LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
                    value.put("op", string(operation.op().value()));
                    value.put("path", string(operation.path()));
                    putStringAllowEmpty(value, "from", operation.from());
                    if (operation.value() != null) {
                        value.put("value", operation.value());
                    }
                    return StateValue.object(value);
                })
                .toList());
    }

    private static LinkedHashMap<String, StateValue> base(AGUIEvent event) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put("type", string(event.type().name()));
        if (event.timestamp() != null) {
            value.put("timestamp", StateValue.number(event.timestamp()));
        }
        if (event.rawEvent() != null) {
            value.put("rawEvent", event.rawEvent());
        }
        return value;
    }

    private static StateValue.StringValue string(String value) {
        return StateValue.string(value);
    }

    private static AGUIProtocolException invalid(String message) {
        return new AGUIProtocolException(AGUIErrorCode.INVALID_MODEL, message);
    }

    private static void rejectUnknown(StateValue.ObjectValue object, Set<String> allowed, String description) {
        java.util.TreeSet<String> unknown =
                new java.util.TreeSet<>(object.values().keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw invalid(description
                    + " contains unsupported members "
                    + unknown
                    + "; remove them or upgrade to a version that declares support.");
        }
    }

    private static StateValue requiredValue(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null) {
            throw invalid("Required member '" + name + "' is absent.");
        }
        return value;
    }

    private static StateValue optionalValue(StateValue.ObjectValue object, String name) {
        return object.values().get(name);
    }

    private static String requiredString(StateValue.ObjectValue object, String name) {
        String value = requiredStringAllowEmpty(object, name);
        return AGUIValidation.nonBlank(value, name);
    }

    private static String requiredStringAllowEmpty(StateValue.ObjectValue object, String name) {
        StateValue value = requiredValue(object, name);
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw invalid("Member '" + name + "' must be a string.");
    }

    private static String optionalString(StateValue.ObjectValue object, String name) {
        String value = optionalStringAllowEmpty(object, name);
        return value == null ? null : AGUIValidation.nonBlank(value, name);
    }

    private static String optionalStringAllowEmpty(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw invalid("Member '" + name + "' must be a string when present.");
    }

    private static BigDecimal optionalNumber(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof StateValue.NumberValue number) {
            return number.value();
        }
        throw invalid("Member '" + name + "' must be a number when present.");
    }

    private static boolean optionalBoolean(StateValue.ObjectValue object, String name, boolean defaultValue) {
        StateValue value = object.values().get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof StateValue.BooleanValue bool) {
            return bool.value();
        }
        throw invalid("Member '" + name + "' must be Boolean when present.");
    }

    private static List<StateValue> requiredArray(StateValue.ObjectValue object, String name) {
        StateValue value = requiredValue(object, name);
        if (value instanceof StateValue.ArrayValue array) {
            return array.values();
        }
        throw invalid("Member '" + name + "' must be an array.");
    }

    private static List<StateValue> optionalArray(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null) {
            return List.of();
        }
        if (value instanceof StateValue.ArrayValue array) {
            return array.values();
        }
        throw invalid("Member '" + name + "' must be an array when present.");
    }

    private static StateValue.ObjectValue requireObject(StateValue value, String name) {
        if (value instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw invalid(name + " must be an object.");
    }

    private static StateValue.ObjectValue optionalObject(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        return value == null ? null : requireObject(value, name);
    }

    private static AGUIRole optionalRole(StateValue.ObjectValue object, String name, AGUIRole defaultValue) {
        String value = optionalString(object, name);
        return value == null ? defaultValue : AGUIRole.fromValue(value);
    }

    private static AGUIRole requiredLiteralRole(StateValue.ObjectValue object, String name, AGUIRole expected) {
        AGUIRole role = AGUIRole.fromValue(requiredString(object, name));
        if (role != expected) {
            throw invalid("Member '" + name + "' has an invalid role.");
        }
        return role;
    }

    private static AGUIRole optionalLiteralRole(StateValue.ObjectValue object, String name, AGUIRole expected) {
        String value = optionalString(object, name);
        return value == null ? null : requiredLiteralRole(object, name, expected);
    }

    private static void putString(Map<String, StateValue> target, String name, String value) {
        if (value != null) {
            target.put(name, string(value));
        }
    }

    private static void putStringAllowEmpty(Map<String, StateValue> target, String name, String value) {
        if (value != null) {
            target.put(name, string(value));
        }
    }

    private static AGUIMessage decodeMessage(StateValue.ObjectValue object) {
        AGUIRole role = AGUIRole.fromValue(requiredString(object, "role"));
        return switch (role) {
            case DEVELOPER -> {
                rejectUnknown(object, Set.of("id", "role", "content", "name", "encryptedValue"), "developer message");
                yield new AGUIMessages.Developer(
                        requiredString(object, "id"),
                        requiredStringAllowEmpty(object, "content"),
                        optionalString(object, "name"),
                        optionalString(object, "encryptedValue"));
            }
            case SYSTEM -> {
                rejectUnknown(object, Set.of("id", "role", "content", "name", "encryptedValue"), "system message");
                yield new AGUIMessages.System(
                        requiredString(object, "id"),
                        requiredStringAllowEmpty(object, "content"),
                        optionalString(object, "name"),
                        optionalString(object, "encryptedValue"));
            }
            case ASSISTANT -> {
                rejectUnknown(
                        object,
                        Set.of("id", "role", "content", "name", "encryptedValue", "toolCalls"),
                        "assistant message");
                yield new AGUIMessages.Assistant(
                        requiredString(object, "id"),
                        optionalStringAllowEmpty(object, "content"),
                        optionalString(object, "name"),
                        optionalString(object, "encryptedValue"),
                        optionalArray(object, "toolCalls").stream()
                                .map(value -> decodeToolCall(requireObject(value, "tool call")))
                                .toList());
            }
            case USER -> {
                rejectUnknown(object, Set.of("id", "role", "content", "name", "encryptedValue"), "user message");
                yield new AGUIMessages.User(
                        requiredString(object, "id"),
                        decodeUserContent(requiredValue(object, "content")),
                        optionalString(object, "name"),
                        optionalString(object, "encryptedValue"));
            }
            case TOOL -> {
                rejectUnknown(
                        object,
                        Set.of("id", "role", "content", "toolCallId", "error", "encryptedValue"),
                        "tool message");
                yield new AGUIMessages.Tool(
                        requiredString(object, "id"),
                        requiredStringAllowEmpty(object, "content"),
                        requiredString(object, "toolCallId"),
                        optionalString(object, "error"),
                        optionalString(object, "encryptedValue"));
            }
            case ACTIVITY -> {
                rejectUnknown(object, Set.of("id", "role", "activityType", "content"), "activity message");
                yield new AGUIMessages.Activity(
                        requiredString(object, "id"),
                        requiredString(object, "activityType"),
                        requireObject(requiredValue(object, "content"), "activity content"));
            }
            case REASONING -> {
                rejectUnknown(object, Set.of("id", "role", "content", "encryptedValue"), "reasoning message");
                yield new AGUIMessages.Reasoning(
                        requiredString(object, "id"),
                        requiredStringAllowEmpty(object, "content"),
                        optionalString(object, "encryptedValue"));
            }
        };
    }

    private static StateValue.ObjectValue encodeMessage(AGUIMessage message) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put("id", string(message.id()));
        value.put("role", string(message.role().value()));
        switch (message) {
            case AGUIMessages.Developer developer -> {
                value.put("content", string(developer.content()));
                putString(value, "name", developer.name());
                putString(value, "encryptedValue", developer.encryptedValue());
            }
            case AGUIMessages.System system -> {
                value.put("content", string(system.content()));
                putString(value, "name", system.name());
                putString(value, "encryptedValue", system.encryptedValue());
            }
            case AGUIMessages.Assistant assistant -> {
                putStringAllowEmpty(value, "content", assistant.content());
                putString(value, "name", assistant.name());
                putString(value, "encryptedValue", assistant.encryptedValue());
                if (!assistant.toolCalls().isEmpty()) {
                    value.put(
                            "toolCalls",
                            StateValue.array(assistant.toolCalls().stream()
                                    .map(AGUIWireValues::encodeToolCall)
                                    .toList()));
                }
            }
            case AGUIMessages.User user -> {
                value.put("content", encodeUserContent(user.content()));
                putString(value, "name", user.name());
                putString(value, "encryptedValue", user.encryptedValue());
            }
            case AGUIMessages.Tool tool -> {
                value.put("content", string(tool.content()));
                value.put("toolCallId", string(tool.toolCallId()));
                putString(value, "error", tool.error());
                putString(value, "encryptedValue", tool.encryptedValue());
            }
            case AGUIMessages.Activity activity -> {
                value.put("activityType", string(activity.activityType()));
                value.put("content", activity.content());
            }
            case AGUIMessages.Reasoning reasoning -> {
                value.put("content", string(reasoning.content()));
                putString(value, "encryptedValue", reasoning.encryptedValue());
            }
        }
        return StateValue.object(value);
    }

    private static AGUIMessages.ToolCall decodeToolCall(StateValue.ObjectValue object) {
        rejectUnknown(object, Set.of("id", "type", "function", "encryptedValue"), "tool call");
        if (!"function".equals(requiredString(object, "type"))) {
            throw invalid("AG-UI tool-call type must be function.");
        }
        StateValue.ObjectValue function = requireObject(requiredValue(object, "function"), "tool-call function");
        rejectUnknown(function, Set.of("name", "arguments"), "tool-call function");
        return new AGUIMessages.ToolCall(
                requiredString(object, "id"),
                new AGUIMessages.FunctionCall(
                        requiredString(function, "name"), requiredStringAllowEmpty(function, "arguments")),
                optionalString(object, "encryptedValue"));
    }

    private static StateValue.ObjectValue encodeToolCall(AGUIMessages.ToolCall toolCall) {
        LinkedHashMap<String, StateValue> function = new LinkedHashMap<>();
        function.put("name", string(toolCall.function().name()));
        function.put("arguments", string(toolCall.function().arguments()));
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put("id", string(toolCall.id()));
        value.put("type", string(toolCall.type()));
        value.put("function", StateValue.object(function));
        putString(value, "encryptedValue", toolCall.encryptedValue());
        return StateValue.object(value);
    }

    private static AGUIUserContent decodeUserContent(StateValue value) {
        return switch (value) {
            case StateValue.StringValue string -> new AGUIMessages.TextUserContent(string.value());
            case StateValue.ArrayValue array ->
                new AGUIMessages.PartsUserContent(array.values().stream()
                        .map(item -> decodeInputContent(requireObject(item, "input content")))
                        .toList());
            default -> throw invalid("User message content must be a string or input-content array.");
        };
    }

    private static StateValue encodeUserContent(AGUIUserContent content) {
        return switch (content) {
            case AGUIMessages.TextUserContent text -> string(text.text());
            case AGUIMessages.PartsUserContent parts ->
                StateValue.array(parts.parts().stream()
                        .map(AGUIWireValues::encodeInputContent)
                        .toList());
        };
    }

    @SuppressWarnings("removal")
    private static AGUIInputContent decodeInputContent(StateValue.ObjectValue object) {
        String type = requiredString(object, "type");
        return switch (type) {
            case "text" -> {
                rejectUnknown(object, Set.of("type", "text"), "text input");
                yield new AGUIMessages.TextInput(requiredStringAllowEmpty(object, "text"));
            }
            case "image", "audio", "video", "document" -> {
                rejectUnknown(object, Set.of("type", "source", "metadata"), "media input");
                yield new AGUIMessages.MediaInput(
                        AGUIMediaKind.fromValue(type),
                        decodeInputSource(requireObject(requiredValue(object, "source"), "input source")),
                        optionalValue(object, "metadata"));
            }
            case "binary" -> {
                rejectUnknown(
                        object, Set.of("type", "mimeType", "id", "url", "data", "filename"), "legacy binary input");
                yield new AGUIMessages.LegacyBinaryInput(
                        requiredString(object, "mimeType"),
                        optionalString(object, "id"),
                        optionalString(object, "url"),
                        optionalString(object, "data"),
                        optionalString(object, "filename"));
            }
            default -> throw invalid("Unknown AG-UI input-content type.");
        };
    }

    @SuppressWarnings("removal")
    private static StateValue.ObjectValue encodeInputContent(AGUIInputContent input) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put("type", string(input.type()));
        switch (input) {
            case AGUIMessages.TextInput text -> value.put("text", string(text.text()));
            case AGUIMessages.MediaInput media -> {
                value.put("source", encodeInputSource(media.source()));
                if (media.metadata() != null) {
                    value.put("metadata", media.metadata());
                }
            }
            case AGUIMessages.LegacyBinaryInput binary -> {
                value.put("mimeType", string(binary.mimeType()));
                putString(value, "id", binary.id());
                putString(value, "url", binary.url());
                putString(value, "data", binary.data());
                putString(value, "filename", binary.filename());
            }
        }
        return StateValue.object(value);
    }

    private static AGUIInputSource decodeInputSource(StateValue.ObjectValue object) {
        String type = requiredString(object, "type");
        return switch (type) {
            case "data" -> {
                rejectUnknown(object, Set.of("type", "value", "mimeType"), "data input source");
                yield new AGUIMessages.DataSource(
                        requiredStringAllowEmpty(object, "value"), requiredString(object, "mimeType"));
            }
            case "url" -> {
                rejectUnknown(object, Set.of("type", "value", "mimeType"), "URL input source");
                yield new AGUIMessages.UrlSource(requiredString(object, "value"), optionalString(object, "mimeType"));
            }
            default -> throw invalid("Unknown AG-UI input-source type.");
        };
    }

    private static StateValue.ObjectValue encodeInputSource(AGUIInputSource source) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put("type", string(source.type()));
        value.put("value", string(source.value()));
        switch (source) {
            case AGUIMessages.DataSource data -> value.put("mimeType", string(data.mimeType()));
            case AGUIMessages.UrlSource url -> putString(value, "mimeType", url.mimeType());
        }
        return StateValue.object(value);
    }

    private static AGUITool decodeTool(StateValue.ObjectValue object) {
        rejectUnknown(object, Set.of("name", "description", "parameters", "metadata"), "tool");
        StateValue.ObjectValue metadata = optionalObject(object, "metadata");
        return new AGUITool(
                requiredString(object, "name"),
                requiredString(object, "description"),
                requiredValue(object, "parameters"),
                metadata == null ? Map.of() : metadata.values());
    }

    private static StateValue.ObjectValue encodeTool(AGUITool tool) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put("name", string(tool.name()));
        value.put("description", string(tool.description()));
        value.put("parameters", tool.parameters());
        if (!tool.metadata().isEmpty()) {
            value.put("metadata", StateValue.object(tool.metadata()));
        }
        return StateValue.object(value);
    }

    private static AGUIContext decodeContext(StateValue.ObjectValue object) {
        rejectUnknown(object, Set.of("description", "value"), "context");
        return new AGUIContext(requiredString(object, "description"), requiredStringAllowEmpty(object, "value"));
    }

    private static StateValue.ObjectValue encodeContext(AGUIContext context) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put("description", string(context.description()));
        value.put("value", string(context.value()));
        return StateValue.object(value);
    }

    private static AGUIInterrupt decodeInterrupt(StateValue.ObjectValue object) {
        rejectUnknown(
                object,
                Set.of("id", "reason", "message", "toolCallId", "responseSchema", "expiresAt", "metadata"),
                "interrupt");
        StateValue.ObjectValue metadata = optionalObject(object, "metadata");
        return new AGUIInterrupt(
                requiredString(object, "id"),
                requiredString(object, "reason"),
                optionalString(object, "message"),
                optionalString(object, "toolCallId"),
                optionalObject(object, "responseSchema"),
                optionalInstant(object, "expiresAt"),
                metadata == null ? Map.of() : metadata.values());
    }

    private static StateValue.ObjectValue encodeInterrupt(AGUIInterrupt interrupt) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put("id", string(interrupt.id()));
        value.put("reason", string(interrupt.reason()));
        putString(value, "message", interrupt.message());
        putString(value, "toolCallId", interrupt.toolCallId());
        if (interrupt.responseSchema() != null) {
            value.put("responseSchema", interrupt.responseSchema());
        }
        if (interrupt.expiresAt() != null) {
            value.put("expiresAt", string(interrupt.expiresAt().toString()));
        }
        if (!interrupt.metadata().isEmpty()) {
            value.put("metadata", StateValue.object(interrupt.metadata()));
        }
        return StateValue.object(value);
    }

    private static AGUIResumeEntry decodeResume(StateValue.ObjectValue object) {
        rejectUnknown(object, Set.of("interruptId", "status", "payload"), "resume entry");
        return new AGUIResumeEntry(
                requiredString(object, "interruptId"),
                AGUIResumeStatus.fromValue(requiredString(object, "status")),
                optionalValue(object, "payload"));
    }

    private static StateValue.ObjectValue encodeResume(AGUIResumeEntry resume) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put("interruptId", string(resume.interruptId()));
        value.put("status", string(resume.status().value()));
        if (resume.payload() != null) {
            value.put("payload", resume.payload());
        }
        return StateValue.object(value);
    }

    private static Instant optionalInstant(StateValue.ObjectValue object, String name) {
        String value = optionalString(object, name);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw invalid("Member '" + name + "' must be an ISO-8601 instant.");
        }
    }
}
