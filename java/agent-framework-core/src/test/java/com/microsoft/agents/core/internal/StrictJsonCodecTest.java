// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.SerializationError;
import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.StateValue;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StrictJsonCodecTest {
    @Test
    void codec_shouldRoundTripFrameworkValuesWithoutPolymorphicBinding() {
        StrictJsonCodec codec = new StrictJsonCodec(1024, 1024, 8, 128, 32, 16);
        StateValue value = StateValue.object(Map.of(
                "text", StateValue.string("hello"),
                "nested", StateValue.array(java.util.List.of(StateValue.integer(1)))));

        assertThat(codec.parse(codec.write(value))).isEqualTo(value);
    }

    @Test
    void codec_shouldRejectDuplicateKeysTrailingContentAndLimits() {
        StrictJsonCodec codec = new StrictJsonCodec(64, 64, 2, 8, 4, 2);

        assertFailure(codec, "{\"x\":1,\"x\":2}", SerializationError.DUPLICATE_KEY);
        assertFailure(codec, "{}{}", SerializationError.TRAILING_CONTENT);
        assertFailure(codec, "{\"long\":\"123456789\"}", SerializationError.STRING_LENGTH);
        assertThatThrownBy(() -> codec.write(StateValue.string("x".repeat(100))))
                .isInstanceOf(SerializationException.class)
                .extracting(exception -> ((SerializationException) exception).error())
                .isEqualTo(SerializationError.STRING_LENGTH);
    }

    private static void assertFailure(StrictJsonCodec codec, String json, SerializationError error) {
        assertThatThrownBy(() -> codec.parse(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SerializationException.class)
                .extracting(exception -> ((SerializationException) exception).error())
                .isEqualTo(error);
    }
}
