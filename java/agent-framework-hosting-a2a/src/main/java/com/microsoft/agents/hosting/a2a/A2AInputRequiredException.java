// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import com.microsoft.agents.core.AgentFrameworkException;
import com.microsoft.agents.core.StateValue;
import java.util.Map;

/**
 * Signals an application-managed input-required task boundary.
 *
 * <p>The application must persist any continuation state in its own principal-isolated storage and
 * use the subsequent message's task/context correlation to resume. Framework tool-approval
 * continuations are not converted to this exception automatically.
 */
public final class A2AInputRequiredException extends AgentFrameworkException {
    private static final long serialVersionUID = 1L;

    private final transient Map<String, StateValue> metadata;

    /**
     * Creates an input-required boundary.
     *
     * @param message sanitized prompt
     * @param metadata JSON-shaped continuation metadata
     */
    public A2AInputRequiredException(String message, Map<String, StateValue> metadata) {
        super(HostingA2AValidation.nonBlank(message, "message"));
        this.metadata = Map.copyOf(metadata);
    }

    /** Returns application-managed continuation metadata. */
    public Map<String, StateValue> metadata() {
        return metadata;
    }
}
