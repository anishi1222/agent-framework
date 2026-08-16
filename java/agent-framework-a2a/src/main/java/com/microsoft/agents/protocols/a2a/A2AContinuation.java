// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.StateValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Preserves remote task/context correlation across framework agent calls.
 *
 * @param taskId remote task identifier
 * @param contextId remote context identifier
 * @param state last observed task state
 */
public record A2AContinuation(String taskId, String contextId, TaskState state) {
    /** Creates a validated continuation. */
    public A2AContinuation {
        taskId = A2AValidation.nonBlank(taskId, "taskId");
        contextId = A2AValidation.nonBlank(contextId, "contextId");
        state = Objects.requireNonNull(state, "state");
    }

    /**
     * Encodes the continuation as framework-owned JSON-shaped state.
     *
     * @return continuation value
     */
    public StateValue.ObjectValue toStateValue() {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        values.put("kind", StateValue.string("a2a-continuation-v1"));
        values.put("taskId", StateValue.string(taskId));
        values.put("contextId", StateValue.string(contextId));
        values.put("state", StateValue.string(state.name()));
        return StateValue.object(values);
    }

    /**
     * Decodes a continuation value.
     *
     * @param value JSON-shaped value
     * @return continuation
     */
    public static A2AContinuation fromStateValue(StateValue value) {
        if (!(value instanceof StateValue.ObjectValue object)) {
            throw new com.microsoft.agents.core.ValidationException("A2A continuation must be an object.");
        }
        Map<String, StateValue> values = object.values();
        String kind = string(values, "kind");
        if (!"a2a-continuation-v1".equals(kind)) {
            throw new com.microsoft.agents.core.ValidationException("Unsupported A2A continuation kind.");
        }
        TaskState state;
        try {
            state = TaskState.valueOf(string(values, "state"));
        } catch (IllegalArgumentException exception) {
            throw new com.microsoft.agents.core.ValidationException(
                    "A2A continuation contains an unknown state.", exception);
        }
        return new A2AContinuation(string(values, "taskId"), string(values, "contextId"), state);
    }

    private static String string(Map<String, StateValue> values, String name) {
        StateValue value = values.get(name);
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw new com.microsoft.agents.core.ValidationException(
                "A2A continuation member '" + name + "' must be a string.");
    }
}
