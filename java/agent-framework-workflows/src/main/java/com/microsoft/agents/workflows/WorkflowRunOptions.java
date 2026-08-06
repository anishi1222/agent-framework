// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.StateValue;
import java.util.Map;
import java.util.Objects;

/** Configures one finite or streaming workflow run. */
public final class WorkflowRunOptions {
    private static final int DEFAULT_MAX_SUPERSTEPS = 100;

    private static final int DEFAULT_MAX_BUFFERED_EVENTS = 256;

    private final int maxSupersteps;

    private final int maxBufferedEvents;

    private final WorkflowState initialState;

    private final String runId;

    private final CheckpointStorage checkpointStorage;

    private final CheckpointKey checkpointKey;

    private final long expectedCheckpointRevision;

    private final WorkflowValueEncoder valueEncoder;

    private final Map<String, StateValue> metadata;

    private WorkflowRunOptions(Builder builder) {
        maxSupersteps = builder.maxSupersteps;
        maxBufferedEvents = builder.maxBufferedEvents;
        initialState = builder.initialState;
        runId = builder.runId;
        checkpointStorage = builder.checkpointStorage;
        checkpointKey = builder.checkpointKey;
        expectedCheckpointRevision = builder.expectedCheckpointRevision;
        valueEncoder = builder.valueEncoder;
        metadata = Map.copyOf(builder.metadata);
    }

    /**
     * Returns default run options.
     *
     * @return default options
     */
    public static WorkflowRunOptions defaults() {
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
     * Returns the maximum number of supersteps.
     *
     * @return positive maximum supersteps
     */
    public int maxSupersteps() {
        return maxSupersteps;
    }

    /**
     * Returns the maximum number of retained streaming events.
     *
     * @return positive event-buffer bound
     */
    public int maxBufferedEvents() {
        return maxBufferedEvents;
    }

    /**
     * Returns the immutable initial state.
     *
     * @return initial state snapshot
     */
    public WorkflowState initialState() {
        return initialState;
    }

    /**
     * Returns an optional caller-defined logical run identifier.
     *
     * @return run identifier, or {@code null} to generate one
     */
    public String runId() {
        return runId;
    }

    /**
     * Returns optional checkpoint storage.
     *
     * @return checkpoint storage, or {@code null}
     */
    public CheckpointStorage checkpointStorage() {
        return checkpointStorage;
    }

    /**
     * Returns the checkpoint key paired with checkpoint storage.
     *
     * @return checkpoint key, or {@code null}
     */
    public CheckpointKey checkpointKey() {
        return checkpointKey;
    }

    /**
     * Returns the expected checkpoint revision.
     *
     * @return {@code -1} for create-only or a positive replacement revision
     */
    public long expectedCheckpointRevision() {
        return expectedCheckpointRevision;
    }

    /**
     * Returns the encoder used for workflow values carried by events.
     *
     * @return non-null workflow value encoder
     */
    public WorkflowValueEncoder valueEncoder() {
        return valueEncoder;
    }

    /**
     * Returns immutable per-run metadata propagated to workflow node contexts.
     *
     * @return metadata
     */
    public Map<String, StateValue> metadata() {
        return metadata;
    }

    /** Builds immutable workflow run options. */
    public static final class Builder {
        private int maxSupersteps = DEFAULT_MAX_SUPERSTEPS;

        private int maxBufferedEvents = DEFAULT_MAX_BUFFERED_EVENTS;

        private WorkflowState initialState = WorkflowState.empty();

        private String runId;

        private CheckpointStorage checkpointStorage;

        private CheckpointKey checkpointKey;

        private long expectedCheckpointRevision = CheckpointStorage.CREATE_ONLY;

        private WorkflowValueEncoder valueEncoder = WorkflowValueEncoder.defaultEncoder();

        private Map<String, StateValue> metadata = Map.of();

        private Builder() {}

        /**
         * Sets the positive maximum superstep count.
         *
         * @param maxSupersteps maximum supersteps
         * @return this builder
         */
        public Builder maxSupersteps(int maxSupersteps) {
            if (maxSupersteps <= 0) {
                throw new IllegalArgumentException("maxSupersteps must be greater than zero.");
            }
            this.maxSupersteps = maxSupersteps;
            return this;
        }

        /**
         * Sets the positive bounded streaming-event count.
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
         * Sets the immutable initial workflow state.
         *
         * @param initialState initial state snapshot
         * @return this builder
         */
        public Builder initialState(WorkflowState initialState) {
            this.initialState = Objects.requireNonNull(initialState, "initialState");
            return this;
        }

        /**
         * Sets a stable caller-defined run identifier.
         *
         * @param runId logical run identifier
         * @return this builder
         */
        public Builder runId(String runId) {
            this.runId = WorkflowValidation.requireNonBlank(runId, "runId");
            return this;
        }

        /**
         * Enables boundary checkpoint saves with optimistic concurrency.
         *
         * @param storage checkpoint storage
         * @param key checkpoint key
         * @param expectedRevision {@code -1} for create-only or a positive replacement revision
         * @return this builder
         */
        public Builder checkpoint(CheckpointStorage storage, CheckpointKey key, long expectedRevision) {
            if (expectedRevision != CheckpointStorage.CREATE_ONLY && expectedRevision <= 0) {
                throw new IllegalArgumentException("expectedRevision must be -1 for create-only or greater than zero.");
            }
            checkpointStorage = Objects.requireNonNull(storage, "storage");
            checkpointKey = Objects.requireNonNull(key, "key");
            expectedCheckpointRevision = expectedRevision;
            return this;
        }

        /**
         * Sets the explicit encoder for workflow values carried by events.
         *
         * <p>Custom encoders should delegate values they do not own to {@link
         * WorkflowValueEncoder#defaultEncoder()}.
         *
         * @param valueEncoder workflow value encoder
         * @return this builder
         */
        public Builder valueEncoder(WorkflowValueEncoder valueEncoder) {
            this.valueEncoder = Objects.requireNonNull(valueEncoder, "valueEncoder");
            return this;
        }

        /**
         * Sets immutable metadata propagated to workflow node contexts.
         *
         * @param metadata run metadata
         * @return this builder
         */
        public Builder metadata(Map<String, StateValue> metadata) {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            return this;
        }

        /**
         * Creates immutable run options.
         *
         * @return run options
         */
        public WorkflowRunOptions build() {
            if ((checkpointStorage == null) != (checkpointKey == null)) {
                throw new IllegalStateException("checkpoint storage and key must be configured together.");
            }
            return new WorkflowRunOptions(this);
        }
    }
}
