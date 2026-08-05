// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents immutable JSON-shaped state without exposing a JSON library type.
 */
public sealed interface StateValue
        permits StateValue.ArrayValue,
                StateValue.BooleanValue,
                StateValue.NullValue,
                StateValue.NumberValue,
                StateValue.ObjectValue,
                StateValue.StringValue {
    /**
     * Creates an object value.
     *
     * @param values object members
     * @return immutable object value
     */
    static ObjectValue object(Map<String, StateValue> values) {
        return new ObjectValue(values);
    }

    /**
     * Creates an array value.
     *
     * @param values ordered values
     * @return immutable array value
     */
    static ArrayValue array(List<? extends StateValue> values) {
        return new ArrayValue(List.copyOf(values));
    }

    /**
     * Creates a string value.
     *
     * @param value string value
     * @return string state value
     */
    static StringValue string(String value) {
        return new StringValue(value);
    }

    /**
     * Creates an exact decimal number value.
     *
     * @param value number value
     * @return number state value
     */
    static NumberValue number(BigDecimal value) {
        return new NumberValue(value);
    }

    /**
     * Creates an arbitrary-precision integer value.
     *
     * @param value integer value
     * @return number state value
     */
    static NumberValue integer(BigInteger value) {
        return new NumberValue(new BigDecimal(Objects.requireNonNull(value, "value")));
    }

    /**
     * Creates an integer value.
     *
     * @param value integer value
     * @return number state value
     */
    static NumberValue integer(long value) {
        return new NumberValue(BigDecimal.valueOf(value));
    }

    /**
     * Creates a Boolean value.
     *
     * @param value Boolean value
     * @return Boolean state value
     */
    static BooleanValue bool(boolean value) {
        return new BooleanValue(value);
    }

    /**
     * Returns the singleton JSON null value.
     *
     * @return null state value
     */
    static NullValue nullValue() {
        return NullValue.INSTANCE;
    }

    /**
     * Represents an immutable JSON object.
     *
     * @param values object members
     */
    record ObjectValue(Map<String, StateValue> values) implements StateValue {
        /** Creates an immutable object value. */
        public ObjectValue {
            Objects.requireNonNull(values, "values");
            LinkedHashMap<String, StateValue> copy = new LinkedHashMap<>();
            values.forEach((key, value) -> copy.put(
                    CoreValidation.requireNonBlank(key, "object member name"),
                    Objects.requireNonNull(value, "object member value")));
            values = Collections.unmodifiableMap(copy);
        }

        /**
         * Returns a required member.
         *
         * @param name member name
         * @return member value
         * @throws SerializationException when the member is absent
         */
        public StateValue require(String name) {
            StateValue value = values.get(name);
            if (value == null) {
                throw new SerializationException(
                        SerializationError.MALFORMED_DOCUMENT, "Required state member '" + name + "' is absent.");
            }
            return value;
        }
    }

    /**
     * Represents an immutable JSON array.
     *
     * @param values ordered values
     */
    record ArrayValue(List<StateValue> values) implements StateValue {
        /** Creates an immutable array value. */
        public ArrayValue {
            values = CoreValidation.copyList(values, "values");
        }
    }

    /**
     * Represents a JSON string.
     *
     * @param value string value
     */
    record StringValue(String value) implements StateValue {
        /** Creates a non-null string value. */
        public StringValue {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * Represents a finite JSON number without precision loss.
     *
     * <p>Decimal scale is retained so usage folding can distinguish integer tokens from decimal
     * provider values.
     *
     * @param value exact decimal value
     */
    record NumberValue(BigDecimal value) implements StateValue {
        /** Creates a finite exact number value. */
        public NumberValue {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * Represents a JSON Boolean.
     *
     * @param value Boolean value
     */
    record BooleanValue(boolean value) implements StateValue {}

    /** Represents the JSON {@code null} value. */
    enum NullValue implements StateValue {
        /** Singleton JSON null value. */
        INSTANCE
    }
}
