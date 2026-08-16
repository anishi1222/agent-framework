// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import com.microsoft.agents.core.StateValue;
import java.math.BigDecimal;
import java.util.List;

/** Contains the closed immutable hierarchy for the current official AG-UI event set. */
@SuppressWarnings("removal")
public final class AGUIEvents {
    private AGUIEvents() {}

    /** Closed marker implemented by every concrete event in this module. */
    public sealed interface Event extends AGUIEvent
            permits TextMessageStart,
                    TextMessageContent,
                    TextMessageEnd,
                    TextMessageChunk,
                    ToolCallStart,
                    ToolCallArgs,
                    ToolCallEnd,
                    ToolCallChunk,
                    ToolCallResult,
                    ThinkingStart,
                    ThinkingEnd,
                    ThinkingTextMessageStart,
                    ThinkingTextMessageContent,
                    ThinkingTextMessageEnd,
                    StateSnapshot,
                    StateDelta,
                    MessagesSnapshot,
                    ActivitySnapshot,
                    ActivityDelta,
                    Raw,
                    Custom,
                    RunStarted,
                    RunFinished,
                    RunError,
                    StepStarted,
                    StepFinished,
                    ReasoningStart,
                    ReasoningMessageStart,
                    ReasoningMessageContent,
                    ReasoningMessageEnd,
                    ReasoningMessageChunk,
                    ReasoningEnd,
                    ReasoningEncryptedValue {}

    /**
     * Starts a streaming text message.
     *
     * @param messageId message identifier
     * @param role non-tool text role
     * @param name optional sender name
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record TextMessageStart(
            String messageId, AGUIRole role, String name, BigDecimal timestamp, StateValue rawEvent) implements Event {
        /** Creates a validated text start. */
        public TextMessageStart {
            messageId = id(messageId, "messageId");
            java.util.Objects.requireNonNull(role, "role");
            if (!isTextRole(role)) {
                throw AGUIValidation.invalid("Text message role must be developer, system, assistant, or user.");
            }
            name = AGUIValidation.optionalNonBlank(name, "name");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.TEXT_MESSAGE_START;
        }
    }

    /**
     * Carries a text message delta.
     *
     * @param messageId target message
     * @param delta text delta
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record TextMessageContent(String messageId, String delta, BigDecimal timestamp, StateValue rawEvent)
            implements Event {
        /** Creates validated text content. */
        public TextMessageContent {
            messageId = id(messageId, "messageId");
            java.util.Objects.requireNonNull(delta, "delta");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.TEXT_MESSAGE_CONTENT;
        }
    }

    /**
     * Ends a streaming text message.
     *
     * @param messageId target message
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record TextMessageEnd(String messageId, BigDecimal timestamp, StateValue rawEvent) implements Event {
        /** Creates a validated text end. */
        public TextMessageEnd {
            messageId = id(messageId, "messageId");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.TEXT_MESSAGE_END;
        }
    }

    /**
     * Carries a convenience text chunk that normalizes to the explicit text lifecycle.
     *
     * @param messageId optional identifier, required on the first chunk
     * @param role optional non-tool role
     * @param delta optional content delta
     * @param name optional sender name
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record TextMessageChunk(
            String messageId, AGUIRole role, String delta, String name, BigDecimal timestamp, StateValue rawEvent)
            implements Event {
        /** Creates a structurally valid text chunk. */
        public TextMessageChunk {
            messageId = AGUIValidation.optionalNonBlank(messageId, "messageId");
            if (role != null && !isTextRole(role)) {
                throw AGUIValidation.invalid("Text message role must be developer, system, assistant, or user.");
            }
            name = AGUIValidation.optionalNonBlank(name, "name");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.TEXT_MESSAGE_CHUNK;
        }
    }

    /**
     * Starts a streaming tool call.
     *
     * @param toolCallId tool-call identifier
     * @param toolCallName function name
     * @param parentMessageId optional parent message
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record ToolCallStart(
            String toolCallId, String toolCallName, String parentMessageId, BigDecimal timestamp, StateValue rawEvent)
            implements Event {
        /** Creates a validated tool-call start. */
        public ToolCallStart {
            toolCallId = id(toolCallId, "toolCallId");
            toolCallName = id(toolCallName, "toolCallName");
            parentMessageId = AGUIValidation.optionalNonBlank(parentMessageId, "parentMessageId");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.TOOL_CALL_START;
        }
    }

    /**
     * Carries a tool-call argument delta.
     *
     * @param toolCallId tool-call identifier
     * @param delta JSON fragment
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record ToolCallArgs(String toolCallId, String delta, BigDecimal timestamp, StateValue rawEvent)
            implements Event {
        /** Creates validated tool-call arguments. */
        public ToolCallArgs {
            toolCallId = id(toolCallId, "toolCallId");
            java.util.Objects.requireNonNull(delta, "delta");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.TOOL_CALL_ARGS;
        }
    }

    /**
     * Ends a streaming tool call.
     *
     * @param toolCallId tool-call identifier
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record ToolCallEnd(String toolCallId, BigDecimal timestamp, StateValue rawEvent) implements Event {
        /** Creates a validated tool-call end. */
        public ToolCallEnd {
            toolCallId = id(toolCallId, "toolCallId");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.TOOL_CALL_END;
        }
    }

    /**
     * Carries a convenience tool-call chunk.
     *
     * @param toolCallId optional identifier, required on the first chunk
     * @param toolCallName optional function name, required on the first chunk
     * @param parentMessageId optional parent message
     * @param delta optional JSON argument fragment
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record ToolCallChunk(
            String toolCallId,
            String toolCallName,
            String parentMessageId,
            String delta,
            BigDecimal timestamp,
            StateValue rawEvent)
            implements Event {
        /** Creates a structurally valid tool-call chunk. */
        public ToolCallChunk {
            toolCallId = AGUIValidation.optionalNonBlank(toolCallId, "toolCallId");
            toolCallName = AGUIValidation.optionalNonBlank(toolCallName, "toolCallName");
            parentMessageId = AGUIValidation.optionalNonBlank(parentMessageId, "parentMessageId");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.TOOL_CALL_CHUNK;
        }
    }

    /**
     * Carries one completed tool result.
     *
     * @param messageId result message identifier
     * @param toolCallId originating tool-call identifier
     * @param content result text
     * @param role optional role, when present exactly {@code tool}
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record ToolCallResult(
            String messageId,
            String toolCallId,
            String content,
            AGUIRole role,
            BigDecimal timestamp,
            StateValue rawEvent)
            implements Event {
        /** Creates a validated tool result. */
        public ToolCallResult {
            messageId = id(messageId, "messageId");
            toolCallId = id(toolCallId, "toolCallId");
            java.util.Objects.requireNonNull(content, "content");
            if (role != null && role != AGUIRole.TOOL) {
                throw AGUIValidation.invalid("Tool-call result role must be tool when present.");
            }
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.TOOL_CALL_RESULT;
        }
    }

    /**
     * Starts a deprecated thinking phase.
     *
     * @param title optional title
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     * @deprecated use {@link ReasoningStart}
     */
    @Deprecated(forRemoval = true)
    public record ThinkingStart(String title, BigDecimal timestamp, StateValue rawEvent) implements Event {
        /** Creates a deprecated thinking start. */
        public ThinkingStart {
            title = AGUIValidation.optionalNonBlank(title, "title");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.THINKING_START;
        }
    }

    /**
     * Ends a deprecated thinking phase.
     *
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     * @deprecated use {@link ReasoningEnd}
     */
    @Deprecated(forRemoval = true)
    public record ThinkingEnd(BigDecimal timestamp, StateValue rawEvent) implements Event {
        @Override
        public AGUIEventType type() {
            return AGUIEventType.THINKING_END;
        }
    }

    /**
     * Starts a deprecated thinking text message.
     *
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     * @deprecated use {@link ReasoningMessageStart}
     */
    @Deprecated(forRemoval = true)
    public record ThinkingTextMessageStart(BigDecimal timestamp, StateValue rawEvent) implements Event {
        @Override
        public AGUIEventType type() {
            return AGUIEventType.THINKING_TEXT_MESSAGE_START;
        }
    }

    /**
     * Carries deprecated thinking text.
     *
     * @param delta content delta
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     * @deprecated use {@link ReasoningMessageContent}
     */
    @Deprecated(forRemoval = true)
    public record ThinkingTextMessageContent(String delta, BigDecimal timestamp, StateValue rawEvent) implements Event {
        /** Creates deprecated thinking text. */
        public ThinkingTextMessageContent {
            java.util.Objects.requireNonNull(delta, "delta");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.THINKING_TEXT_MESSAGE_CONTENT;
        }
    }

    /**
     * Ends a deprecated thinking text message.
     *
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     * @deprecated use {@link ReasoningMessageEnd}
     */
    @Deprecated(forRemoval = true)
    public record ThinkingTextMessageEnd(BigDecimal timestamp, StateValue rawEvent) implements Event {
        @Override
        public AGUIEventType type() {
            return AGUIEventType.THINKING_TEXT_MESSAGE_END;
        }
    }

    /**
     * Replaces synchronized shared state.
     *
     * @param snapshot complete state
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record StateSnapshot(StateValue snapshot, BigDecimal timestamp, StateValue rawEvent) implements Event {
        /** Creates a state snapshot. */
        public StateSnapshot {
            snapshot = AGUIValidation.state(snapshot, "snapshot");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.STATE_SNAPSHOT;
        }
    }

    /**
     * Applies RFC 6902 operations to synchronized shared state.
     *
     * @param delta ordered patch operations
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record StateDelta(List<AGUIJsonPatchOperation> delta, BigDecimal timestamp, StateValue rawEvent)
            implements Event {
        /** Creates an immutable state delta. */
        public StateDelta {
            delta = AGUIValidation.list(delta, "delta");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.STATE_DELTA;
        }
    }

    /**
     * Replaces synchronized message history.
     *
     * @param messages complete message snapshot
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record MessagesSnapshot(List<AGUIMessage> messages, BigDecimal timestamp, StateValue rawEvent)
            implements Event {
        /** Creates an immutable messages snapshot. */
        public MessagesSnapshot {
            messages = AGUIValidation.list(messages, "messages");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.MESSAGES_SNAPSHOT;
        }
    }

    /**
     * Replaces or initializes one frontend activity.
     *
     * @param messageId activity message identifier
     * @param activityType activity discriminator
     * @param content complete activity state
     * @param replace whether an existing activity is replaced
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record ActivitySnapshot(
            String messageId,
            String activityType,
            StateValue.ObjectValue content,
            boolean replace,
            BigDecimal timestamp,
            StateValue rawEvent)
            implements Event {
        /** Creates a validated activity snapshot. */
        public ActivitySnapshot {
            messageId = id(messageId, "messageId");
            activityType = id(activityType, "activityType");
            content = AGUIValidation.object(content, "content");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.ACTIVITY_SNAPSHOT;
        }
    }

    /**
     * Applies RFC 6902 operations to one activity.
     *
     * @param messageId activity message identifier
     * @param activityType activity discriminator
     * @param patch ordered patch operations
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record ActivityDelta(
            String messageId,
            String activityType,
            List<AGUIJsonPatchOperation> patch,
            BigDecimal timestamp,
            StateValue rawEvent)
            implements Event {
        /** Creates a validated activity delta. */
        public ActivityDelta {
            messageId = id(messageId, "messageId");
            activityType = id(activityType, "activityType");
            patch = AGUIValidation.list(patch, "patch");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.ACTIVITY_DELTA;
        }
    }

    /**
     * Wraps one external source event.
     *
     * @param event original event payload
     * @param source optional source identifier
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional transformed source event
     */
    public record Raw(StateValue event, String source, BigDecimal timestamp, StateValue rawEvent) implements Event {
        /** Creates a validated raw event. */
        public Raw {
            event = AGUIValidation.state(event, "event");
            source = AGUIValidation.optionalNonBlank(source, "source");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.RAW;
        }
    }

    /**
     * Carries one application-defined extension event.
     *
     * @param name namespaced custom name
     * @param value arbitrary immutable payload
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record Custom(String name, StateValue value, BigDecimal timestamp, StateValue rawEvent) implements Event {
        /** Creates a custom event. */
        public Custom {
            name = id(name, "name");
            value = AGUIValidation.state(value, "value");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.CUSTOM;
        }
    }

    /**
     * Starts one run.
     *
     * @param threadId thread correlation identifier
     * @param runId run correlation identifier
     * @param parentRunId optional lineage identifier
     * @param input optional captured input
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record RunStarted(
            String threadId,
            String runId,
            String parentRunId,
            RunAgentInput input,
            BigDecimal timestamp,
            StateValue rawEvent)
            implements Event {
        /** Creates a validated run start. */
        public RunStarted {
            threadId = id(threadId, "threadId");
            runId = id(runId, "runId");
            parentRunId = AGUIValidation.optionalNonBlank(parentRunId, "parentRunId");
            if (input != null
                    && (!threadId.equals(input.threadId())
                            || !runId.equals(input.runId())
                            || !java.util.Objects.equals(parentRunId, input.parentRunId()))) {
                throw AGUIValidation.invalid("Captured run input identifiers must match RUN_STARTED.");
            }
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.RUN_STARTED;
        }
    }

    /**
     * Terminates one successful or interrupted run.
     *
     * @param threadId thread correlation identifier
     * @param runId run correlation identifier
     * @param result optional free-form result
     * @param outcome optional explicit success-or-interrupt outcome
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record RunFinished(
            String threadId,
            String runId,
            StateValue result,
            AGUIRunFinishedOutcome outcome,
            BigDecimal timestamp,
            StateValue rawEvent)
            implements Event {
        /** Creates a validated run finish. */
        public RunFinished {
            threadId = id(threadId, "threadId");
            runId = id(runId, "runId");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.RUN_FINISHED;
        }
    }

    /**
     * Terminates one failed run.
     *
     * @param message sanitized failure message
     * @param code optional stable code
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record RunError(String message, String code, BigDecimal timestamp, StateValue rawEvent) implements Event {
        /** Creates a validated run error. */
        public RunError {
            message = id(message, "message");
            code = AGUIValidation.optionalNonBlank(code, "code");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.RUN_ERROR;
        }
    }

    /**
     * Starts one named run step.
     *
     * @param stepName step name
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record StepStarted(String stepName, BigDecimal timestamp, StateValue rawEvent) implements Event {
        /** Creates a step start. */
        public StepStarted {
            stepName = id(stepName, "stepName");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.STEP_STARTED;
        }
    }

    /**
     * Ends one named run step.
     *
     * @param stepName step name
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record StepFinished(String stepName, BigDecimal timestamp, StateValue rawEvent) implements Event {
        /** Creates a step finish. */
        public StepFinished {
            stepName = id(stepName, "stepName");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.STEP_FINISHED;
        }
    }

    /**
     * Starts one reasoning phase.
     *
     * @param messageId reasoning phase identifier
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record ReasoningStart(String messageId, BigDecimal timestamp, StateValue rawEvent) implements Event {
        /** Creates a reasoning start. */
        public ReasoningStart {
            messageId = id(messageId, "messageId");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.REASONING_START;
        }
    }

    /**
     * Starts one reasoning message.
     *
     * @param messageId reasoning message identifier
     * @param role fixed reasoning role
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record ReasoningMessageStart(String messageId, AGUIRole role, BigDecimal timestamp, StateValue rawEvent)
            implements Event {
        /** Creates a reasoning message start. */
        public ReasoningMessageStart {
            messageId = id(messageId, "messageId");
            if (role != AGUIRole.REASONING) {
                throw AGUIValidation.invalid("Reasoning message role must be reasoning.");
            }
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.REASONING_MESSAGE_START;
        }
    }

    /**
     * Carries a reasoning content delta.
     *
     * @param messageId reasoning message identifier
     * @param delta visible reasoning delta
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record ReasoningMessageContent(String messageId, String delta, BigDecimal timestamp, StateValue rawEvent)
            implements Event {
        /** Creates reasoning message content. */
        public ReasoningMessageContent {
            messageId = id(messageId, "messageId");
            java.util.Objects.requireNonNull(delta, "delta");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.REASONING_MESSAGE_CONTENT;
        }
    }

    /**
     * Ends one reasoning message.
     *
     * @param messageId reasoning message identifier
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record ReasoningMessageEnd(String messageId, BigDecimal timestamp, StateValue rawEvent) implements Event {
        /** Creates a reasoning message end. */
        public ReasoningMessageEnd {
            messageId = id(messageId, "messageId");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.REASONING_MESSAGE_END;
        }
    }

    /**
     * Carries a reasoning convenience chunk.
     *
     * @param messageId optional identifier, required on the first chunk
     * @param delta optional content; an empty value closes the message
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record ReasoningMessageChunk(String messageId, String delta, BigDecimal timestamp, StateValue rawEvent)
            implements Event {
        /** Creates a structurally valid reasoning chunk. */
        public ReasoningMessageChunk {
            messageId = AGUIValidation.optionalNonBlank(messageId, "messageId");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.REASONING_MESSAGE_CHUNK;
        }
    }

    /**
     * Ends one reasoning phase.
     *
     * @param messageId reasoning phase identifier
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record ReasoningEnd(String messageId, BigDecimal timestamp, StateValue rawEvent) implements Event {
        /** Creates a reasoning end. */
        public ReasoningEnd {
            messageId = id(messageId, "messageId");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.REASONING_END;
        }
    }

    /**
     * Attaches opaque encrypted reasoning to a message or tool call.
     *
     * @param subtype referenced entity kind
     * @param entityId message or tool-call identifier
     * @param encryptedValue opaque protected value
     * @param timestamp optional numeric timestamp
     * @param rawEvent optional source event
     */
    public record ReasoningEncryptedValue(
            AGUIReasoningEncryptedSubtype subtype,
            String entityId,
            String encryptedValue,
            BigDecimal timestamp,
            StateValue rawEvent)
            implements Event {
        /** Creates a validated encrypted reasoning value. */
        public ReasoningEncryptedValue {
            java.util.Objects.requireNonNull(subtype, "subtype");
            entityId = id(entityId, "entityId");
            encryptedValue = id(encryptedValue, "encryptedValue");
        }

        @Override
        public AGUIEventType type() {
            return AGUIEventType.REASONING_ENCRYPTED_VALUE;
        }
    }

    private static String id(String value, String name) {
        return AGUIValidation.nonBlank(value, name);
    }

    private static boolean isTextRole(AGUIRole role) {
        return role == AGUIRole.DEVELOPER
                || role == AGUIRole.SYSTEM
                || role == AGUIRole.ASSISTANT
                || role == AGUIRole.USER;
    }
}
