// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import java.util.List;

/**
 * Provides deterministic conversation splitters for message and tool-call boundaries.
 */
public final class ConversationSplitters {
    private static final ConversationSplitter LAST_TURN = conversation -> {
        List<Message> checked = EvaluationValidation.copyList(conversation, "conversation");
        int boundary = 0;
        for (int index = 0; index < checked.size(); index++) {
            if (Role.USER.equals(checked.get(index).role())) {
                boundary = index + 1;
            }
        }
        return ConversationSplit.at(checked, boundary);
    };

    private static final ConversationSplitter FULL = conversation -> {
        List<Message> checked = EvaluationValidation.copyList(conversation, "conversation");
        for (int index = 0; index < checked.size(); index++) {
            if (Role.USER.equals(checked.get(index).role())) {
                return ConversationSplit.at(checked, index + 1);
            }
        }
        return ConversationSplit.at(checked, 0);
    };

    private ConversationSplitters() {}

    /**
     * Returns a splitter that places the boundary after the last user message.
     *
     * @return shared last-turn splitter
     */
    public static ConversationSplitter lastTurn() {
        return LAST_TURN;
    }

    /**
     * Returns a splitter that places the boundary after the first user message.
     *
     * @return shared full-trajectory splitter
     */
    public static ConversationSplitter full() {
        return FULL;
    }

    /**
     * Returns a splitter for a fixed message boundary.
     *
     * @param responseStartIndex index of the first response message
     * @return fixed-boundary splitter
     */
    public static ConversationSplitter atMessageBoundary(int responseStartIndex) {
        if (responseStartIndex < 0) {
            throw new IllegalArgumentException("responseStartIndex must not be negative.");
        }
        return conversation -> ConversationSplit.at(conversation, responseStartIndex);
    }

    /**
     * Returns a splitter that places the boundary before the first function-call message.
     *
     * @return first-tool-call splitter
     */
    public static ConversationSplitter beforeFirstToolCall() {
        return conversation -> {
            List<Message> checked = EvaluationValidation.copyList(conversation, "conversation");
            for (int index = 0; index < checked.size(); index++) {
                if (checked.get(index).contents().stream().anyMatch(FunctionCallContent.class::isInstance)) {
                    return ConversationSplit.at(checked, index);
                }
            }
            throw new IllegalArgumentException("The conversation does not contain a function call.");
        };
    }

    /**
     * Returns a splitter that places the boundary before the first call to a named tool.
     *
     * @param toolName exact non-blank tool name
     * @return named-tool-call splitter
     */
    public static ConversationSplitter beforeToolCall(String toolName) {
        String checkedName = EvaluationValidation.requireNonBlank(toolName, "toolName");
        return conversation -> {
            List<Message> checked = EvaluationValidation.copyList(conversation, "conversation");
            for (int index = 0; index < checked.size(); index++) {
                boolean found = checked.get(index).contents().stream()
                        .filter(FunctionCallContent.class::isInstance)
                        .map(FunctionCallContent.class::cast)
                        .anyMatch(call -> checkedName.equals(call.name()));
                if (found) {
                    return ConversationSplit.at(checked, index);
                }
            }
            throw new IllegalArgumentException("The conversation does not contain tool call '" + checkedName + "'.");
        };
    }

    /**
     * Returns a splitter that places the boundary before a function call with a correlation ID.
     *
     * @param callId exact non-blank function-call identifier
     * @return call-identifier splitter
     */
    public static ConversationSplitter beforeToolCallId(String callId) {
        String checkedId = EvaluationValidation.requireNonBlank(callId, "callId");
        return conversation -> {
            List<Message> checked = EvaluationValidation.copyList(conversation, "conversation");
            for (int index = 0; index < checked.size(); index++) {
                boolean found = checked.get(index).contents().stream()
                        .filter(FunctionCallContent.class::isInstance)
                        .map(FunctionCallContent.class::cast)
                        .anyMatch(call -> checkedId.equals(call.callId()));
                if (found) {
                    return ConversationSplit.at(checked, index);
                }
            }
            throw new IllegalArgumentException("The conversation does not contain tool call ID '" + checkedId + "'.");
        };
    }

    /**
     * Returns a splitter that places the boundary after a correlated function-result message.
     *
     * @param callId exact non-blank function-call identifier
     * @return tool-result splitter
     */
    public static ConversationSplitter afterToolResult(String callId) {
        String checkedId = EvaluationValidation.requireNonBlank(callId, "callId");
        return conversation -> {
            List<Message> checked = EvaluationValidation.copyList(conversation, "conversation");
            for (int index = 0; index < checked.size(); index++) {
                boolean found = checked.get(index).contents().stream()
                        .filter(FunctionResultContent.class::isInstance)
                        .map(FunctionResultContent.class::cast)
                        .anyMatch(result -> checkedId.equals(result.callId()));
                if (found) {
                    return ConversationSplit.at(checked, index + 1);
                }
            }
            throw new IllegalArgumentException("The conversation does not contain tool result ID '" + checkedId + "'.");
        };
    }
}
