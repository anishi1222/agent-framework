// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.EncodedState;
import com.microsoft.agents.core.SerializationError;
import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.StateCodec;
import com.microsoft.agents.core.StateValue;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Describes one typed executor in an immutable workflow graph.
 *
 * @param <I> input payload type
 * @param <O> output payload type
 */
public final class WorkflowNode<I, O> {
    private final NodeId id;

    private final Executor<I, O> executor;

    WorkflowNode(NodeId id, Executor<I, O> executor) {
        this.id = Objects.requireNonNull(id, "id");
        this.executor = Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(executor.inputType(), "executor.inputType()");
        Objects.requireNonNull(executor.outputType(), "executor.outputType()");
    }

    /**
     * Returns the node identifier.
     *
     * @return node identifier
     */
    public NodeId id() {
        return id;
    }

    /**
     * Returns the executor input type.
     *
     * @return input type
     */
    public Class<I> inputType() {
        return executor.inputType();
    }

    /**
     * Returns the executor output type.
     *
     * @return output type
     */
    public Class<O> outputType() {
        return executor.outputType();
    }

    /**
     * Returns the typed executor.
     *
     * @return node executor
     */
    public Executor<I, O> executor() {
        return executor;
    }

    CompletionStage<Object> execute(Object input, WorkflowContext context) {
        I checked = inputType().cast(Objects.requireNonNull(input, "input"));
        CompletionStage<O> stage =
                Objects.requireNonNull(executor.executeAsync(checked, context), "executor completion stage");
        return stage.thenApply(value -> outputType().cast(Objects.requireNonNull(value, "executor output")));
    }

    EncodedState encodeInput(Object input) {
        return encode(executor.inputCodec(), inputType().cast(input), "input");
    }

    EncodedState encodeOutput(Object output) {
        return encode(executor.outputCodec(), outputType().cast(output), "output");
    }

    Object decodeInput(EncodedState encoded) {
        StateCodec<I> codec = executor.inputCodec()
                .orElseThrow(() -> new WorkflowCheckpointException(
                        "Node '" + id + "' does not define an input codec required for resume."));
        return inputType().cast(decode(codec, encoded));
    }

    Object decodeOutput(EncodedState encoded) {
        StateCodec<O> codec = executor.outputCodec()
                .orElseThrow(() -> new WorkflowCheckpointException(
                        "Node '" + id + "' does not define an output codec required for resume."));
        return outputType().cast(decode(codec, encoded));
    }

    private <T> EncodedState encode(Optional<StateCodec<T>> codec, T value, String direction) {
        StateCodec<T> selected = codec.orElseThrow(() -> new WorkflowCheckpointException(
                "Node '" + id + "' does not define an " + direction + " codec required for checkpointing."));
        StateValue state = Objects.requireNonNull(selected.encode(value), "codec output");
        return new EncodedState(selected.typeId(), selected.currentVersion(), state);
    }

    private static <T> T decode(StateCodec<T> codec, EncodedState encoded) {
        if (!codec.typeId().equals(encoded.typeId())) {
            throw new SerializationException(
                    SerializationError.UNKNOWN_TYPE_ID,
                    "Checkpoint value expects codec '" + codec.typeId() + "' but found '" + encoded.typeId() + "'.");
        }
        if (encoded.codecVersion() > codec.currentVersion()) {
            throw new SerializationException(
                    SerializationError.CODEC_MIGRATION,
                    "Checkpoint value codec version is newer than the registered codec.");
        }
        StateValue value = encoded.value();
        for (int version = encoded.codecVersion(); version < codec.currentVersion(); version++) {
            value = Objects.requireNonNull(codec.migrate(value, version, version + 1), "codec migration output");
        }
        return Objects.requireNonNull(codec.decode(value, codec.currentVersion()), "codec decoded value");
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
