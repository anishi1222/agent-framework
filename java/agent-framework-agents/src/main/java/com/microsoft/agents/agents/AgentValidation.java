// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AgentValidation {
    private AgentValidation() {}

    static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(name + " must not be blank.");
        }
        return value;
    }

    static String optionalNonBlank(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new ValidationException(name + " must not be blank when present.");
        }
        return value;
    }

    static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new ValidationException(name + " must not be null.");
        }
        return value;
    }

    static List<Message> copyMessages(List<Message> messages) {
        requireNonNull(messages, "messages");
        ArrayList<Message> copy = new ArrayList<>(messages.size());
        for (Message message : messages) {
            copy.add(requireNonNull(message, "message"));
        }
        return List.copyOf(copy);
    }

    static Map<String, StateValue> copyMetadata(Map<String, StateValue> metadata) {
        requireNonNull(metadata, "metadata");
        LinkedHashMap<String, StateValue> copy = new LinkedHashMap<>();
        metadata.forEach((key, value) ->
                copy.put(requireNonBlank(key, "metadata key"), requireNonNull(value, "metadata value")));
        return Map.copyOf(copy);
    }
}
