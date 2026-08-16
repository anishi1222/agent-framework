// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.EncodedState;
import com.microsoft.agents.core.SerializationError;
import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.StateCodec;
import com.microsoft.agents.core.StateValue;
import java.util.Objects;

final class FunctionalStateCodecSupport {
    private FunctionalStateCodecSupport() {}

    static <T> EncodedState encode(StateCodec<T> codec, T value) {
        Objects.requireNonNull(codec, "codec");
        WorkflowValidation.requireCodec(codec);
        StateValue encoded = Objects.requireNonNull(codec.encode(value), "codec output");
        return new EncodedState(codec.typeId(), codec.currentVersion(), encoded);
    }

    static <T> T decode(String subject, Class<T> valueType, StateCodec<T> codec, EncodedState encoded) {
        Objects.requireNonNull(valueType, "valueType");
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(encoded, "encoded");
        WorkflowValidation.requireCodec(codec);
        if (!codec.typeId().equals(encoded.typeId())) {
            throw new SerializationException(
                    SerializationError.UNKNOWN_TYPE_ID,
                    subject + " expects typeId '" + codec.typeId() + "' but found '" + encoded.typeId() + "'.");
        }
        if (encoded.codecVersion() > codec.currentVersion()) {
            throw new SerializationException(
                    SerializationError.CODEC_MIGRATION,
                    subject + " has unsupported future codec version " + encoded.codecVersion() + ".");
        }
        StateValue value = encoded.value();
        for (int version = encoded.codecVersion(); version < codec.currentVersion(); version++) {
            value = Objects.requireNonNull(codec.migrate(value, version, version + 1), "codec migration output");
        }
        return valueType.cast(Objects.requireNonNull(codec.decode(value, codec.currentVersion()), "codec output"));
    }
}
