// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.openai;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunOptions;
import java.util.List;

/**
 * Represents one validated OpenAI Responses request mapped to generic hosting values.
 *
 * @param messages ordered framework messages
 * @param options provider-neutral mapped run options
 * @param requestInfo validated request settings
 * @param streaming whether SSE was requested
 * @param previousResponseId optional immutable response-chain pointer
 * @param conversationId optional stable mutable conversation identifier
 * @param store requested OpenAI storage flag
 */
public record OpenAIResponsesRunRequest(
        List<Message> messages,
        RunOptions options,
        OpenAIResponsesRequestInfo requestInfo,
        boolean streaming,
        String previousResponseId,
        String conversationId,
        boolean store) {
    /** Creates a validated immutable run request. */
    public OpenAIResponsesRunRequest {
        messages = List.copyOf(java.util.Objects.requireNonNull(messages, "messages"));
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty.");
        }
        java.util.Objects.requireNonNull(options, "options");
        java.util.Objects.requireNonNull(requestInfo, "requestInfo");
        previousResponseId = optionalNonBlank(previousResponseId, "previousResponseId");
        conversationId = optionalNonBlank(conversationId, "conversationId");
        if (previousResponseId != null && conversationId != null) {
            throw new IllegalArgumentException("previousResponseId and conversationId are mutually exclusive.");
        }
    }

    private static String optionalNonBlank(String value, String name) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
