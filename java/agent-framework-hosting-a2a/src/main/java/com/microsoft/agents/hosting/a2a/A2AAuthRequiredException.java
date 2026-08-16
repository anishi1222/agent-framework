// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import com.microsoft.agents.core.AgentFrameworkException;
import com.microsoft.agents.core.StateValue;
import java.util.Map;

/** Signals an explicit authentication-required task boundary. */
public final class A2AAuthRequiredException extends AgentFrameworkException {
    private static final long serialVersionUID = 1L;

    private final transient Map<String, StateValue> metadata;

    /**
     * Creates an authentication-required boundary.
     *
     * @param message sanitized prompt
     * @param metadata JSON-shaped challenge metadata
     */
    public A2AAuthRequiredException(String message, Map<String, StateValue> metadata) {
        super(HostingA2AValidation.nonBlank(message, "message"));
        this.metadata = Map.copyOf(metadata);
    }

    /** Returns challenge metadata. */
    public Map<String, StateValue> metadata() {
        return metadata;
    }
}
