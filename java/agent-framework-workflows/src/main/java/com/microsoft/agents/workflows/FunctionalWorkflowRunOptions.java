// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.StateValue;
import java.util.Map;
import java.util.Objects;

/** Configures one finite or streaming functional workflow invocation. */
public final class FunctionalWorkflowRunOptions {
    private static final int DEFAULT_MAX_BUFFERED_EVENTS = 256;

    private final int maxBufferedEvents;

    private final String runId;

    private final CheckpointStorage checkpointStorage;

    private final CheckpointKey checkpointKey;

    private final long expectedCheckpointRevision;

    private final Map<String, StateValue> metadata;

    private FunctionalWorkflowRunOptions(Builder builder) {
        maxBufferedEvents = builder.maxBufferedEvents;
        runId = builder.runId;
        checkpointStorage = builder.checkpointStorage;
        checkpointKey = builder.checkpointKey;
        expectedCheckpointRevision = builder.expectedCheckpointRevision;
        metadata = Map.copyOf(builder.metadata);
    }

    /**
     * Returns default options.
     *
     * @return default options
     */
    public static FunctionalWorkflowRunOptions defaults() {
        return builder().build();
    }

    /**
     * Creates an options builder.
     *
     * @return empty builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the maximum retained streaming event count.
     *
     * @return positive event-buffer bound
     */
    public int maxBufferedEvents() {
        return maxBufferedEvents;
    }

    /**
     * Returns an optional caller-defined logical run identifier.
     *
     * @return run identifier, or {@code null}
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
     * Returns immutable run metadata.
     *
     * @return metadata
     */
    public Map<String, StateValue> metadata() {
        return metadata;
    }

    /** Builds immutable functional workflow options. */
    public static final class Builder {
        private int maxBufferedEvents = DEFAULT_MAX_BUFFERED_EVENTS;

        private String runId;

        private CheckpointStorage checkpointStorage;

        private CheckpointKey checkpointKey;

        private long expectedCheckpointRevision = CheckpointStorage.CREATE_ONLY;

        private Map<String, StateValue> metadata = Map.of();

        private Builder() {}

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
         * Sets immutable run metadata exposed through the run context.
         *
         * @param metadata run metadata
         * @return this builder
         */
        public Builder metadata(Map<String, StateValue> metadata) {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            return this;
        }

        /**
         * Creates immutable options.
         *
         * @return run options
         */
        public FunctionalWorkflowRunOptions build() {
            if ((checkpointStorage == null) != (checkpointKey == null)) {
                throw new IllegalStateException("checkpoint storage and key must be configured together.");
            }
            return new FunctionalWorkflowRunOptions(this);
        }
    }
}
