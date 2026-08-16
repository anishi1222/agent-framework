// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/** Configures one finite or streaming orchestration run. */
public final class OrchestrationRunOptions {
    private static final int DEFAULT_MAX_BUFFERED_EVENTS = 256;

    private final String runId;

    private final RunOptions agentRunOptions;

    private final int maxBufferedEvents;

    private final ExecutorService participantExecutor;

    private final OrchestrationSessionPolicy sessionPolicy;

    private final Map<String, StateValue> metadata;

    private final List<OrchestrationEventListener> eventListeners;

    private OrchestrationRunOptions(Builder builder) {
        runId = builder.runId;
        agentRunOptions = builder.agentRunOptions;
        maxBufferedEvents = builder.maxBufferedEvents;
        participantExecutor = builder.participantExecutor;
        sessionPolicy = builder.sessionPolicy;
        metadata = Map.copyOf(builder.metadata);
        eventListeners = List.copyOf(builder.eventListeners);
    }

    /**
     * Returns default run options.
     *
     * @return immutable defaults
     */
    public static OrchestrationRunOptions defaults() {
        return builder().build();
    }

    /**
     * Creates a run-options builder.
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the optional caller-selected logical run identifier.
     *
     * @return run identifier, or {@code null} to generate one
     */
    public String runId() {
        return runId;
    }

    /**
     * Returns options propagated to every underlying agent.
     *
     * @return agent run options
     */
    public RunOptions agentRunOptions() {
        return agentRunOptions;
    }

    /**
     * Returns the positive bounded event-buffer size.
     *
     * @return maximum retained streaming events
     */
    public int maxBufferedEvents() {
        return maxBufferedEvents;
    }

    /**
     * Returns the optional caller-owned participant executor.
     *
     * <p>The framework never closes this executor. When absent, one run-local virtual-thread executor
     * is created and closed by the orchestration runtime.
     *
     * @return caller executor, or {@code null}
     */
    public ExecutorService participantExecutor() {
        return participantExecutor;
    }

    /**
     * Returns the session policy for session-aware chat agents.
     *
     * @return session policy
     */
    public OrchestrationSessionPolicy sessionPolicy() {
        return sessionPolicy;
    }

    /**
     * Returns immutable orchestration metadata propagated to agent run contexts.
     *
     * @return metadata
     */
    public Map<String, StateValue> metadata() {
        return metadata;
    }

    /**
     * Returns optional event listeners in registration order.
     *
     * @return immutable listener list
     */
    public List<OrchestrationEventListener> eventListeners() {
        return eventListeners;
    }

    /** Builds immutable {@link OrchestrationRunOptions}. */
    public static final class Builder {
        private String runId;

        private RunOptions agentRunOptions = RunOptions.empty();

        private int maxBufferedEvents = DEFAULT_MAX_BUFFERED_EVENTS;

        private ExecutorService participantExecutor;

        private OrchestrationSessionPolicy sessionPolicy = OrchestrationSessionPolicy.ISOLATED;

        private Map<String, StateValue> metadata = Map.of();

        private final ArrayList<OrchestrationEventListener> eventListeners = new ArrayList<>();

        private Builder() {}

        /**
         * Sets a stable caller-defined run identifier.
         *
         * @param runId run identifier
         * @return this builder
         */
        public Builder runId(String runId) {
            this.runId = OrchestrationValidation.requireId(runId, "runId");
            return this;
        }

        /**
         * Sets options propagated to underlying agents.
         *
         * @param agentRunOptions agent options
         * @return this builder
         */
        public Builder agentRunOptions(RunOptions agentRunOptions) {
            this.agentRunOptions = Objects.requireNonNull(agentRunOptions, "agentRunOptions");
            return this;
        }

        /**
         * Sets the positive streaming event-buffer bound.
         *
         * @param maxBufferedEvents maximum retained events
         * @return this builder
         */
        public Builder maxBufferedEvents(int maxBufferedEvents) {
            if (maxBufferedEvents <= 0) {
                throw new IllegalArgumentException("maxBufferedEvents must be greater than zero.");
            }
            this.maxBufferedEvents = maxBufferedEvents;
            return this;
        }

        /**
         * Sets a caller-owned executor used to dispatch participant invocations.
         *
         * @param participantExecutor caller-owned executor
         * @return this builder
         */
        public Builder participantExecutor(ExecutorService participantExecutor) {
            this.participantExecutor = Objects.requireNonNull(participantExecutor, "participantExecutor");
            return this;
        }

        /**
         * Sets the session policy for session-aware chat agents.
         *
         * @param sessionPolicy session policy
         * @return this builder
         */
        public Builder sessionPolicy(OrchestrationSessionPolicy sessionPolicy) {
            this.sessionPolicy = Objects.requireNonNull(sessionPolicy, "sessionPolicy");
            return this;
        }

        /**
         * Sets immutable orchestration metadata.
         *
         * @param metadata metadata values
         * @return this builder
         */
        public Builder metadata(Map<String, StateValue> metadata) {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            return this;
        }

        /**
         * Adds one optional event observer.
         *
         * @param eventListener event observer
         * @return this builder
         */
        public Builder eventListener(OrchestrationEventListener eventListener) {
            eventListeners.add(Objects.requireNonNull(eventListener, "eventListener"));
            return this;
        }

        /**
         * Creates immutable run options.
         *
         * @return run options
         */
        public OrchestrationRunOptions build() {
            return new OrchestrationRunOptions(this);
        }
    }
}
