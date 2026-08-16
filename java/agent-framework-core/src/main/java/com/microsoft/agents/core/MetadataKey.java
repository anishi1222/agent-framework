// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Defines a typed key and JSON-safe codec for immutable framework metadata.
 *
 * @param <T> decoded value type
 */
public final class MetadataKey<T> {
    private final String name;

    private final Function<T, StateValue> encoder;

    private final Function<StateValue, T> decoder;

    private MetadataKey(String name, Function<T, StateValue> encoder, Function<StateValue, T> decoder) {
        this.name = CoreValidation.requireNonBlank(name, "name");
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /**
     * Creates a custom JSON-safe metadata key.
     *
     * @param name stable metadata name
     * @param encoder value encoder
     * @param decoder value decoder
     * @param <T> decoded value type
     * @return typed key
     */
    public static <T> MetadataKey<T> of(String name, Function<T, StateValue> encoder, Function<StateValue, T> decoder) {
        return new MetadataKey<>(name, encoder, decoder);
    }

    /**
     * Creates a custom key named after a Java type.
     *
     * @param type key-owning type
     * @param encoder value encoder
     * @param decoder value decoder
     * @param <T> decoded value type
     * @return typed key using {@link Class#getName()}
     */
    public static <T> MetadataKey<T> forType(
            Class<?> type, Function<T, StateValue> encoder, Function<StateValue, T> decoder) {
        return of(Objects.requireNonNull(type, "type").getName(), encoder, decoder);
    }

    /** Creates a string metadata key. */
    public static MetadataKey<String> string(String name) {
        return of(
                name,
                StateValue::string,
                value -> require(value, StateValue.StringValue.class).value());
    }

    /** Creates a Boolean metadata key. */
    public static MetadataKey<Boolean> bool(String name) {
        return of(
                name,
                StateValue::bool,
                value -> require(value, StateValue.BooleanValue.class).value());
    }

    /** Creates an arbitrary-precision integer metadata key. */
    public static MetadataKey<BigInteger> integer(String name) {
        return of(name, StateValue::integer, value -> {
            try {
                return require(value, StateValue.NumberValue.class).value().toBigIntegerExact();
            } catch (ArithmeticException failure) {
                throw new ValidationException("Metadata value for '" + name + "' is not an integer.");
            }
        });
    }

    /** Creates an exact decimal metadata key. */
    public static MetadataKey<BigDecimal> number(String name) {
        return of(
                name,
                StateValue::number,
                value -> require(value, StateValue.NumberValue.class).value());
    }

    /** Creates an object metadata key. */
    public static MetadataKey<Map<String, StateValue>> object(String name) {
        return of(
                name,
                StateValue::object,
                value -> require(value, StateValue.ObjectValue.class).values());
    }

    /** Creates an array metadata key. */
    public static MetadataKey<List<StateValue>> array(String name) {
        return of(
                name,
                StateValue::array,
                value -> require(value, StateValue.ArrayValue.class).values());
    }

    /**
     * Returns the stable metadata name.
     *
     * @return metadata name
     */
    public String name() {
        return name;
    }

    StateValue encode(T value) {
        return Objects.requireNonNull(encoder.apply(Objects.requireNonNull(value, "value")), "encoded value");
    }

    T decode(StateValue value) {
        return Objects.requireNonNull(decoder.apply(Objects.requireNonNull(value, "value")), "decoded value");
    }

    private static <T extends StateValue> T require(StateValue value, Class<T> type) {
        if (!type.isInstance(value)) {
            throw new ValidationException("Metadata value has type "
                    + value.getClass().getSimpleName() + ", expected " + type.getSimpleName() + ".");
        }
        return type.cast(value);
    }
}
