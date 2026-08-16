// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StateCodecRegistryTest {
    @Test
    void registry_shouldEncodeAndApplyMigrationsOneVersionAtATime() {
        // Arrange
        StateCodecRegistry registry = new StateCodecRegistry();
        MigratingCodec codec = new MigratingCodec();
        registry.register(codec);

        // Act
        String decoded = registry.decode(
                new EncodedState(codec.typeId(), 1, StateValue.object(Map.of("value", StateValue.string("initial")))));

        // Assert
        assertThat(decoded).isEqualTo("initial-v2-v3");
        assertThat(codec.migrations).isEqualTo(2);
        assertThat(registry.encode(codec, "current").codecVersion()).isEqualTo(3);
    }

    @Test
    void registry_shouldRejectDuplicateAndUnknownTypeIds() {
        // Arrange
        StateCodecRegistry registry = new StateCodecRegistry();
        MigratingCodec codec = new MigratingCodec();
        registry.register(codec);

        // Act / Assert
        assertThatThrownBy(() -> registry.register(new MigratingCodec()))
                .isInstanceOf(SerializationException.class)
                .extracting(exception -> ((SerializationException) exception).error())
                .isEqualTo(SerializationError.DUPLICATE_CODEC);
        assertThatThrownBy(() -> registry.decode(new EncodedState("com.example.unknown", 1, StateValue.nullValue())))
                .isInstanceOf(SerializationException.class)
                .extracting(exception -> ((SerializationException) exception).error())
                .isEqualTo(SerializationError.UNKNOWN_TYPE_ID);
    }

    @Test
    void registry_shouldRejectFutureVersionsAndInvalidTypeIds() {
        StateCodecRegistry registry = new StateCodecRegistry();
        MigratingCodec codec = new MigratingCodec();
        registry.register(codec);

        assertThatThrownBy(() -> registry.decode(new EncodedState(codec.typeId(), 4, StateValue.object(Map.of()))))
                .isInstanceOf(SerializationException.class)
                .extracting(exception -> ((SerializationException) exception).error())
                .isEqualTo(SerializationError.CODEC_MIGRATION);
        assertThatThrownBy(() -> registry.register(new InvalidTypeIdCodec()))
                .isInstanceOf(SerializationException.class);
    }

    @Test
    void registry_shouldMapMissingMigrationToSerializationException() {
        StateCodecRegistry registry = new StateCodecRegistry();
        MissingMigrationCodec codec = new MissingMigrationCodec();
        registry.register(codec);

        assertThatThrownBy(() -> registry.decode(new EncodedState(codec.typeId(), 1, StateValue.string("old"))))
                .isInstanceOf(SerializationException.class)
                .extracting(exception -> ((SerializationException) exception).error())
                .isEqualTo(SerializationError.CODEC_MIGRATION);
    }

    private static final class MigratingCodec implements StateCodec<String> {
        private int migrations;

        @Override
        public String typeId() {
            return "com.example.migrating-value";
        }

        @Override
        public int currentVersion() {
            return 3;
        }

        @Override
        public StateValue encode(String value) {
            return StateValue.object(Map.of("value", StateValue.string(value)));
        }

        @Override
        public StateValue migrate(StateValue value, int fromVersion, int toVersion) {
            assertThat(toVersion).isEqualTo(fromVersion + 1);
            migrations++;
            StateValue.ObjectValue object = (StateValue.ObjectValue) value;
            String current = ((StateValue.StringValue) object.require("value")).value();
            return StateValue.object(Map.of("value", StateValue.string(current + "-v" + toVersion)));
        }

        @Override
        public String decode(StateValue value, int version) {
            assertThat(version).isEqualTo(currentVersion());
            StateValue.ObjectValue object = (StateValue.ObjectValue) value;
            return ((StateValue.StringValue) object.require("value")).value();
        }
    }

    private static final class InvalidTypeIdCodec implements StateCodec<String> {
        @Override
        public String typeId() {
            return "not-qualified";
        }

        @Override
        public int currentVersion() {
            return 1;
        }

        @Override
        public StateValue encode(String value) {
            return StateValue.string(value);
        }

        @Override
        public StateValue migrate(StateValue value, int fromVersion, int toVersion) {
            return value;
        }

        @Override
        public String decode(StateValue value, int version) {
            return ((StateValue.StringValue) value).value();
        }
    }

    private static final class MissingMigrationCodec implements StateCodec<String> {
        @Override
        public String typeId() {
            return "com.example.missing-migration";
        }

        @Override
        public int currentVersion() {
            return 2;
        }

        @Override
        public StateValue encode(String value) {
            return StateValue.string(value);
        }

        @Override
        public StateValue migrate(StateValue value, int fromVersion, int toVersion) {
            throw new UnsupportedOperationException("migration not defined");
        }

        @Override
        public String decode(StateValue value, int version) {
            return ((StateValue.StringValue) value).value();
        }
    }
}
