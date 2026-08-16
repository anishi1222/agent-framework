// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.StateValue;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generates deterministic provider-neutral JSON Schema for supported Java types.
 *
 * <p>The generator supports primitives and wrappers, {@link String}, enums, {@link BigInteger},
 * {@link BigDecimal}, parameterized {@link Optional}, {@link List}, string-keyed {@link Map}, and
 * public records composed from those types. Raw generics, wildcards, arrays, arbitrary classes, and
 * recursive type graphs are rejected.
 */
public final class ToolSchemaGenerator {
    private ToolSchemaGenerator() {}

    /**
     * Generates a JSON Schema object for one supported Java type.
     *
     * @param type Java reflection type
     * @return immutable JSON Schema object
     * @throws ToolBindingException when the type is raw, unsupported, inaccessible, or recursive
     */
    public static StateValue.ObjectValue generate(Type type) {
        return schema(type, new ArrayDeque<>());
    }

    static boolean isOptional(Type type) {
        return type instanceof ParameterizedType parameterized && parameterized.getRawType() == Optional.class;
    }

    private static StateValue.ObjectValue schema(Type type, ArrayDeque<Class<?>> records) {
        if (type instanceof Class<?> clazz) {
            return schemaClass(clazz, records);
        }
        if (type instanceof ParameterizedType parameterized) {
            return schemaParameterized(parameterized, records);
        }
        if (type instanceof TypeVariable<?> variable) {
            throw unsupported(type, "type variables are not supported: " + variable.getName());
        }
        if (type instanceof WildcardType) {
            throw unsupported(type, "wildcard types are not supported");
        }
        if (type instanceof GenericArrayType) {
            throw unsupported(type, "generic arrays are not supported");
        }
        throw unsupported(type, "unknown reflection type " + type.getClass().getName());
    }

    private static StateValue.ObjectValue schemaClass(Class<?> clazz, ArrayDeque<Class<?>> records) {
        if (clazz == void.class || clazz == Void.class) {
            return typed("null");
        }
        if (clazz == boolean.class || clazz == Boolean.class) {
            return typed("boolean");
        }
        if (clazz == byte.class
                || clazz == Byte.class
                || clazz == short.class
                || clazz == Short.class
                || clazz == int.class
                || clazz == Integer.class
                || clazz == long.class
                || clazz == Long.class
                || clazz == BigInteger.class) {
            return typed("integer");
        }
        if (clazz == float.class
                || clazz == Float.class
                || clazz == double.class
                || clazz == Double.class
                || clazz == BigDecimal.class) {
            return typed("number");
        }
        if (clazz == char.class || clazz == Character.class) {
            LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
            fields.put("type", StateValue.string("string"));
            fields.put("minLength", StateValue.integer(1));
            fields.put("maxLength", StateValue.integer(1));
            return StateValue.object(fields);
        }
        if (clazz == String.class) {
            return typed("string");
        }
        if (clazz.isEnum()) {
            LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
            fields.put("type", StateValue.string("string"));
            List<StateValue> values = new ArrayList<>();
            for (Object constant : clazz.getEnumConstants()) {
                values.add(StateValue.string(((Enum<?>) constant).name()));
            }
            fields.put("enum", StateValue.array(values));
            return StateValue.object(fields);
        }
        if (clazz.isArray()) {
            throw unsupported(clazz, "arrays are not supported; use List<T>");
        }
        if (clazz == Optional.class || clazz == List.class || clazz == Map.class) {
            throw unsupported(clazz, "raw generic types are not supported");
        }
        if (clazz.isRecord()) {
            return schemaRecord(clazz, records);
        }
        throw unsupported(clazz, "only public records may be bound as structured objects");
    }

    private static StateValue.ObjectValue schemaParameterized(
            ParameterizedType parameterized, ArrayDeque<Class<?>> records) {
        Type rawType = parameterized.getRawType();
        Type[] arguments = parameterized.getActualTypeArguments();
        if (rawType == Optional.class) {
            requireArity(parameterized, arguments, 1);
            LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
            fields.put("anyOf", StateValue.array(List.of(schema(arguments[0], records), typed("null"))));
            return StateValue.object(fields);
        }
        if (rawType == List.class) {
            requireArity(parameterized, arguments, 1);
            LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
            fields.put("type", StateValue.string("array"));
            fields.put("items", schema(arguments[0], records));
            return StateValue.object(fields);
        }
        if (rawType == Map.class) {
            requireArity(parameterized, arguments, 2);
            if (arguments[0] != String.class) {
                throw unsupported(parameterized, "map keys must be String");
            }
            LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
            fields.put("type", StateValue.string("object"));
            fields.put("additionalProperties", schema(arguments[1], records));
            return StateValue.object(fields);
        }
        throw unsupported(parameterized, "only Optional<T>, List<T>, and Map<String,T> are supported generics");
    }

    private static StateValue.ObjectValue schemaRecord(Class<?> recordType, ArrayDeque<Class<?>> records) {
        if (!java.lang.reflect.Modifier.isPublic(recordType.getModifiers())) {
            throw unsupported(recordType, "record type must be public");
        }
        if (records.contains(recordType)) {
            throw unsupported(recordType, "recursive record type graph is not supported");
        }
        records.addLast(recordType);
        try {
            LinkedHashMap<String, StateValue> properties = new LinkedHashMap<>();
            List<StateValue> required = new ArrayList<>();
            for (RecordComponent component : recordType.getRecordComponents()) {
                properties.put(component.getName(), schema(component.getGenericType(), records));
                if (!isOptional(component.getGenericType())) {
                    required.add(StateValue.string(component.getName()));
                }
            }
            LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
            fields.put("type", StateValue.string("object"));
            fields.put("properties", StateValue.object(properties));
            if (!required.isEmpty()) {
                fields.put("required", StateValue.array(required));
            }
            fields.put("additionalProperties", StateValue.bool(false));
            return StateValue.object(fields);
        } finally {
            records.removeLast();
        }
    }

    private static StateValue.ObjectValue typed(String type) {
        return StateValue.object(Map.of("type", StateValue.string(type)));
    }

    private static void requireArity(ParameterizedType type, Type[] arguments, int expected) {
        if (arguments.length != expected) {
            throw unsupported(type, "expected " + expected + " generic argument(s)");
        }
    }

    private static ToolBindingException unsupported(Type type, String reason) {
        return new ToolBindingException("Unsupported tool type '" + type.getTypeName() + "': " + reason + ".");
    }
}
