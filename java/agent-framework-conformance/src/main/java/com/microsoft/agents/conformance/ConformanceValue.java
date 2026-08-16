// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents immutable, language-neutral JSON data without exposing a JSON library type.
 */
public sealed interface ConformanceValue
        permits ConformanceValue.ArrayValue,
                ConformanceValue.BooleanValue,
                ConformanceValue.NullValue,
                ConformanceValue.NumberValue,
                ConformanceValue.ObjectValue,
                ConformanceValue.StringValue {

    /**
     * Represents an immutable JSON object.
     *
     * @param values ordered object members
     */
    record ObjectValue(Map<String, ConformanceValue> values) implements ConformanceValue {
        /** Creates an immutable object value. */
        public ObjectValue {
            Objects.requireNonNull(values, "values");
            LinkedHashMap<String, ConformanceValue> copy = new LinkedHashMap<>();
            values.forEach((key, value) -> copy.put(
                    Objects.requireNonNull(key, "object member name"),
                    Objects.requireNonNull(value, "object member value")));
            values = Collections.unmodifiableMap(copy);
        }

        /**
         * Returns a required member.
         *
         * @param name member name
         * @return member value
         * @throws ConformanceValidationException when the member is absent
         */
        public ConformanceValue require(String name) {
            ConformanceValue value = values.get(name);
            if (value == null) {
                throw new ConformanceValidationException("Required conformance value '" + name + "' is absent.");
            }
            return value;
        }
    }

    /**
     * Represents an immutable JSON array.
     *
     * @param values ordered array values
     */
    record ArrayValue(List<ConformanceValue> values) implements ConformanceValue {
        /** Creates an immutable array value. */
        public ArrayValue {
            Objects.requireNonNull(values, "values");
            ArrayList<ConformanceValue> copy = new ArrayList<>(values.size());
            values.forEach(value -> copy.add(Objects.requireNonNull(value, "array value")));
            values = List.copyOf(copy);
        }
    }

    /**
     * Represents a JSON string.
     *
     * @param value string value
     */
    record StringValue(String value) implements ConformanceValue {
        /** Creates a string value. */
        public StringValue {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * Represents a JSON number without precision loss.
     *
     * @param value decimal value
     */
    record NumberValue(BigDecimal value) implements ConformanceValue {
        /** Creates a number value. */
        public NumberValue {
            Objects.requireNonNull(value, "value");
            value = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        }
    }

    /**
     * Represents a JSON Boolean.
     *
     * @param value Boolean value
     */
    record BooleanValue(boolean value) implements ConformanceValue {}

    /**
     * Represents the JSON {@code null} value.
     */
    enum NullValue implements ConformanceValue {
        /** Singleton JSON null value. */
        INSTANCE
    }
}
