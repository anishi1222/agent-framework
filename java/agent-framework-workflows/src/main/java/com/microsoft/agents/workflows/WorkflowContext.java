// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Carries immutable run metadata and an isolated pending-state buffer for one node invocation. */
public final class WorkflowContext {
    private final String runId;

    private final NodeId nodeId;

    private final int superstep;

    private final String correlationId;

    private final WorkflowState state;

    private final RunCancellation cancellation;

    private final Map<String, StateValue> metadata;

    private final LinkedHashMap<String, StateMutation> mutations = new LinkedHashMap<>();

    WorkflowContext(
            String runId,
            NodeId nodeId,
            int superstep,
            String correlationId,
            WorkflowState state,
            RunCancellation cancellation,
            Map<String, StateValue> metadata) {
        this.runId = WorkflowValidation.requireNonBlank(runId, "runId");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        if (superstep < 0) {
            throw new IllegalArgumentException("superstep must not be negative.");
        }
        this.superstep = superstep;
        this.correlationId = WorkflowValidation.requireNonBlank(correlationId, "correlationId");
        this.state = Objects.requireNonNull(state, "state");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.metadata = Map.copyOf(metadata);
    }

    /**
     * Returns the stable logical run identifier.
     *
     * @return run identifier
     */
    public String runId() {
        return runId;
    }

    /**
     * Returns the current node identifier.
     *
     * @return node identifier
     */
    public NodeId nodeId() {
        return nodeId;
    }

    /**
     * Returns the zero-based current superstep.
     *
     * @return superstep
     */
    public int superstep() {
        return superstep;
    }

    /**
     * Returns the stable invocation correlation identifier.
     *
     * @return correlation identifier
     */
    public String correlationId() {
        return correlationId;
    }

    /**
     * Returns the immutable state visible at the start of this superstep.
     *
     * @return committed state snapshot
     */
    public WorkflowState state() {
        return state;
    }

    /**
     * Reads a typed value from the committed state snapshot.
     *
     * @param key typed state key
     * @param <T> state value type
     * @return decoded value when present
     */
    public <T> Optional<T> getState(StateKey<T> key) {
        Objects.requireNonNull(key, "key");
        synchronized (this) {
            StateMutation pending = mutations.get(key.name());
            if (pending != null) {
                if (!key.equals(pending.key())) {
                    throw new StateConflictException(key.name());
                }
                return Optional.of(key.decode(pending.value()));
            }
        }
        return state.get(key);
    }

    /**
     * Buffers a typed state write for atomic commit after all branches succeed.
     *
     * @param key typed state key
     * @param value state value
     * @param <T> state value type
     */
    public synchronized <T> void setState(StateKey<T> key, T value) {
        Objects.requireNonNull(key, "key");
        mutations.put(key.name(), new StateMutation(key, key.encode(value)));
    }

    /**
     * Returns the run cancellation signal.
     *
     * @return cancellation signal
     */
    public RunCancellation cancellation() {
        return cancellation;
    }

    /**
     * Returns immutable workflow-run metadata.
     *
     * @return run metadata
     */
    public Map<String, StateValue> metadata() {
        return metadata;
    }

    synchronized Map<String, StateMutation> mutations() {
        return Map.copyOf(mutations);
    }
}
