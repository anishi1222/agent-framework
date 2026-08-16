// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.StateValue;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ToolValueCodec {
    private ToolValueCodec() {}

    static Object bind(Type type, StateValue value, String path) {
        ToolSchemaGenerator.generate(type);
        return bindValue(type, value, path, new ArrayDeque<>());
    }

    static StateValue encode(Type type, Object value, String path) {
        ToolSchemaGenerator.generate(type);
        return encodeValue(type, value, path, new IdentityHashMap<>());
    }

    private static Object bindValue(Type type, StateValue value, String path, ArrayDeque<Class<?>> records) {
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            Type[] arguments = parameterized.getActualTypeArguments();
            if (raw == Optional.class) {
                if (value == StateValue.NullValue.INSTANCE) {
                    return Optional.empty();
                }
                return Optional.ofNullable(bindValue(arguments[0], value, path, records));
            }
            if (raw == List.class) {
                StateValue.ArrayValue array = requireArray(value, path);
                List<Object> result = new ArrayList<>(array.values().size());
                for (int index = 0; index < array.values().size(); index++) {
                    result.add(bindValue(arguments[0], array.values().get(index), path + "[" + index + "]", records));
                }
                return List.copyOf(result);
            }
            if (raw == Map.class) {
                StateValue.ObjectValue object = requireObject(value, path);
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                object.values()
                        .forEach((key, item) ->
                                result.put(key, bindValue(arguments[1], item, path + "." + key, records)));
                return java.util.Collections.unmodifiableMap(result);
            }
        }
        if (!(type instanceof Class<?> clazz)) {
            throw new ToolBindingException("Unsupported bound type at " + path + ": " + type.getTypeName() + ".");
        }
        if (value == StateValue.NullValue.INSTANCE) {
            throw new ToolBindingException("Required value at " + path + " must not be null; use Optional<T>.");
        }
        if (clazz == String.class) {
            return requireString(value, path);
        }
        if (clazz == char.class || clazz == Character.class) {
            String text = requireString(value, path);
            if (text.length() != 1) {
                throw new ToolBindingException("Value at " + path + " must contain exactly one character.");
            }
            return text.charAt(0);
        }
        if (clazz == boolean.class || clazz == Boolean.class) {
            if (value instanceof StateValue.BooleanValue bool) {
                return bool.value();
            }
            throw expected(path, "boolean", value);
        }
        if (clazz == BigDecimal.class) {
            return requireNumber(value, path);
        }
        if (clazz == BigInteger.class) {
            return requireInteger(value, path);
        }
        if (clazz == byte.class || clazz == Byte.class) {
            try {
                return exactInteger(value, path).byteValueExact();
            } catch (ArithmeticException exception) {
                throw integerRange(path, "byte", exception);
            }
        }
        if (clazz == short.class || clazz == Short.class) {
            try {
                return exactInteger(value, path).shortValueExact();
            } catch (ArithmeticException exception) {
                throw integerRange(path, "short", exception);
            }
        }
        if (clazz == int.class || clazz == Integer.class) {
            try {
                return exactInteger(value, path).intValueExact();
            } catch (ArithmeticException exception) {
                throw integerRange(path, "int", exception);
            }
        }
        if (clazz == long.class || clazz == Long.class) {
            try {
                return exactInteger(value, path).longValueExact();
            } catch (ArithmeticException exception) {
                throw integerRange(path, "long", exception);
            }
        }
        if (clazz == float.class || clazz == Float.class) {
            float result = requireNumber(value, path).floatValue();
            if (!Float.isFinite(result)) {
                throw new ToolBindingException("Number at " + path + " is outside the finite float range.");
            }
            return result;
        }
        if (clazz == double.class || clazz == Double.class) {
            double result = requireNumber(value, path).doubleValue();
            if (!Double.isFinite(result)) {
                throw new ToolBindingException("Number at " + path + " is outside the finite double range.");
            }
            return result;
        }
        if (clazz.isEnum()) {
            String name = requireString(value, path);
            try {
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object result = Enum.valueOf((Class<? extends Enum>) clazz, name);
                return result;
            } catch (IllegalArgumentException exception) {
                throw new ToolBindingException(
                        "Unsupported enum value '" + name + "' at " + path + " for " + clazz.getName() + ".");
            }
        }
        if (clazz.isRecord()) {
            return bindRecord(clazz, requireObject(value, path), path, records);
        }
        throw new ToolBindingException("Unsupported bound class at " + path + ": " + clazz.getName() + ".");
    }

    private static Object bindRecord(
            Class<?> recordType, StateValue.ObjectValue value, String path, ArrayDeque<Class<?>> records) {
        if (records.contains(recordType)) {
            throw new ToolBindingException("Recursive record value at " + path + " is not supported.");
        }
        records.addLast(recordType);
        try {
            RecordComponent[] components = recordType.getRecordComponents();
            java.util.Set<String> componentNames = java.util.Arrays.stream(components)
                    .map(RecordComponent::getName)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            List<String> extras = value.values().keySet().stream()
                    .filter(key -> !componentNames.contains(key))
                    .sorted()
                    .toList();
            if (!extras.isEmpty()) {
                throw new ToolBindingException("Unexpected record field(s) at " + path + ": " + extras + ".");
            }
            Object[] arguments = new Object[components.length];
            Class<?>[] parameterTypes = new Class<?>[components.length];
            for (int index = 0; index < components.length; index++) {
                RecordComponent component = components[index];
                parameterTypes[index] = component.getType();
                StateValue member = value.values().get(component.getName());
                if (member == null) {
                    if (ToolSchemaGenerator.isOptional(component.getGenericType())) {
                        arguments[index] = Optional.empty();
                        continue;
                    }
                    throw new ToolBindingException(
                            "Missing required record field at " + path + "." + component.getName() + ".");
                }
                arguments[index] =
                        bindValue(component.getGenericType(), member, path + "." + component.getName(), records);
            }
            MethodHandle constructor = MethodHandles.publicLookup()
                    .findConstructor(recordType, MethodType.methodType(void.class, parameterTypes));
            return constructor.invokeWithArguments(arguments);
        } catch (ToolBindingException exception) {
            throw exception;
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new ToolBindingException(
                    "Public canonical constructor is inaccessible for record " + recordType.getName() + ".", exception);
        } catch (Throwable failure) {
            throw new ToolBindingException("Unable to construct record " + recordType.getName() + ".", failure);
        } finally {
            records.removeLast();
        }
    }

    private static StateValue encodeValue(
            Type type, Object value, String path, IdentityHashMap<Object, Boolean> active) {
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            Type[] arguments = parameterized.getActualTypeArguments();
            if (raw == Optional.class) {
                if (!(value instanceof Optional<?> optional)) {
                    throw incompatible(path, type, value);
                }
                return optional.isPresent()
                        ? encodeValue(arguments[0], optional.orElseThrow(), path, active)
                        : StateValue.nullValue();
            }
            if (raw == List.class) {
                if (!(value instanceof List<?> list)) {
                    throw incompatible(path, type, value);
                }
                enter(value, path, active);
                try {
                    List<StateValue> encoded = new ArrayList<>(list.size());
                    for (int index = 0; index < list.size(); index++) {
                        encoded.add(encodeValue(arguments[0], list.get(index), path + "[" + index + "]", active));
                    }
                    return StateValue.array(encoded);
                } finally {
                    active.remove(value);
                }
            }
            if (raw == Map.class) {
                if (!(value instanceof Map<?, ?> map)) {
                    throw incompatible(path, type, value);
                }
                enter(value, path, active);
                try {
                    LinkedHashMap<String, StateValue> encoded = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (!(entry.getKey() instanceof String key)) {
                            throw new ToolBindingException("Map key at " + path + " must be String.");
                        }
                        encoded.put(key, encodeValue(arguments[1], entry.getValue(), path + "." + key, active));
                    }
                    return StateValue.object(encoded);
                } finally {
                    active.remove(value);
                }
            }
        }
        if (!(type instanceof Class<?> clazz)) {
            throw new ToolBindingException("Unsupported encoded type at " + path + ": " + type.getTypeName() + ".");
        }
        if (value == null) {
            if (clazz == void.class || clazz == Void.class) {
                return StateValue.nullValue();
            }
            throw new ToolBindingException("Required return value at " + path + " must not be null.");
        }
        if (clazz == void.class || clazz == Void.class) {
            return StateValue.nullValue();
        }
        if (clazz == String.class) {
            return StateValue.string((String) value);
        }
        if (clazz == char.class || clazz == Character.class) {
            return StateValue.string(String.valueOf((Character) value));
        }
        if (clazz == boolean.class || clazz == Boolean.class) {
            return StateValue.bool((Boolean) value);
        }
        if (clazz == BigInteger.class) {
            return StateValue.integer((BigInteger) value);
        }
        if (clazz == BigDecimal.class) {
            return StateValue.number((BigDecimal) value);
        }
        if (clazz == byte.class
                || clazz == Byte.class
                || clazz == short.class
                || clazz == Short.class
                || clazz == int.class
                || clazz == Integer.class
                || clazz == long.class
                || clazz == Long.class) {
            return StateValue.integer(((Number) value).longValue());
        }
        if (clazz == float.class || clazz == Float.class || clazz == double.class || clazz == Double.class) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw new ToolBindingException("Return number at " + path + " must be finite.");
            }
            return StateValue.number(BigDecimal.valueOf(number));
        }
        if (clazz.isEnum()) {
            return StateValue.string(((Enum<?>) value).name());
        }
        if (clazz.isRecord()) {
            return encodeRecord(clazz, value, path, active);
        }
        throw incompatible(path, type, value);
    }

    private static StateValue encodeRecord(
            Class<?> recordType, Object value, String path, IdentityHashMap<Object, Boolean> active) {
        if (!recordType.isInstance(value)) {
            throw incompatible(path, recordType, value);
        }
        enter(value, path, active);
        try {
            LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
            for (RecordComponent component : recordType.getRecordComponents()) {
                try {
                    MethodHandle accessor = MethodHandles.publicLookup().unreflect(component.getAccessor());
                    Object member = accessor.invoke(value);
                    fields.put(
                            component.getName(),
                            encodeValue(component.getGenericType(), member, path + "." + component.getName(), active));
                } catch (IllegalAccessException exception) {
                    throw new ToolBindingException(
                            "Public record accessor is inaccessible for " + recordType.getName() + ".", exception);
                } catch (ToolBindingException exception) {
                    throw exception;
                } catch (Throwable failure) {
                    throw new ToolBindingException(
                            "Unable to read record component " + recordType.getName() + "." + component.getName() + ".",
                            failure);
                }
            }
            return StateValue.object(fields);
        } finally {
            active.remove(value);
        }
    }

    private static void enter(Object value, String path, IdentityHashMap<Object, Boolean> active) {
        if (active.put(value, Boolean.TRUE) != null) {
            throw new ToolBindingException("Recursive value graph at " + path + " is not supported.");
        }
    }

    private static StateValue.ObjectValue requireObject(StateValue value, String path) {
        if (value instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw expected(path, "object", value);
    }

    private static StateValue.ArrayValue requireArray(StateValue value, String path) {
        if (value instanceof StateValue.ArrayValue array) {
            return array;
        }
        throw expected(path, "array", value);
    }

    private static String requireString(StateValue value, String path) {
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw expected(path, "string", value);
    }

    private static BigDecimal requireNumber(StateValue value, String path) {
        if (value instanceof StateValue.NumberValue number) {
            return number.value();
        }
        throw expected(path, "number", value);
    }

    private static BigInteger requireInteger(StateValue value, String path) {
        try {
            return requireNumber(value, path).toBigIntegerExact();
        } catch (ArithmeticException exception) {
            throw new ToolBindingException("Number at " + path + " must be an integer.", exception);
        }
    }

    private static BigInteger exactInteger(StateValue value, String path) {
        return requireInteger(value, path);
    }

    private static ToolBindingException expected(String path, String expected, StateValue actual) {
        return new ToolBindingException("Expected " + expected + " at " + path + " but received "
                + actual.getClass().getSimpleName() + ".");
    }

    private static ToolBindingException incompatible(String path, Type type, Object actual) {
        String actualName = actual == null ? "null" : actual.getClass().getName();
        return new ToolBindingException(
                "Value at " + path + " is not compatible with " + type.getTypeName() + ": " + actualName + ".");
    }

    private static ToolBindingException integerRange(String path, String targetType, ArithmeticException cause) {
        return new ToolBindingException("Integer at " + path + " is outside the " + targetType + " range.", cause);
    }
}
