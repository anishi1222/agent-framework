// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.StateValue;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/** Represents textual or binary skill resource content. */
public sealed interface SkillResourceContent permits SkillResourceContent.Text, SkillResourceContent.Binary {
    /**
     * Converts this resource to a model-safe JSON-shaped result.
     *
     * @return resource result value
     */
    StateValue toStateValue();

    /**
     * Represents textual skill content.
     *
     * @param text content text
     */
    record Text(String text) implements SkillResourceContent {
        /** Creates non-null text content. */
        public Text {
            Objects.requireNonNull(text, "text");
        }

        @Override
        public StateValue toStateValue() {
            return StateValue.string(text);
        }
    }

    /** Represents immutable binary skill content. */
    final class Binary implements SkillResourceContent {
        private final byte[] data;

        /**
         * Creates binary content with defensive copying.
         *
         * @param data resource bytes
         */
        public Binary(byte[] data) {
            this.data = Objects.requireNonNull(data, "data").clone();
        }

        /**
         * Returns a defensive byte copy.
         *
         * @return resource bytes
         */
        public byte[] data() {
            return data.clone();
        }

        @Override
        public StateValue toStateValue() {
            return StateValue.string(Base64.getEncoder().encodeToString(data));
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof Binary binary && Arrays.equals(data, binary.data);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(data);
        }
    }
}
