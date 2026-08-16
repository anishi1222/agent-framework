// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

/** Lists the exact {@code @ag-ui/core} 0.0.57 event discriminators. */
public enum AGUIEventType {
    /** Text message start. */
    TEXT_MESSAGE_START,
    /** Text message content delta. */
    TEXT_MESSAGE_CONTENT,
    /** Text message end. */
    TEXT_MESSAGE_END,
    /** Text message convenience chunk. */
    TEXT_MESSAGE_CHUNK,
    /** Tool call start. */
    TOOL_CALL_START,
    /** Tool call argument delta. */
    TOOL_CALL_ARGS,
    /** Tool call end. */
    TOOL_CALL_END,
    /** Tool call convenience chunk. */
    TOOL_CALL_CHUNK,
    /** Tool call result. */
    TOOL_CALL_RESULT,
    /** Deprecated thinking phase start. */
    @Deprecated(forRemoval = true)
    THINKING_START,
    /** Deprecated thinking phase end. */
    @Deprecated(forRemoval = true)
    THINKING_END,
    /** Deprecated thinking text start. */
    @Deprecated(forRemoval = true)
    THINKING_TEXT_MESSAGE_START,
    /** Deprecated thinking text content. */
    @Deprecated(forRemoval = true)
    THINKING_TEXT_MESSAGE_CONTENT,
    /** Deprecated thinking text end. */
    @Deprecated(forRemoval = true)
    THINKING_TEXT_MESSAGE_END,
    /** Complete state replacement. */
    STATE_SNAPSHOT,
    /** RFC 6902 state update. */
    STATE_DELTA,
    /** Complete message snapshot. */
    MESSAGES_SNAPSHOT,
    /** Complete activity replacement. */
    ACTIVITY_SNAPSHOT,
    /** RFC 6902 activity update. */
    ACTIVITY_DELTA,
    /** Wrapped external event. */
    RAW,
    /** Application-defined event. */
    CUSTOM,
    /** Run start. */
    RUN_STARTED,
    /** Successful or interrupted run terminal. */
    RUN_FINISHED,
    /** Failed run terminal. */
    RUN_ERROR,
    /** Step start. */
    STEP_STARTED,
    /** Step end. */
    STEP_FINISHED,
    /** Reasoning phase start. */
    REASONING_START,
    /** Reasoning message start. */
    REASONING_MESSAGE_START,
    /** Reasoning message content. */
    REASONING_MESSAGE_CONTENT,
    /** Reasoning message end. */
    REASONING_MESSAGE_END,
    /** Reasoning message convenience chunk. */
    REASONING_MESSAGE_CHUNK,
    /** Reasoning phase end. */
    REASONING_END,
    /** Opaque encrypted reasoning value. */
    REASONING_ENCRYPTED_VALUE;

    /**
     * Resolves an exact SCREAMING_SNAKE_CASE discriminator.
     *
     * @param value wire discriminator
     * @return event type
     */
    public static AGUIEventType fromValue(String value) {
        try {
            return valueOf(AGUIValidation.nonBlank(value, "type"));
        } catch (IllegalArgumentException exception) {
            throw new AGUIProtocolException(AGUIErrorCode.UNKNOWN_EVENT, "Unknown standard AG-UI event type.");
        }
    }
}
