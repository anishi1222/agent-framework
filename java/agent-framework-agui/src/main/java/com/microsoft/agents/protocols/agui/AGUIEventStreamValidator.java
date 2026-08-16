// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import com.microsoft.agents.core.StateValue;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Enforces AG-UI run, step, message, tool, state, activity, and reasoning ordering. */
public final class AGUIEventStreamValidator {
    private final AGUILimits limits;

    private final Set<String> messageIds;

    private final Set<String> toolCallIds;

    private final Set<String> endedToolCallIds;

    private final Set<String> toolResultIds = new HashSet<>();

    private final Set<String> reasoningPhaseIds = new HashSet<>();

    private final Deque<String> steps = new ArrayDeque<>();

    private final Map<String, ActivityState> activities = new HashMap<>();

    private StateValue currentState;

    private int eventCount;

    private boolean started;

    private boolean terminal;

    private String threadId;

    private String runId;

    private String openTextMessage;

    private String openReasoningPhase;

    private String openReasoningMessage;

    private boolean thinking;

    private boolean thinkingText;

    /**
     * Creates a validator with no prior-thread context.
     *
     * @param limits event and patch bounds
     */
    public AGUIEventStreamValidator(AGUILimits limits) {
        this(limits, AGUIValidationContext.empty());
    }

    /**
     * Creates a validator with prior-thread entities for a resumed run.
     *
     * @param limits event and patch bounds
     * @param context prior-thread validation context
     */
    public AGUIEventStreamValidator(AGUILimits limits, AGUIValidationContext context) {
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
        java.util.Objects.requireNonNull(context, "context");
        messageIds = new LinkedHashSet<>(context.messageIds());
        toolCallIds = new LinkedHashSet<>(context.toolCallIds());
        endedToolCallIds = new LinkedHashSet<>(context.toolCallIds());
        currentState = context.state();
    }

    /**
     * Validates and records one normalized event.
     *
     * @param event normalized event
     */
    @SuppressWarnings("removal")
    public void accept(AGUIEvent event) {
        java.util.Objects.requireNonNull(event, "event");
        if (eventCount >= limits.maxEventsPerRun()) {
            throw invalid("AG-UI stream exceeds maxEventsPerRun.");
        }
        eventCount++;
        if (terminal) {
            throw invalid("No AG-UI events are allowed after the terminal event.");
        }
        if (!started) {
            if (!(event instanceof AGUIEvents.RunStarted runStarted)) {
                throw invalid("RUN_STARTED must be the first event.");
            }
            start(runStarted);
            return;
        }
        if (event instanceof AGUIEvents.RunStarted) {
            throw invalid("RUN_STARTED may appear only once.");
        }
        switch (event) {
            case AGUIEvents.TextMessageStart text -> startText(text);
            case AGUIEvents.TextMessageContent text -> {
                if (!text.messageId().equals(openTextMessage)) {
                    throw invalid("TEXT_MESSAGE_CONTENT targets no open text message.");
                }
            }
            case AGUIEvents.TextMessageEnd text -> {
                if (!text.messageId().equals(openTextMessage)) {
                    throw invalid("TEXT_MESSAGE_END targets no open text message.");
                }
                openTextMessage = null;
            }
            case AGUIEvents.TextMessageChunk _, AGUIEvents.ToolCallChunk _, AGUIEvents.ReasoningMessageChunk _ ->
                throw invalid("Convenience chunks must be normalized before sequence validation.");
            case AGUIEvents.ToolCallStart tool -> startTool(tool);
            case AGUIEvents.ToolCallArgs tool -> {
                if (!toolCallIds.contains(tool.toolCallId()) || endedToolCallIds.contains(tool.toolCallId())) {
                    throw invalid("TOOL_CALL_ARGS targets no open tool call.");
                }
            }
            case AGUIEvents.ToolCallEnd tool -> {
                if (!toolCallIds.contains(tool.toolCallId()) || !endedToolCallIds.add(tool.toolCallId())) {
                    throw invalid("TOOL_CALL_END targets no open tool call.");
                }
            }
            case AGUIEvents.ToolCallResult result -> toolResult(result);
            case AGUIEvents.ThinkingStart _ -> {
                if (thinking) {
                    throw invalid("THINKING_START is already open.");
                }
                thinking = true;
            }
            case AGUIEvents.ThinkingEnd _ -> {
                if (!thinking || thinkingText) {
                    throw invalid("THINKING_END is unbalanced.");
                }
                thinking = false;
            }
            case AGUIEvents.ThinkingTextMessageStart _ -> {
                if (!thinking || thinkingText) {
                    throw invalid("THINKING_TEXT_MESSAGE_START is unbalanced.");
                }
                thinkingText = true;
            }
            case AGUIEvents.ThinkingTextMessageContent _ -> {
                if (!thinkingText) {
                    throw invalid("THINKING_TEXT_MESSAGE_CONTENT has no open message.");
                }
            }
            case AGUIEvents.ThinkingTextMessageEnd _ -> {
                if (!thinkingText) {
                    throw invalid("THINKING_TEXT_MESSAGE_END has no open message.");
                }
                thinkingText = false;
            }
            case AGUIEvents.StateSnapshot state -> currentState = state.snapshot();
            case AGUIEvents.StateDelta state -> {
                if (currentState == null) {
                    throw invalid("STATE_DELTA requires an input state or preceding STATE_SNAPSHOT.");
                }
                currentState = AGUIJsonPatch.apply(currentState, state.delta(), limits.maxPatchOperations());
            }
            case AGUIEvents.MessagesSnapshot snapshot -> acceptMessagesSnapshot(snapshot.messages());
            case AGUIEvents.ActivitySnapshot activity -> {
                ActivityState existing = activities.get(activity.messageId());
                if (existing == null || activity.replace()) {
                    activities.put(
                            activity.messageId(), new ActivityState(activity.activityType(), activity.content()));
                }
                messageIds.add(activity.messageId());
            }
            case AGUIEvents.ActivityDelta activity -> {
                ActivityState existing = activities.get(activity.messageId());
                if (existing == null || !existing.type().equals(activity.activityType())) {
                    throw invalid("ACTIVITY_DELTA requires a matching preceding ACTIVITY_SNAPSHOT.");
                }
                StateValue patched =
                        AGUIJsonPatch.apply(existing.content(), activity.patch(), limits.maxPatchOperations());
                if (!(patched instanceof StateValue.ObjectValue object)) {
                    throw invalid("ACTIVITY_DELTA must retain object content.");
                }
                activities.put(activity.messageId(), new ActivityState(existing.type(), object));
            }
            case AGUIEvents.Raw _, AGUIEvents.Custom _ -> {
                // Special events do not alter ordering state.
            }
            case AGUIEvents.RunFinished run -> finishRun(run);
            case AGUIEvents.RunError _ -> finishRun();
            case AGUIEvents.StepStarted step -> steps.push(step.stepName());
            case AGUIEvents.StepFinished step -> {
                if (steps.isEmpty() || !steps.pop().equals(step.stepName())) {
                    throw invalid("STEP_FINISHED must match the most recent open step.");
                }
            }
            case AGUIEvents.ReasoningStart reasoning -> {
                if (openReasoningPhase != null || !reasoningPhaseIds.add(reasoning.messageId())) {
                    throw invalid("REASONING_START identifier is duplicate or already open.");
                }
                openReasoningPhase = reasoning.messageId();
            }
            case AGUIEvents.ReasoningMessageStart reasoning -> {
                if (openReasoningPhase == null || openReasoningMessage != null) {
                    throw invalid("REASONING_MESSAGE_START requires one open reasoning phase.");
                }
                requireUniqueMessage(reasoning.messageId());
                openReasoningMessage = reasoning.messageId();
            }
            case AGUIEvents.ReasoningMessageContent reasoning -> {
                if (!reasoning.messageId().equals(openReasoningMessage)) {
                    throw invalid("REASONING_MESSAGE_CONTENT targets no open reasoning message.");
                }
            }
            case AGUIEvents.ReasoningMessageEnd reasoning -> {
                if (!reasoning.messageId().equals(openReasoningMessage)) {
                    throw invalid("REASONING_MESSAGE_END targets no open reasoning message.");
                }
                openReasoningMessage = null;
            }
            case AGUIEvents.ReasoningEnd reasoning -> {
                if (!reasoning.messageId().equals(openReasoningPhase) || openReasoningMessage != null) {
                    throw invalid("REASONING_END is unbalanced.");
                }
                openReasoningPhase = null;
            }
            case AGUIEvents.ReasoningEncryptedValue encrypted -> {
                boolean known = encrypted.subtype() == AGUIReasoningEncryptedSubtype.MESSAGE
                        ? messageIds.contains(encrypted.entityId())
                        : toolCallIds.contains(encrypted.entityId());
                if (!known) {
                    throw invalid("REASONING_ENCRYPTED_VALUE targets an unknown entity.");
                }
            }
            case AGUIEvents.RunStarted _ -> throw new AssertionError("RUN_STARTED was handled before the switch.");
        }
    }

    /**
     * Verifies that the stream ended after exactly one balanced terminal event.
     */
    public void finish() {
        if (!started) {
            throw invalid("AG-UI stream did not start with RUN_STARTED.");
        }
        if (!terminal) {
            throw invalid("AG-UI stream ended without RUN_FINISHED or RUN_ERROR.");
        }
    }

    /**
     * Returns the synchronized state after validated snapshot and delta events.
     *
     * @return current state, or {@code null} when no baseline was observed
     */
    public StateValue currentState() {
        return currentState;
    }

    /**
     * Returns the number of accepted events.
     *
     * @return accepted event count
     */
    public int eventCount() {
        return eventCount;
    }

    private void start(AGUIEvents.RunStarted run) {
        started = true;
        threadId = run.threadId();
        runId = run.runId();
        if (run.input() != null) {
            AGUIValidationContext inputContext = AGUIValidationContext.fromInput(run.input());
            messageIds.addAll(inputContext.messageIds());
            toolCallIds.addAll(inputContext.toolCallIds());
            endedToolCallIds.addAll(inputContext.toolCallIds());
            currentState = inputContext.state();
        }
    }

    private void startText(AGUIEvents.TextMessageStart text) {
        if (openTextMessage != null) {
            throw invalid("Only one text message may be open at a time.");
        }
        requireUniqueMessage(text.messageId());
        openTextMessage = text.messageId();
    }

    private void startTool(AGUIEvents.ToolCallStart tool) {
        if (!toolCallIds.add(tool.toolCallId())) {
            throw invalid("Tool-call identifiers must be unique.");
        }
        if (tool.parentMessageId() != null && !messageIds.contains(tool.parentMessageId())) {
            throw invalid("TOOL_CALL_START parentMessageId is unknown.");
        }
    }

    private void toolResult(AGUIEvents.ToolCallResult result) {
        if (!endedToolCallIds.contains(result.toolCallId())) {
            throw invalid("TOOL_CALL_RESULT must follow a completed current or prior tool call.");
        }
        if (!toolResultIds.add(result.toolCallId())) {
            throw invalid("A tool call may have at most one result in a run.");
        }
        requireUniqueMessage(result.messageId());
    }

    private void acceptMessagesSnapshot(List<AGUIMessage> messages) {
        HashSet<String> snapshotIds = new HashSet<>();
        for (AGUIMessage message : messages) {
            if (!snapshotIds.add(message.id())) {
                throw invalid("MESSAGES_SNAPSHOT identifiers must be unique.");
            }
            if (message instanceof AGUIMessages.Assistant assistant) {
                for (AGUIMessages.ToolCall call : assistant.toolCalls()) {
                    if (!toolCallIds.add(call.id()) && !endedToolCallIds.contains(call.id())) {
                        throw invalid("MESSAGES_SNAPSHOT contains a conflicting tool-call identifier.");
                    }
                    endedToolCallIds.add(call.id());
                }
            } else if (message instanceof AGUIMessages.Tool tool) {
                toolCallIds.add(tool.toolCallId());
                endedToolCallIds.add(tool.toolCallId());
            }
        }
        messageIds.addAll(snapshotIds);
    }

    private void finishRun(AGUIEvents.RunFinished run) {
        if (!threadId.equals(run.threadId()) || !runId.equals(run.runId())) {
            throw invalid("RUN_FINISHED identifiers must match RUN_STARTED.");
        }
        if (run.outcome() instanceof AGUIRunOutcomes.Interrupt interrupt) {
            HashSet<String> ids = new HashSet<>();
            for (AGUIInterrupt pending : interrupt.interrupts()) {
                if (!ids.add(pending.id())) {
                    throw invalid("Interrupt identifiers must be unique.");
                }
                if (pending.toolCallId() != null && !endedToolCallIds.contains(pending.toolCallId())) {
                    throw invalid("Tool-bound interrupt references an incomplete or unknown tool call.");
                }
            }
        }
        finishRun();
    }

    private void finishRun() {
        if (openTextMessage != null
                || !endedToolCallIds.containsAll(toolCallIds)
                || openReasoningPhase != null
                || openReasoningMessage != null
                || thinking
                || thinkingText
                || !steps.isEmpty()) {
            throw invalid("Terminal event requires every step, text, tool, and reasoning lifecycle to be balanced.");
        }
        terminal = true;
    }

    private void requireUniqueMessage(String messageId) {
        if (!messageIds.add(messageId)) {
            throw invalid("Message identifiers must be unique outside snapshot replacement.");
        }
    }

    private static AGUIProtocolException invalid(String message) {
        return new AGUIProtocolException(AGUIErrorCode.INVALID_SEQUENCE, message);
    }

    private record ActivityState(String type, StateValue.ObjectValue content) {}
}
