// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Represents an immutable workflow snapshot at a committed superstep boundary. */
public final class WorkflowCheckpoint {
    private final String workflowId;

    private final String checkpointId;

    private final long revision;

    private final String previousCheckpointId;

    private final WorkflowCheckpointStatus status;

    private final List<NodeId> pendingExecutors;

    private final List<BufferedInput> bufferedInputs;

    private final Map<NodeId, Long> fanInNextEpochs;

    private final Integer workflowSchemaVersion;

    private final String graphFingerprint;

    private final String runId;

    private final Integer superstep;

    private final WorkflowState state;

    /**
     * Creates a complete runtime checkpoint.
     *
     * @param workflowId stable workflow identity
     * @param checkpointId stable checkpoint identity
     * @param revision zero for an unstored draft or a positive stored revision
     * @param previousCheckpointId optional previous checkpoint identity
     * @param status checkpoint status
     * @param pendingExecutors pending executor identifiers
     * @param bufferedInputs pending and fan-in buffered inputs
     * @param workflowSchemaVersion positive application schema version
     * @param graphFingerprint deterministic graph fingerprint
     * @param runId logical run identifier
     * @param superstep next zero-based superstep
     * @param state committed state snapshot
     */
    public WorkflowCheckpoint(
            String workflowId,
            String checkpointId,
            long revision,
            String previousCheckpointId,
            WorkflowCheckpointStatus status,
            List<NodeId> pendingExecutors,
            List<BufferedInput> bufferedInputs,
            Integer workflowSchemaVersion,
            String graphFingerprint,
            String runId,
            Integer superstep,
            WorkflowState state) {
        this(
                workflowId,
                checkpointId,
                revision,
                previousCheckpointId,
                status,
                pendingExecutors,
                bufferedInputs,
                Map.of(),
                workflowSchemaVersion,
                graphFingerprint,
                runId,
                superstep,
                state);
    }

    /**
     * Creates a complete runtime checkpoint with fan-in epoch state.
     *
     * <p>Each {@code fanInNextEpochs} value is the zero-based epoch that the fan-in group identified
     * by its unique target node will release next. A buffered incomplete epoch uses this value; a
     * pending, already released {@link FanInInput} uses the immediately preceding value.
     *
     * @param workflowId stable workflow identity
     * @param checkpointId stable checkpoint identity
     * @param revision zero for an unstored draft or a positive stored revision
     * @param previousCheckpointId optional previous checkpoint identity
     * @param status checkpoint status
     * @param pendingExecutors pending executor identifiers
     * @param bufferedInputs pending and fan-in buffered inputs
     * @param fanInNextEpochs next epoch to release, keyed by the fan-in target node
     * @param workflowSchemaVersion positive application schema version
     * @param graphFingerprint deterministic graph fingerprint
     * @param runId logical run identifier
     * @param superstep next zero-based superstep
     * @param state committed state snapshot
     */
    public WorkflowCheckpoint(
            String workflowId,
            String checkpointId,
            long revision,
            String previousCheckpointId,
            WorkflowCheckpointStatus status,
            List<NodeId> pendingExecutors,
            List<BufferedInput> bufferedInputs,
            Map<NodeId, Long> fanInNextEpochs,
            Integer workflowSchemaVersion,
            String graphFingerprint,
            String runId,
            Integer superstep,
            WorkflowState state) {
        this.workflowId = WorkflowValidation.requireNonBlank(workflowId, "workflowId");
        this.checkpointId = WorkflowValidation.requireNonBlank(checkpointId, "checkpointId");
        if (revision < 0) {
            throw new WorkflowValidationException("checkpoint revision must not be negative.");
        }
        this.revision = revision;
        this.previousCheckpointId = previousCheckpointId == null
                ? null
                : WorkflowValidation.requireNonBlank(previousCheckpointId, "previousCheckpointId");
        this.status = Objects.requireNonNull(status, "status");
        this.pendingExecutors = copyPending(pendingExecutors);
        this.bufferedInputs = copyBuffered(bufferedInputs);
        this.fanInNextEpochs = copyFanInNextEpochs(fanInNextEpochs);
        if (workflowSchemaVersion != null && workflowSchemaVersion <= 0) {
            throw new WorkflowValidationException("workflowSchemaVersion must be positive when present.");
        }
        this.workflowSchemaVersion = workflowSchemaVersion;
        this.graphFingerprint = optionalNonBlank(graphFingerprint, "graphFingerprint");
        this.runId = optionalNonBlank(runId, "runId");
        if (superstep != null && superstep < 0) {
            throw new WorkflowValidationException("superstep must not be negative when present.");
        }
        this.superstep = superstep;
        this.state = Objects.requireNonNull(state, "state");
        boolean extended =
                workflowSchemaVersion != null || graphFingerprint != null || runId != null || superstep != null;
        if (extended
                && (workflowSchemaVersion == null || graphFingerprint == null || runId == null || superstep == null)) {
            throw new WorkflowValidationException(
                    "Runtime checkpoint identity fields must either all be present or all be absent.");
        }
    }

    /**
     * Creates the portable version-1 checkpoint shape used by the checked-in golden fixture.
     *
     * @param workflowId workflow identity
     * @param checkpointId checkpoint identity
     * @param revision positive stored revision
     * @param previousCheckpointId optional previous checkpoint identity
     * @param status checkpoint status
     * @param pendingExecutors pending executor identifiers
     * @param bufferedInputs buffered inputs
     * @return portable checkpoint
     */
    public static WorkflowCheckpoint portableV1(
            String workflowId,
            String checkpointId,
            long revision,
            String previousCheckpointId,
            WorkflowCheckpointStatus status,
            List<NodeId> pendingExecutors,
            List<BufferedInput> bufferedInputs) {
        return portableV1(
                workflowId,
                checkpointId,
                revision,
                previousCheckpointId,
                status,
                pendingExecutors,
                bufferedInputs,
                Map.of());
    }

    /**
     * Creates the portable version-1 checkpoint shape with fan-in epoch state.
     *
     * @param workflowId workflow identity
     * @param checkpointId checkpoint identity
     * @param revision positive stored revision
     * @param previousCheckpointId optional previous checkpoint identity
     * @param status checkpoint status
     * @param pendingExecutors pending executor identifiers
     * @param bufferedInputs buffered inputs
     * @param fanInNextEpochs next epoch to release, keyed by the fan-in target node
     * @return portable checkpoint
     */
    public static WorkflowCheckpoint portableV1(
            String workflowId,
            String checkpointId,
            long revision,
            String previousCheckpointId,
            WorkflowCheckpointStatus status,
            List<NodeId> pendingExecutors,
            List<BufferedInput> bufferedInputs,
            Map<NodeId, Long> fanInNextEpochs) {
        if (revision <= 0) {
            throw new WorkflowValidationException("portable checkpoint revision must be positive.");
        }
        return new WorkflowCheckpoint(
                workflowId,
                checkpointId,
                revision,
                previousCheckpointId,
                status,
                pendingExecutors,
                bufferedInputs,
                fanInNextEpochs,
                null,
                null,
                null,
                null,
                WorkflowState.empty());
    }

    /**
     * Returns the stable workflow identity.
     *
     * @return workflow identity
     */
    public String workflowId() {
        return workflowId;
    }

    /**
     * Returns the checkpoint identity.
     *
     * @return checkpoint identity
     */
    public String checkpointId() {
        return checkpointId;
    }

    /**
     * Returns the storage revision, or zero for an unstored draft.
     *
     * @return checkpoint revision
     */
    public long revision() {
        return revision;
    }

    /**
     * Returns the previous checkpoint identity.
     *
     * @return previous checkpoint identity, or {@code null}
     */
    public String previousCheckpointId() {
        return previousCheckpointId;
    }

    /**
     * Returns the checkpoint status.
     *
     * @return checkpoint status
     */
    public WorkflowCheckpointStatus status() {
        return status;
    }

    /**
     * Returns pending executor identifiers in lexical order.
     *
     * @return pending executor identifiers
     */
    public List<NodeId> pendingExecutors() {
        return pendingExecutors;
    }

    /**
     * Returns buffered inputs ordered by target then source.
     *
     * @return buffered inputs
     */
    public List<BufferedInput> bufferedInputs() {
        return bufferedInputs;
    }

    /**
     * Returns the next zero-based epoch each fan-in group will release, keyed by its target node.
     *
     * <p>The target uniquely identifies a fan-in group. An absent target is equivalent to next
     * epoch zero.
     *
     * @return immutable map in lexical target order
     */
    public Map<NodeId, Long> fanInNextEpochs() {
        return fanInNextEpochs;
    }

    /**
     * Returns the optional application workflow schema version.
     *
     * @return schema version, or {@code null} for the portable fixture shape
     */
    public Integer workflowSchemaVersion() {
        return workflowSchemaVersion;
    }

    /**
     * Returns the optional graph fingerprint.
     *
     * @return graph fingerprint, or {@code null}
     */
    public String graphFingerprint() {
        return graphFingerprint;
    }

    /**
     * Returns the optional logical run identity.
     *
     * @return run identity, or {@code null}
     */
    public String runId() {
        return runId;
    }

    /**
     * Returns the optional next superstep.
     *
     * @return next superstep, or {@code null}
     */
    public Integer superstep() {
        return superstep;
    }

    /**
     * Returns the committed state snapshot.
     *
     * @return committed state
     */
    public WorkflowState state() {
        return state;
    }

    /**
     * Returns a copy with the assigned storage revision.
     *
     * @param assignedRevision positive storage revision
     * @return revised checkpoint
     */
    public WorkflowCheckpoint withRevision(long assignedRevision) {
        if (assignedRevision <= 0) {
            throw new WorkflowValidationException("assignedRevision must be positive.");
        }
        return new WorkflowCheckpoint(
                workflowId,
                checkpointId,
                assignedRevision,
                previousCheckpointId,
                status,
                pendingExecutors,
                bufferedInputs,
                fanInNextEpochs,
                workflowSchemaVersion,
                graphFingerprint,
                runId,
                superstep,
                state);
    }

    /**
     * Reports whether this checkpoint contains runtime identity fields.
     *
     * @return {@code true} for a resumable runtime checkpoint
     */
    public boolean isRuntimeCheckpoint() {
        return workflowSchemaVersion != null;
    }

    private static List<NodeId> copyPending(List<NodeId> pending) {
        Objects.requireNonNull(pending, "pendingExecutors");
        ArrayList<NodeId> sorted = new ArrayList<>(pending.size());
        HashSet<NodeId> unique = new HashSet<>();
        for (NodeId nodeId : pending) {
            NodeId checked = Objects.requireNonNull(nodeId, "pending executor");
            if (!unique.add(checked)) {
                throw new WorkflowValidationException("Duplicate pending executor '" + checked + "'.");
            }
            sorted.add(checked);
        }
        sorted.sort(NodeId::compareTo);
        return List.copyOf(sorted);
    }

    private static List<BufferedInput> copyBuffered(List<BufferedInput> buffered) {
        Objects.requireNonNull(buffered, "bufferedInputs");
        ArrayList<BufferedInput> sorted = new ArrayList<>(buffered.size());
        HashSet<BufferedInputIdentity> unique = new HashSet<>();
        for (BufferedInput input : buffered) {
            BufferedInput checked = Objects.requireNonNull(input, "buffered input");
            BufferedInputIdentity identity = new BufferedInputIdentity(checked.targetId(), checked.sourceId());
            if (!unique.add(identity)) {
                throw new WorkflowValidationException("Duplicate buffered input for target/source '" + identity + "'.");
            }
            sorted.add(checked);
        }
        sorted.sort(BufferedInput::compareTo);
        return List.copyOf(sorted);
    }

    private static Map<NodeId, Long> copyFanInNextEpochs(Map<NodeId, Long> epochs) {
        Objects.requireNonNull(epochs, "fanInNextEpochs");
        TreeMap<NodeId, Long> sorted = new TreeMap<>();
        epochs.forEach((targetId, nextEpoch) -> {
            NodeId checkedTarget = Objects.requireNonNull(targetId, "fan-in target id");
            long checkedEpoch = Objects.requireNonNull(nextEpoch, "fan-in next epoch");
            if (checkedEpoch < 0) {
                throw new WorkflowValidationException(
                        "Fan-in next epoch for target '" + checkedTarget + "' must not be negative.");
            }
            sorted.put(checkedTarget, checkedEpoch);
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static String optionalNonBlank(String value, String name) {
        return value == null ? null : WorkflowValidation.requireNonBlank(value, name);
    }

    private record BufferedInputIdentity(NodeId targetId, String sourceId) {}
}
