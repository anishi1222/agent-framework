// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import java.util.ArrayList;
import java.util.List;

/** Expands AG-UI text, tool-call, and reasoning convenience chunks into explicit lifecycles. */
public final class AGUIEventNormalizer {
    private TextChunkState text;

    private ToolChunkState tool;

    private ReasoningChunkState reasoning;

    /**
     * Accepts one event and returns zero or more normalized events.
     *
     * @param event source event
     * @return explicit normalized events
     */
    public List<AGUIEvent> accept(AGUIEvent event) {
        java.util.Objects.requireNonNull(event, "event");
        ArrayList<AGUIEvent> output = new ArrayList<>();
        switch (event) {
            case AGUIEvents.TextMessageChunk chunk -> normalizeText(chunk, output);
            case AGUIEvents.ToolCallChunk chunk -> normalizeTool(chunk, output);
            case AGUIEvents.ReasoningMessageChunk chunk -> normalizeReasoning(chunk, output);
            default -> {
                closeAll(output);
                output.add(event);
            }
        }
        if (!event.additionalProperties().isEmpty()) {
            output.forEach(normalized -> AGUIEventMetadata.attach(normalized, event.additionalProperties()));
        }
        return List.copyOf(output);
    }

    /**
     * Closes convenience lifecycles at end of stream.
     *
     * @return zero or more explicit end events
     */
    public List<AGUIEvent> finish() {
        ArrayList<AGUIEvent> output = new ArrayList<>();
        closeAll(output);
        return List.copyOf(output);
    }

    private void normalizeText(AGUIEvents.TextMessageChunk chunk, List<AGUIEvent> output) {
        closeTool(output);
        closeReasoning(output);
        if (text != null && chunk.messageId() != null && !text.messageId().equals(chunk.messageId())) {
            closeText(output);
        }
        if (text == null) {
            if (chunk.messageId() == null) {
                throw invalid("First TEXT_MESSAGE_CHUNK requires messageId.");
            }
            AGUIRole role = chunk.role() == null ? AGUIRole.ASSISTANT : chunk.role();
            text = new TextChunkState(chunk.messageId(), role, chunk.name(), chunk);
            output.add(new AGUIEvents.TextMessageStart(
                    chunk.messageId(), role, chunk.name(), chunk.timestamp(), chunk.rawEvent()));
        } else if (chunk.role() != null && chunk.role() != text.role()
                || chunk.name() != null && !java.util.Objects.equals(chunk.name(), text.name())) {
            throw invalid("Later TEXT_MESSAGE_CHUNK cannot change role or name.");
        }
        text = text.withLast(chunk);
        if (chunk.delta() != null) {
            output.add(new AGUIEvents.TextMessageContent(
                    text.messageId(), chunk.delta(), chunk.timestamp(), chunk.rawEvent()));
        }
    }

    private void normalizeTool(AGUIEvents.ToolCallChunk chunk, List<AGUIEvent> output) {
        closeText(output);
        closeReasoning(output);
        if (tool != null && chunk.toolCallId() != null && !tool.toolCallId().equals(chunk.toolCallId())) {
            closeTool(output);
        }
        if (tool == null) {
            if (chunk.toolCallId() == null || chunk.toolCallName() == null) {
                throw invalid("First TOOL_CALL_CHUNK requires toolCallId and toolCallName.");
            }
            tool = new ToolChunkState(chunk.toolCallId(), chunk.toolCallName(), chunk.parentMessageId(), chunk);
            output.add(new AGUIEvents.ToolCallStart(
                    chunk.toolCallId(),
                    chunk.toolCallName(),
                    chunk.parentMessageId(),
                    chunk.timestamp(),
                    chunk.rawEvent()));
        } else if (chunk.toolCallName() != null && !tool.toolCallName().equals(chunk.toolCallName())
                || chunk.parentMessageId() != null
                        && !java.util.Objects.equals(chunk.parentMessageId(), tool.parentMessageId())) {
            throw invalid("Later TOOL_CALL_CHUNK cannot change tool name or parent message.");
        }
        tool = tool.withLast(chunk);
        if (chunk.delta() != null) {
            output.add(
                    new AGUIEvents.ToolCallArgs(tool.toolCallId(), chunk.delta(), chunk.timestamp(), chunk.rawEvent()));
        }
    }

    private void normalizeReasoning(AGUIEvents.ReasoningMessageChunk chunk, List<AGUIEvent> output) {
        closeText(output);
        closeTool(output);
        if (reasoning != null
                && chunk.messageId() != null
                && !reasoning.messageId().equals(chunk.messageId())) {
            closeReasoning(output);
        }
        if (reasoning == null) {
            if (chunk.messageId() == null) {
                throw invalid("First REASONING_MESSAGE_CHUNK requires messageId.");
            }
            reasoning = new ReasoningChunkState(chunk.messageId(), chunk);
            output.add(new AGUIEvents.ReasoningMessageStart(
                    chunk.messageId(), AGUIRole.REASONING, chunk.timestamp(), chunk.rawEvent()));
        }
        reasoning = reasoning.withLast(chunk);
        if (chunk.delta() != null && !chunk.delta().isEmpty()) {
            output.add(new AGUIEvents.ReasoningMessageContent(
                    reasoning.messageId(), chunk.delta(), chunk.timestamp(), chunk.rawEvent()));
        }
        if ("".equals(chunk.delta())) {
            closeReasoning(output);
        }
    }

    private void closeAll(List<AGUIEvent> output) {
        closeText(output);
        closeTool(output);
        closeReasoning(output);
    }

    private void closeText(List<AGUIEvent> output) {
        if (text == null) {
            return;
        }
        output.add(new AGUIEvents.TextMessageEnd(
                text.messageId(), text.last().timestamp(), text.last().rawEvent()));
        text = null;
    }

    private void closeTool(List<AGUIEvent> output) {
        if (tool == null) {
            return;
        }
        output.add(new AGUIEvents.ToolCallEnd(
                tool.toolCallId(), tool.last().timestamp(), tool.last().rawEvent()));
        tool = null;
    }

    private void closeReasoning(List<AGUIEvent> output) {
        if (reasoning == null) {
            return;
        }
        output.add(new AGUIEvents.ReasoningMessageEnd(
                reasoning.messageId(),
                reasoning.last().timestamp(),
                reasoning.last().rawEvent()));
        reasoning = null;
    }

    private static AGUIProtocolException invalid(String message) {
        return new AGUIProtocolException(AGUIErrorCode.INVALID_SEQUENCE, message);
    }

    private record TextChunkState(String messageId, AGUIRole role, String name, AGUIEvents.TextMessageChunk last) {
        private TextChunkState withLast(AGUIEvents.TextMessageChunk replacement) {
            return new TextChunkState(messageId, role, name, replacement);
        }
    }

    private record ToolChunkState(
            String toolCallId, String toolCallName, String parentMessageId, AGUIEvents.ToolCallChunk last) {
        private ToolChunkState withLast(AGUIEvents.ToolCallChunk replacement) {
            return new ToolChunkState(toolCallId, toolCallName, parentMessageId, replacement);
        }
    }

    private record ReasoningChunkState(String messageId, AGUIEvents.ReasoningMessageChunk last) {
        private ReasoningChunkState withLast(AGUIEvents.ReasoningMessageChunk replacement) {
            return new ReasoningChunkState(messageId, replacement);
        }
    }
}
