// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Configures the framework {@link A2AAgent} adapter. */
public final class A2AAgentOptions {
    /** Run-options metadata key containing an {@link A2AContinuation}. */
    public static final String CONTINUATION_METADATA_KEY = "a2a.continuation";

    private final AgentMetadata metadata;

    private final List<String> inputModes;

    private final List<String> outputModes;

    private final A2ALimits limits;

    private final boolean closeClient;

    private A2AAgentOptions(Builder builder) {
        metadata = Objects.requireNonNull(builder.metadata, "metadata");
        inputModes = A2AValidation.strings(builder.inputModes, "inputModes", false);
        outputModes = A2AValidation.strings(builder.outputModes, "outputModes", false);
        limits = Objects.requireNonNull(builder.limits, "limits");
        closeClient = builder.closeClient;
    }

    /**
     * Creates adapter options.
     *
     * @param metadata agent metadata
     * @return builder
     */
    public static Builder builder(AgentMetadata metadata) {
        return new Builder(metadata);
    }

    /** Returns agent metadata. */
    public AgentMetadata metadata() {
        return metadata;
    }

    /** Returns accepted input modes. */
    public List<String> inputModes() {
        return inputModes;
    }

    /** Returns accepted output modes. */
    public List<String> outputModes() {
        return outputModes;
    }

    /** Returns conversion limits. */
    public A2ALimits limits() {
        return limits;
    }

    /** Reports whether closing the adapter closes the client. */
    public boolean closeClient() {
        return closeClient;
    }

    /**
     * Returns run options carrying a continuation while preserving all existing values.
     *
     * @param options base options
     * @param continuation continuation
     * @return copied run options
     */
    public static RunOptions withContinuation(RunOptions options, A2AContinuation continuation) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(continuation, "continuation");
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(options.metadata());
        metadata.put(CONTINUATION_METADATA_KEY, continuation.toStateValue());
        return new RunOptions(options.maxIterations(), options.maxFunctionCalls(), metadata);
    }

    /** Builds immutable {@link A2AAgentOptions}. */
    public static final class Builder {
        private final AgentMetadata metadata;

        private List<String> inputModes =
                List.of("text/plain", "application/json", "image/*", "audio/*", "application/*");

        private List<String> outputModes =
                List.of("text/plain", "application/json", "image/*", "audio/*", "application/*");

        private A2ALimits limits = A2ALimits.defaults();

        private boolean closeClient;

        private Builder(AgentMetadata metadata) {
            this.metadata = metadata;
        }

        /** Sets accepted remote input modes. */
        public Builder inputModes(List<String> values) {
            inputModes = values;
            return this;
        }

        /** Sets accepted remote output modes. */
        public Builder outputModes(List<String> values) {
            outputModes = values;
            return this;
        }

        /** Sets conversion limits. */
        public Builder limits(A2ALimits value) {
            limits = value;
            return this;
        }

        /** Sets whether closing the adapter closes the supplied client. */
        public Builder closeClient(boolean value) {
            closeClient = value;
            return this;
        }

        /** Creates immutable options. */
        public A2AAgentOptions build() {
            return new A2AAgentOptions(this);
        }
    }
}
