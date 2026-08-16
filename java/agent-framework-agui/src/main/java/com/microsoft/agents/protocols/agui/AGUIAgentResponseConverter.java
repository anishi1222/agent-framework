// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.ErrorContent;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.MetadataContent;
import com.microsoft.agents.core.ReasoningContent;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts an ordered normalized AG-UI stream into provider-neutral response updates. */
public final class AGUIAgentResponseConverter {
    private final AGUIJsonCodec codec;

    private final Map<String, ToolState> tools = new HashMap<>();

    private long sequence;

    private String threadId;

    private String runId;

    private String textMessageId;

    private Role textRole = Role.ASSISTANT;

    private String textName;

    /**
     * Creates a response converter.
     *
     * @param codec strict codec
     */
    public AGUIAgentResponseConverter(AGUIJsonCodec codec) {
        this.codec = java.util.Objects.requireNonNull(codec, "codec");
    }

    /**
     * Converts one normalized event.
     *
     * @param event event
     * @return zero or more response updates
     */
    @SuppressWarnings("removal")
    public List<AgentResponseUpdate> accept(AGUIEvent event) {
        java.util.Objects.requireNonNull(event, "event");
        ArrayList<AgentResponseUpdate> updates = new ArrayList<>();
        switch (event) {
            case AGUIEvents.RunStarted run -> {
                threadId = run.threadId();
                runId = run.runId();
                updates.add(update(
                        run, List.of(), Role.ASSISTANT, null, null, metadata("threadId", threadId, "runId", runId)));
            }
            case AGUIEvents.TextMessageStart text -> {
                textMessageId = text.messageId();
                textRole = Role.of(text.role().value());
                textName = text.name();
            }
            case AGUIEvents.TextMessageContent text ->
                updates.add(update(
                        text, List.of(new TextContent(text.delta())), textRole, textName, text.messageId(), Map.of()));
            case AGUIEvents.TextMessageEnd _ -> {
                textMessageId = null;
                textRole = Role.ASSISTANT;
                textName = null;
            }
            case AGUIEvents.ToolCallStart tool ->
                tools.put(tool.toolCallId(), new ToolState(tool.toolCallName(), new StringBuilder()));
            case AGUIEvents.ToolCallArgs tool ->
                requireTool(tool.toolCallId()).arguments().append(tool.delta());
            case AGUIEvents.ToolCallEnd tool -> {
                ToolState state = tools.remove(tool.toolCallId());
                if (state == null) {
                    throw invalid("TOOL_CALL_END targets no open tool call.");
                }
                String arguments = state.arguments().toString();
                StateValue parsed = arguments.isBlank()
                        ? StateValue.nullValue()
                        : codec.decodeValue(arguments.getBytes(StandardCharsets.UTF_8));
                updates.add(update(
                        tool,
                        List.of(new FunctionCallContent(tool.toolCallId(), state.name(), parsed)),
                        Role.ASSISTANT,
                        null,
                        textMessageId,
                        Map.of()));
            }
            case AGUIEvents.ToolCallResult result ->
                updates.add(update(
                        result,
                        List.of(new FunctionResultContent(result.toolCallId(), StateValue.string(result.content()))),
                        Role.TOOL,
                        null,
                        result.messageId(),
                        Map.of()));
            case AGUIEvents.ReasoningMessageContent reasoning ->
                updates.add(update(
                        reasoning,
                        List.of(new ReasoningContent(reasoning.messageId(), reasoning.delta())),
                        Role.of("reasoning"),
                        null,
                        reasoning.messageId(),
                        Map.of()));
            case AGUIEvents.ReasoningEncryptedValue encrypted ->
                updates.add(update(
                        encrypted,
                        List.of(new ReasoningContent(encrypted.entityId(), null, encrypted.encryptedValue(), Map.of())),
                        Role.of("reasoning"),
                        null,
                        encrypted.subtype() == AGUIReasoningEncryptedSubtype.MESSAGE ? encrypted.entityId() : null,
                        Map.of(
                                "agui.encryptedSubtype",
                                StateValue.string(encrypted.subtype().value()))));
            case AGUIEvents.RunFinished run -> updates.add(finished(run));
            case AGUIEvents.RunError error ->
                updates.add(update(
                        error,
                        List.of(new ErrorContent(error.message(), error.code(), null)),
                        Role.ASSISTANT,
                        null,
                        null,
                        Map.of()));
            case AGUIEvents.Custom custom ->
                updates.add(metadataUpdate(
                        custom,
                        "custom",
                        StateValue.object(Map.of("name", StateValue.string(custom.name()), "value", custom.value()))));
            case AGUIEvents.Raw raw ->
                updates.add(metadataUpdate(
                        raw,
                        "raw",
                        StateValue.object(Map.of(
                                "event",
                                raw.event(),
                                "source",
                                raw.source() == null ? StateValue.nullValue() : StateValue.string(raw.source())))));
            case AGUIEvents.StateSnapshot state ->
                updates.add(metadataUpdate(state, "stateSnapshot", state.snapshot()));
            case AGUIEvents.StateDelta state ->
                updates.add(metadataUpdate(state, "stateDelta", patchValue(state.delta())));
            case AGUIEvents.MessagesSnapshot messages ->
                updates.add(metadataUpdate(
                        messages,
                        "messagesSnapshot",
                        StateValue.array(messages.messages().stream()
                                .map(message -> StateValue.string(message.id()))
                                .toList())));
            case AGUIEvents.ActivitySnapshot activity ->
                updates.add(metadataUpdate(activity, "activitySnapshot", activity.content()));
            case AGUIEvents.ActivityDelta activity ->
                updates.add(metadataUpdate(activity, "activityDelta", patchValue(activity.patch())));
            case AGUIEvents.StepStarted step ->
                updates.add(metadataUpdate(step, "stepStarted", StateValue.string(step.stepName())));
            case AGUIEvents.StepFinished step ->
                updates.add(metadataUpdate(step, "stepFinished", StateValue.string(step.stepName())));
            case AGUIEvents.ThinkingTextMessageContent thinking ->
                updates.add(update(
                        thinking,
                        List.of(new ReasoningContent("thinking-" + sequence, thinking.delta())),
                        Role.of("reasoning"),
                        null,
                        null,
                        Map.of("agui.deprecatedThinking", StateValue.bool(true))));
            case AGUIEvents.TextMessageChunk _, AGUIEvents.ToolCallChunk _, AGUIEvents.ReasoningMessageChunk _ ->
                throw invalid("Convenience chunks must be normalized before response conversion.");
            case AGUIEvents.ThinkingStart _,
                    AGUIEvents.ThinkingEnd _,
                    AGUIEvents.ThinkingTextMessageStart _,
                    AGUIEvents.ThinkingTextMessageEnd _,
                    AGUIEvents.ReasoningStart _,
                    AGUIEvents.ReasoningMessageStart _,
                    AGUIEvents.ReasoningMessageEnd _,
                    AGUIEvents.ReasoningEnd _ -> {
                // Boundary-only events do not produce framework content.
            }
        }
        return List.copyOf(updates);
    }

    private AgentResponseUpdate finished(AGUIEvents.RunFinished run) {
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        metadata.put("threadId", StateValue.string(run.threadId()));
        metadata.put("runId", StateValue.string(run.runId()));
        if (run.result() != null) {
            metadata.put("result", run.result());
        }
        if (run.outcome() != null) {
            metadata.put("outcome", outcomeValue(run.outcome()));
        }
        FinishReason reason = run.outcome() instanceof AGUIRunOutcomes.Interrupt
                ? FinishReason.of("inputRequired")
                : FinishReason.STOP;
        return AgentResponseUpdate.builder()
                .sequence(sequence++)
                .contents(List.of())
                .role(Role.ASSISTANT)
                .responseId(run.runId())
                .finishReason(reason)
                .metadata(metadata)
                .build();
    }

    private AgentResponseUpdate metadataUpdate(AGUIEvent event, String kind, StateValue value) {
        return update(
                event,
                List.of(new MetadataContent(Map.of("agui.kind", StateValue.string(kind), "agui.value", value))),
                Role.ASSISTANT,
                null,
                null,
                Map.of());
    }

    private AgentResponseUpdate update(
            AGUIEvent event,
            List<? extends com.microsoft.agents.core.Content> contents,
            Role role,
            String authorName,
            String messageId,
            Map<String, StateValue> metadata) {
        AgentResponseUpdate.Builder builder = AgentResponseUpdate.builder()
                .sequence(sequence++)
                .contents(contents)
                .role(role)
                .metadata(metadata);
        if (authorName != null) {
            builder.authorName(authorName);
        }
        if (messageId != null) {
            builder.messageId(messageId);
        }
        if (runId != null) {
            builder.responseId(runId);
        }
        Instant createdAt = instant(event);
        if (createdAt != null) {
            builder.createdAt(createdAt);
        }
        return builder.build();
    }

    private StateValue.ArrayValue patchValue(List<AGUIJsonPatchOperation> operations) {
        return StateValue.array(operations.stream()
                .map(operation -> {
                    LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
                    value.put("op", StateValue.string(operation.op().value()));
                    value.put("path", StateValue.string(operation.path()));
                    if (operation.from() != null) {
                        value.put("from", StateValue.string(operation.from()));
                    }
                    if (operation.value() != null) {
                        value.put("value", operation.value());
                    }
                    return StateValue.object(value);
                })
                .toList());
    }

    private static StateValue.ObjectValue outcomeValue(AGUIRunFinishedOutcome outcome) {
        if (outcome instanceof AGUIRunOutcomes.Interrupt interrupt) {
            return StateValue.object(Map.of(
                    "type",
                    StateValue.string("interrupt"),
                    "interruptIds",
                    StateValue.array(interrupt.interrupts().stream()
                            .map(value -> StateValue.string(value.id()))
                            .toList())));
        }
        return StateValue.object(Map.of("type", StateValue.string("success")));
    }

    private ToolState requireTool(String id) {
        ToolState state = tools.get(id);
        if (state == null) {
            throw invalid("TOOL_CALL_ARGS targets no open tool call.");
        }
        return state;
    }

    private static Map<String, StateValue> metadata(
            String firstKey, String firstValue, String secondKey, String secondValue) {
        return Map.of(firstKey, StateValue.string(firstValue), secondKey, StateValue.string(secondValue));
    }

    private static Instant instant(AGUIEvent event) {
        if (event.timestamp() == null) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(event.timestamp().longValueExact());
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    private static AGUIProtocolException invalid(String message) {
        return new AGUIProtocolException(AGUIErrorCode.INVALID_SEQUENCE, message);
    }

    private record ToolState(String name, StringBuilder arguments) {}
}
