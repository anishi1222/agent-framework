// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.FunctionCallContent;

/**
 * Creates durable invocation identifiers at the provider-to-tool boundary.
 */
@FunctionalInterface
public interface InvocationIdFactory {
    /**
     * Creates the identifier for one provider function-call occurrence.
     *
     * @param logicalRunId non-blank logical run identifier
     * @param call provider-neutral function call
     * @return stable invocation identifier
     */
    InvocationId create(String logicalRunId, FunctionCallContent call);

    /**
     * Returns the default identifier factory.
     *
     * <p>The default first honors a string {@code invocationId} in call metadata and otherwise
     * derives {@code logicalRunId:callId}. Providers that can reuse call ids for distinct
     * occurrences must supply a durable provider occurrence identifier in metadata or configure a
     * custom factory.
     *
     * @return default factory
     */
    static InvocationIdFactory defaultFactory() {
        return (logicalRunId, call) -> {
            var metadataValue = call.metadata().get("invocationId");
            if (metadataValue instanceof com.microsoft.agents.core.StateValue.StringValue stringValue) {
                return new InvocationId(stringValue.value());
            }
            return new InvocationId(ToolValidation.requireNonBlank(logicalRunId, "logicalRunId") + ":" + call.callId());
        };
    }
}
