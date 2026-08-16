// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.TextContent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class OrchestrationMessages {
    private OrchestrationMessages() {}

    static List<Message> appendResponse(
            List<Message> transcript, List<Message> invocationInput, AgentResponse<?> response) {
        ArrayList<Message> merged = new ArrayList<>(transcript);
        List<Message> responseMessages = response.messages();
        int prefix = commonPrefix(responseMessages, invocationInput);
        Set<String> existingIds = new HashSet<>();
        for (Message message : merged) {
            if (message.messageId() != null) {
                existingIds.add(message.messageId());
            }
        }
        for (int index = prefix; index < responseMessages.size(); index++) {
            Message message = responseMessages.get(index);
            if (message.messageId() == null || existingIds.add(message.messageId())) {
                merged.add(message);
            }
        }
        return List.copyOf(merged);
    }

    static List<Message> responseAsNextInput(OrchestrationParticipant participant, AgentResponse<?> response) {
        ArrayList<Message> transformed = new ArrayList<>(response.messages().size());
        for (Message message : response.messages()) {
            Role role = Role.ASSISTANT.equals(message.role()) ? Role.USER : message.role();
            String author = message.authorName() == null ? participant.id() : message.authorName();
            transformed.add(new Message(role, message.contents(), author, message.messageId(), message.metadata()));
        }
        return List.copyOf(transformed);
    }

    static List<Message> incrementalMessages(List<Message> history, List<Message> requested) {
        int prefix = commonPrefix(requested, history);
        if (prefix == history.size()) {
            return List.copyOf(requested.subList(prefix, requested.size()));
        }
        return requested;
    }

    static List<Message> textOnly(List<Message> messages) {
        ArrayList<Message> cleaned = new ArrayList<>();
        for (Message message : messages) {
            List<Content> text = message.contents().stream()
                    .filter(TextContent.class::isInstance)
                    .toList();
            if (!text.isEmpty()) {
                cleaned.add(new Message(
                        message.role(), text, message.authorName(), message.messageId(), message.metadata()));
            }
        }
        return List.copyOf(cleaned);
    }

    private static int commonPrefix(List<Message> left, List<Message> right) {
        int limit = Math.min(left.size(), right.size());
        int index = 0;
        while (index < limit && left.get(index).equals(right.get(index))) {
            index++;
        }
        return index;
    }
}
