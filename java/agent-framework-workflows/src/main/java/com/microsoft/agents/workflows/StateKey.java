// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.EncodedState;
import com.microsoft.agents.core.SerializationError;
import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.StateCodec;
import com.microsoft.agents.core.StateValue;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Defines one typed, codec-backed workflow state entry.
 *
 * @param <T> decoded state value type
 */
public final class StateKey<T> {
    private final String name;

    private final Class<T> valueType;

    private final StateCodec<T> codec;

    private final StateReducer<T> reducer;

    private StateKey(String name, Class<T> valueType, StateCodec<T> codec, StateReducer<T> reducer) {
        this.name = WorkflowValidation.requireNonBlank(name, "state key name");
        this.valueType = Objects.requireNonNull(valueType, "valueType");
        this.codec = Objects.requireNonNull(codec, "codec");
        WorkflowValidation.requireCodec(codec);
        this.reducer = reducer;
    }

    /**
     * Creates a state key that rejects conflicting concurrent writes.
     *
     * @param name stable state name
     * @param valueType decoded value type
     * @param codec explicit state codec
     * @param <T> value type
     * @return state key
     */
    public static <T> StateKey<T> of(String name, Class<T> valueType, StateCodec<T> codec) {
        return new StateKey<>(name, valueType, codec, null);
    }

    /**
     * Creates a state key with a deterministic concurrent-write reducer.
     *
     * @param name stable state name
     * @param valueType decoded value type
     * @param codec explicit state codec
     * @param reducer reducer applied in stable node order
     * @param <T> value type
     * @return reducing state key
     */
    public static <T> StateKey<T> reducing(
            String name, Class<T> valueType, StateCodec<T> codec, StateReducer<T> reducer) {
        return new StateKey<>(name, valueType, codec, Objects.requireNonNull(reducer, "reducer"));
    }

    /**
     * Returns the stable state name.
     *
     * @return state name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the decoded value type.
     *
     * @return decoded value type
     */
    public Class<T> valueType() {
        return valueType;
    }

    /**
     * Returns the explicit value codec.
     *
     * @return value codec
     */
    public StateCodec<T> codec() {
        return codec;
    }

    /**
     * Reports whether this key has a concurrent-write reducer.
     *
     * @return {@code true} when concurrent writes can be merged
     */
    public boolean hasReducer() {
        return reducer != null;
    }

    EncodedState encode(T value) {
        T checked = valueType.cast(Objects.requireNonNull(value, "value"));
        StateValue encoded = Objects.requireNonNull(codec.encode(checked), "codec output");
        return new EncodedState(codec.typeId(), codec.currentVersion(), encoded);
    }

    T decode(EncodedState encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (!codec.typeId().equals(encoded.typeId())) {
            throw new SerializationException(
                    SerializationError.UNKNOWN_TYPE_ID,
                    "State key '" + name + "' expects typeId '" + codec.typeId() + "' but found '" + encoded.typeId()
                            + "'.");
        }
        if (encoded.codecVersion() > codec.currentVersion()) {
            throw new SerializationException(
                    SerializationError.CODEC_MIGRATION,
                    "State key '" + name + "' has unsupported future codec version " + encoded.codecVersion() + ".");
        }
        StateValue value = encoded.value();
        for (int version = encoded.codecVersion(); version < codec.currentVersion(); version++) {
            value = Objects.requireNonNull(codec.migrate(value, version, version + 1), "codec migration output");
        }
        return valueType.cast(
                Objects.requireNonNull(codec.decode(value, codec.currentVersion()), "codec decoded value"));
    }

    EncodedState mergeEncoded(List<EncodedState> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty.");
        }
        EncodedState first = values.getFirst();
        if (values.stream().allMatch(first::equals)) {
            return first;
        }
        if (reducer == null) {
            throw new StateConflictException(name);
        }
        T merged = decode(first);
        for (int index = 1; index < values.size(); index++) {
            merged = valueType.cast(
                    Objects.requireNonNull(reducer.reduce(merged, decode(values.get(index))), "reducer"));
        }
        return encode(merged);
    }

    Optional<StateReducer<T>> reducer() {
        return Optional.ofNullable(reducer);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StateKey<?> key
                && name.equals(key.name)
                && valueType.equals(key.valueType)
                && codec.typeId().equals(key.codec.typeId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, valueType, codec.typeId());
    }

    @Override
    public String toString() {
        return name;
    }
}
