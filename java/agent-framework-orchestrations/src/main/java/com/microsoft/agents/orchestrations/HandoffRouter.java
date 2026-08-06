// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import java.util.ArrayList;
import java.util.List;

/** Resolves a strongly typed routing directive from one completed participant turn. */
@FunctionalInterface
public interface HandoffRouter {
    /**
     * Resolves the next routing directive.
     *
     * @param context immutable turn context
     * @return non-null directive
     */
    HandoffDirective route(HandoffTurnContext context);

    /**
     * Returns the framework function-call router.
     *
     * <p>The router recognizes executable {@code handoff} calls with a string {@code target}
     * argument, {@code handoff_to_<participantId>} calls, and {@code request_human_input} calls
     * with a string {@code prompt}. It never parses natural-language response text for routing.
     *
     * @return function-call router
     */
    static HandoffRouter functionCalls() {
        return context -> {
            List<FunctionCallContent> directives = new ArrayList<>();
            for (com.microsoft.agents.core.Message message : context.response().messages()) {
                for (Content content : message.contents()) {
                    if (content instanceof FunctionCallContent call
                            && !call.informationalOnly()
                            && (call.name().equals("handoff")
                                    || call.name().startsWith("handoff_to_")
                                    || call.name().equals("request_human_input"))) {
                        directives.add(call);
                    }
                }
            }
            if (directives.isEmpty()) {
                return HandoffCompletion.completed();
            }
            if (directives.size() > 1) {
                throw new ValidationException("A participant response must contain at most one handoff directive.");
            }
            FunctionCallContent call = directives.getFirst();
            if (call.name().equals("request_human_input")) {
                return new HandoffInputRequest(requiredString(call.arguments(), "prompt"));
            }
            if (call.name().startsWith("handoff_to_")) {
                String target = call.name().substring("handoff_to_".length());
                return new HandoffRequest(target, optionalString(call.arguments(), "reason"));
            }
            return new HandoffRequest(
                    requiredString(call.arguments(), "target"), optionalString(call.arguments(), "reason"));
        };
    }

    private static String requiredString(StateValue arguments, String name) {
        String value = optionalString(arguments, name);
        if (value == null) {
            throw new ValidationException("Handoff function argument '" + name + "' is required.");
        }
        return value;
    }

    private static String optionalString(StateValue arguments, String name) {
        if (!(arguments instanceof StateValue.ObjectValue object)) {
            if (arguments instanceof StateValue.NullValue) {
                return null;
            }
            throw new ValidationException("Handoff function arguments must be an object.");
        }
        StateValue value = object.values().get(name);
        if (value == null || value instanceof StateValue.NullValue) {
            return null;
        }
        if (!(value instanceof StateValue.StringValue string)) {
            throw new ValidationException("Handoff function argument '" + name + "' must be a string.");
        }
        return OrchestrationValidation.requireText(string.value(), name);
    }
}
