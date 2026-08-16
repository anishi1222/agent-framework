// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import com.microsoft.agents.core.Message;
import java.util.List;

/**
 * Represents an immutable partition of a conversation into query and response messages.
 *
 * @param queryMessages ordered messages before the response boundary
 * @param responseMessages ordered messages at and after the response boundary
 */
public record ConversationSplit(List<Message> queryMessages, List<Message> responseMessages) {
    /** Creates a validated immutable conversation split. */
    public ConversationSplit {
        queryMessages = EvaluationValidation.copyList(queryMessages, "queryMessages");
        responseMessages = EvaluationValidation.copyList(responseMessages, "responseMessages");
    }

    /**
     * Splits a conversation before the message at the supplied index.
     *
     * @param conversation full conversation
     * @param responseStartIndex index of the first response message, from zero through the
     *     conversation size
     * @return immutable conversation split
     */
    public static ConversationSplit at(List<Message> conversation, int responseStartIndex) {
        List<Message> checked = EvaluationValidation.copyList(conversation, "conversation");
        if (responseStartIndex < 0 || responseStartIndex > checked.size()) {
            throw new IllegalArgumentException("responseStartIndex must be between zero and the conversation size.");
        }
        return new ConversationSplit(
                checked.subList(0, responseStartIndex), checked.subList(responseStartIndex, checked.size()));
    }
}
