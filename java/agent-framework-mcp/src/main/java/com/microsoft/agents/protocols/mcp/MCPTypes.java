// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class MCPTypes {
    private MCPTypes() {}

    static StateValue toState(Object value, MCPLimits limits) {
        return toState(value, new Budget(limits), 0);
    }

    static StateValue.ObjectValue toStateObject(Map<String, ?> value, MCPLimits limits) {
        StateValue converted = toState(value, limits);
        if (converted instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw new ValidationException("MCP value must be a JSON object.");
    }

    static Map<String, StateValue> toStateMap(Map<String, ?> value, MCPLimits limits) {
        return value == null ? Map.of() : toStateObject(value, limits).values();
    }

    static Object toJava(StateValue value, MCPLimits limits) {
        return toJava(value, new Budget(limits), 0);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> toJavaMap(StateValue.ObjectValue value, MCPLimits limits) {
        return (Map<String, Object>) toJava(value, limits);
    }

    static void validateState(StateValue value, MCPLimits limits) {
        toJava(value, limits);
    }

    private static StateValue toState(Object value, Budget budget, int depth) {
        budget.depth(depth);
        if (value == null) {
            budget.item();
            return StateValue.nullValue();
        }
        if (value instanceof StateValue stateValue) {
            toJava(stateValue, budget, depth);
            return stateValue;
        }
        if (value instanceof String string) {
            budget.text(string);
            return StateValue.string(string);
        }
        if (value instanceof Boolean bool) {
            budget.item();
            return StateValue.bool(bool);
        }
        if (value instanceof BigDecimal decimal) {
            budget.number(decimal);
            return StateValue.number(decimal);
        }
        if (value instanceof BigInteger integer) {
            budget.number(new BigDecimal(integer));
            return StateValue.integer(integer);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            budget.item();
            return StateValue.integer(((Number) value).longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw new ValidationException("MCP JSON number must be finite.");
            }
            BigDecimal decimal = BigDecimal.valueOf(number);
            budget.number(decimal);
            return StateValue.number(decimal);
        }
        if (value instanceof Number number) {
            BigDecimal decimal;
            try {
                decimal = new BigDecimal(number.toString());
            } catch (NumberFormatException exception) {
                throw new ValidationException("MCP JSON number is invalid.", exception);
            }
            budget.number(decimal);
            return StateValue.number(decimal);
        }
        if (value instanceof Map<?, ?> map) {
            budget.collectionSize(map.size());
            LinkedHashMap<String, StateValue> converted = new LinkedHashMap<>();
            map.forEach((key, child) -> {
                if (!(key instanceof String name) || name.isBlank()) {
                    throw new ValidationException("MCP JSON object keys must be non-blank strings.");
                }
                budget.text(name);
                converted.put(name, toState(child, budget, depth + 1));
            });
            return StateValue.object(converted);
        }
        if (value instanceof Iterable<?> iterable) {
            ArrayList<StateValue> converted = new ArrayList<>();
            for (Object child : iterable) {
                budget.collectionSize(converted.size() + 1);
                converted.add(toState(child, budget, depth + 1));
            }
            return StateValue.array(converted);
        }
        if (value.getClass().isArray() && value instanceof Object[] values) {
            return toState(java.util.Arrays.asList(values), budget, depth);
        }
        throw new ValidationException("MCP JSON value has unsupported runtime type '"
                + value.getClass().getName() + "'.");
    }

    private static Object toJava(StateValue value, Budget budget, int depth) {
        Objects.requireNonNull(value, "value");
        budget.depth(depth);
        return switch (value) {
            case StateValue.NullValue nullValue -> {
                Objects.requireNonNull(nullValue, "nullValue");
                budget.item();
                yield null;
            }
            case StateValue.StringValue string -> {
                budget.text(string.value());
                yield string.value();
            }
            case StateValue.BooleanValue bool -> {
                budget.item();
                yield bool.value();
            }
            case StateValue.NumberValue number -> {
                budget.number(number.value());
                yield number.value();
            }
            case StateValue.ArrayValue array -> {
                budget.collectionSize(array.values().size());
                ArrayList<Object> converted = new ArrayList<>(array.values().size());
                for (StateValue child : array.values()) {
                    converted.add(toJava(child, budget, depth + 1));
                }
                yield Collections.unmodifiableList(converted);
            }
            case StateValue.ObjectValue object -> {
                budget.collectionSize(object.values().size());
                LinkedHashMap<String, Object> converted = new LinkedHashMap<>();
                object.values().forEach((name, child) -> {
                    budget.text(name);
                    converted.put(name, toJava(child, budget, depth + 1));
                });
                yield Collections.unmodifiableMap(converted);
            }
        };
    }

    private static final class Budget {
        private final MCPLimits limits;

        private int items;

        private long bytes;

        private Budget(MCPLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
        }

        private void depth(int depth) {
            if (depth > limits.maxNestingDepth()) {
                throw new ValidationException(
                        "MCP JSON exceeds maximum nesting depth " + limits.maxNestingDepth() + ".");
            }
        }

        private void item() {
            items++;
            if (items > limits.maxCollectionItems()) {
                throw new ValidationException(
                        "MCP value exceeds aggregate item count " + limits.maxCollectionItems() + ".");
            }
        }

        private void collectionSize(int count) {
            if (count > limits.maxCollectionItems()) {
                throw new ValidationException(
                        "MCP collection exceeds maximum item count " + limits.maxCollectionItems() + ".");
            }
        }

        private void text(String text) {
            Objects.requireNonNull(text, "text");
            item();
            bytes += text.getBytes(StandardCharsets.UTF_8).length;
            checkBytes();
        }

        private void number(BigDecimal number) {
            text(number.toPlainString());
        }

        private void checkBytes() {
            if (bytes > limits.maxPayloadBytes()) {
                throw new ValidationException(
                        "MCP value exceeds maximum payload size " + limits.maxPayloadBytes() + " bytes.");
            }
        }
    }
}
