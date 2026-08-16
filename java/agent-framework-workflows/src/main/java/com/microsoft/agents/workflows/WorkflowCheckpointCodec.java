// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.DocumentKind;
import com.microsoft.agents.core.EncodedState;
import com.microsoft.agents.core.SerializationError;
import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.StateEnvelope;
import com.microsoft.agents.core.StateSerializer;
import com.microsoft.agents.core.StateValue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Encodes and decodes the Java version-1 workflow-checkpoint envelope. */
public final class WorkflowCheckpointCodec {
    /** Current workflow-checkpoint payload version. */
    public static final int PAYLOAD_VERSION = 1;

    private final StateSerializer serializer;

    /**
     * Creates a workflow checkpoint codec backed by the core state serializer.
     *
     * @param serializer safe deterministic state serializer
     */
    public WorkflowCheckpointCodec(StateSerializer serializer) {
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    /**
     * Encodes a stored checkpoint using the version-1 envelope.
     *
     * @param checkpoint checkpoint with a positive revision
     * @return compact deterministic UTF-8 JSON
     */
    public byte[] encode(WorkflowCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (checkpoint.revision() <= 0) {
            throw new WorkflowCheckpointException("Only a stored checkpoint with a positive revision can be encoded.");
        }
        StateEnvelope envelope =
                StateEnvelope.of(DocumentKind.WORKFLOW_CHECKPOINT, PAYLOAD_VERSION, toStateValue(checkpoint));
        return serializer.write(envelope);
    }

    /**
     * Decodes a version-1 workflow checkpoint.
     *
     * @param utf8Json encoded checkpoint envelope
     * @return immutable checkpoint
     */
    public WorkflowCheckpoint decode(byte[] utf8Json) {
        StateEnvelope envelope =
                serializer.read(Objects.requireNonNull(utf8Json, "utf8Json"), DocumentKind.WORKFLOW_CHECKPOINT);
        if (envelope.payloadVersion() != PAYLOAD_VERSION) {
            throw new SerializationException(
                    SerializationError.UNSUPPORTED_PAYLOAD_VERSION,
                    "Unsupported workflow checkpoint payload version " + envelope.payloadVersion() + ".");
        }
        return fromStateValue(envelope.payload());
    }

    /**
     * Converts a checkpoint to its deterministic JSON-shaped payload.
     *
     * @param checkpoint checkpoint
     * @return payload value
     */
    public StateValue.ObjectValue toStateValue(WorkflowCheckpoint checkpoint) {
        LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
        fields.put("workflowId", StateValue.string(checkpoint.workflowId()));
        fields.put("checkpointId", StateValue.string(checkpoint.checkpointId()));
        fields.put("revision", StateValue.integer(checkpoint.revision()));
        fields.put(
                "previousCheckpointId",
                checkpoint.previousCheckpointId() == null
                        ? StateValue.nullValue()
                        : StateValue.string(checkpoint.previousCheckpointId()));
        fields.put("status", StateValue.string(checkpoint.status().value()));
        fields.put(
                "bufferedInputs",
                StateValue.array(checkpoint.bufferedInputs().stream()
                        .map(WorkflowCheckpointCodec::bufferedInputValue)
                        .toList()));
        fields.put(
                "pendingExecutors",
                StateValue.array(checkpoint.pendingExecutors().stream()
                        .map(nodeId -> StateValue.string(nodeId.value()))
                        .toList()));
        LinkedHashMap<String, StateValue> fanInNextEpochs = new LinkedHashMap<>();
        checkpoint
                .fanInNextEpochs()
                .forEach((targetId, nextEpoch) -> fanInNextEpochs.put(targetId.value(), StateValue.integer(nextEpoch)));
        fields.put("fanInNextEpochs", StateValue.object(fanInNextEpochs));
        if (checkpoint.isRuntimeCheckpoint()) {
            fields.put("workflowSchemaVersion", StateValue.integer(checkpoint.workflowSchemaVersion()));
            fields.put("graphFingerprint", StateValue.string(checkpoint.graphFingerprint()));
            fields.put("runId", StateValue.string(checkpoint.runId()));
            fields.put("superstep", StateValue.integer(checkpoint.superstep()));
            LinkedHashMap<String, StateValue> state = new LinkedHashMap<>();
            checkpoint.state().values().forEach((key, encoded) -> state.put(key, encoded.toStateValue()));
            fields.put("state", StateValue.object(state));
        }
        return StateValue.object(fields);
    }

    /**
     * Converts a version-1 payload to an immutable checkpoint.
     *
     * @param payload checkpoint payload
     * @return checkpoint
     */
    public WorkflowCheckpoint fromStateValue(StateValue payload) {
        if (!(payload instanceof StateValue.ObjectValue object)) {
            throw malformed("Workflow checkpoint payload must be an object.");
        }
        String workflowId = string(object, "workflowId");
        String checkpointId = string(object, "checkpointId");
        long revision = nonNegativeLong(object, "revision");
        if (revision <= 0) {
            throw malformed("Workflow checkpoint revision must be positive.");
        }
        String previousCheckpointId = nullableString(object, "previousCheckpointId");
        WorkflowCheckpointStatus status = WorkflowCheckpointStatus.fromValue(string(object, "status"));
        List<NodeId> pendingExecutors = stringArray(object, "pendingExecutors").stream()
                .map(NodeId::new)
                .toList();
        List<BufferedInput> bufferedInputs = bufferedInputs(object);
        Map<NodeId, Long> fanInNextEpochs = fanInNextEpochs(object);

        boolean runtime = object.values().containsKey("workflowSchemaVersion")
                || object.values().containsKey("graphFingerprint")
                || object.values().containsKey("runId")
                || object.values().containsKey("superstep")
                || object.values().containsKey("state");
        if (!runtime) {
            return WorkflowCheckpoint.portableV1(
                    workflowId,
                    checkpointId,
                    revision,
                    previousCheckpointId,
                    status,
                    pendingExecutors,
                    bufferedInputs,
                    fanInNextEpochs);
        }
        int workflowSchemaVersion = positiveInt(object, "workflowSchemaVersion");
        String graphFingerprint = string(object, "graphFingerprint");
        String runId = string(object, "runId");
        int superstep = nonNegativeInt(object, "superstep");
        WorkflowState state = decodeState(object.require("state"));
        return new WorkflowCheckpoint(
                workflowId,
                checkpointId,
                revision,
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

    private static StateValue.ObjectValue bufferedInputValue(BufferedInput input) {
        return StateValue.object(Map.of(
                "sourceId",
                StateValue.string(input.sourceId()),
                "targetId",
                StateValue.string(input.targetId().value()),
                "value",
                input.value()));
    }

    private static List<BufferedInput> bufferedInputs(StateValue.ObjectValue object) {
        StateValue value = object.require("bufferedInputs");
        if (!(value instanceof StateValue.ArrayValue array)) {
            throw malformed("bufferedInputs must be an array.");
        }
        ArrayList<BufferedInput> result = new ArrayList<>();
        for (StateValue item : array.values()) {
            if (!(item instanceof StateValue.ObjectValue buffered)) {
                throw malformed("Each buffered input must be an object.");
            }
            result.add(new BufferedInput(
                    new NodeId(string(buffered, "targetId")), string(buffered, "sourceId"), buffered.require("value")));
        }
        return List.copyOf(result);
    }

    private static WorkflowState decodeState(StateValue value) {
        if (!(value instanceof StateValue.ObjectValue object)) {
            throw malformed("Workflow checkpoint state must be an object.");
        }
        LinkedHashMap<String, EncodedState> state = new LinkedHashMap<>();
        object.values().forEach((key, encoded) -> state.put(key, EncodedState.fromStateValue(encoded)));
        return state.isEmpty() ? WorkflowState.empty() : new WorkflowState(state);
    }

    private static Map<NodeId, Long> fanInNextEpochs(StateValue.ObjectValue checkpoint) {
        StateValue value = checkpoint.values().get("fanInNextEpochs");
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof StateValue.ObjectValue object)) {
            throw malformed("fanInNextEpochs must be an object.");
        }
        LinkedHashMap<NodeId, Long> result = new LinkedHashMap<>();
        object.values()
                .forEach((targetId, nextEpoch) ->
                        result.put(new NodeId(targetId), nonNegativeLong(nextEpoch, "fanInNextEpochs." + targetId)));
        return Map.copyOf(result);
    }

    private static List<String> stringArray(StateValue.ObjectValue object, String name) {
        StateValue value = object.require(name);
        if (!(value instanceof StateValue.ArrayValue array)) {
            throw malformed("Checkpoint field '" + name + "' must be an array.");
        }
        return array.values().stream()
                .map(item -> {
                    if (item instanceof StateValue.StringValue string) {
                        return string.value();
                    }
                    throw malformed("Checkpoint field '" + name + "' must contain strings.");
                })
                .toList();
    }

    private static String string(StateValue.ObjectValue object, String name) {
        StateValue value = object.require(name);
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw malformed("Checkpoint field '" + name + "' must be a string.");
    }

    private static String nullableString(StateValue.ObjectValue object, String name) {
        StateValue value = object.require(name);
        if (value instanceof StateValue.NullValue) {
            return null;
        }
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw malformed("Checkpoint field '" + name + "' must be null or a string.");
    }

    private static int positiveInt(StateValue.ObjectValue object, String name) {
        int value = nonNegativeInt(object, name);
        if (value <= 0) {
            throw malformed("Checkpoint field '" + name + "' must be positive.");
        }
        return value;
    }

    private static int nonNegativeInt(StateValue.ObjectValue object, String name) {
        long value = nonNegativeLong(object, name);
        if (value > Integer.MAX_VALUE) {
            throw malformed("Checkpoint field '" + name + "' is outside the integer range.");
        }
        return (int) value;
    }

    private static long nonNegativeLong(StateValue.ObjectValue object, String name) {
        return nonNegativeLong(object.require(name), name);
    }

    private static long nonNegativeLong(StateValue value, String name) {
        if (!(value instanceof StateValue.NumberValue number) || number.value().scale() > 0) {
            throw malformed("Checkpoint field '" + name + "' must be an integer.");
        }
        BigDecimal decimal = number.value();
        try {
            long result = decimal.longValueExact();
            if (result < 0) {
                throw malformed("Checkpoint field '" + name + "' must not be negative.");
            }
            return result;
        } catch (ArithmeticException exception) {
            throw malformed("Checkpoint field '" + name + "' is outside the long range.");
        }
    }

    private static SerializationException malformed(String message) {
        return new SerializationException(SerializationError.MALFORMED_DOCUMENT, message);
    }
}
