// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import com.microsoft.agents.core.StateValue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class HarnessToolSupport {
    static final StateValue.ObjectValue OPEN_OUTPUT = StateValue.object(Map.of());

    static final StateValue.ObjectValue STRING_OUTPUT = StateValue.object(Map.of("type", StateValue.string("string")));

    static final StateValue.ObjectValue INTEGER_OUTPUT =
            StateValue.object(Map.of("type", StateValue.string("integer")));

    private HarnessToolSupport() {}

    static StateValue.ObjectValue objectSchema(Map<String, StateValue> properties, List<String> required) {
        LinkedHashMap<String, StateValue> schema = new LinkedHashMap<>();
        schema.put("type", StateValue.string("object"));
        schema.put("properties", StateValue.object(properties));
        schema.put(
                "required",
                StateValue.array(required.stream().map(StateValue::string).toList()));
        schema.put("additionalProperties", StateValue.bool(false));
        return StateValue.object(schema);
    }

    static StateValue.ObjectValue stringProperty(String description) {
        return StateValue.object(
                Map.of("type", StateValue.string("string"), "description", StateValue.string(description)));
    }

    static StateValue.ObjectValue integerProperty(String description) {
        return StateValue.object(
                Map.of("type", StateValue.string("integer"), "description", StateValue.string(description)));
    }

    static StateValue.ObjectValue booleanProperty(String description) {
        return StateValue.object(
                Map.of("type", StateValue.string("boolean"), "description", StateValue.string(description)));
    }

    static StateValue.ObjectValue arrayProperty(StateValue.ObjectValue itemSchema, String description) {
        return StateValue.object(Map.of(
                "type",
                StateValue.string("array"),
                "items",
                itemSchema,
                "description",
                StateValue.string(description)));
    }

    static String string(StateValue.ObjectValue arguments, String name) {
        StateValue value = arguments.values().get(name);
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw new IllegalArgumentException(name + " must be a string.");
    }

    static String optionalString(StateValue.ObjectValue arguments, String name) {
        StateValue value = arguments.values().get(name);
        if (value == null || value instanceof StateValue.NullValue) {
            return null;
        }
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw new IllegalArgumentException(name + " must be a string or null.");
    }

    static boolean optionalBoolean(StateValue.ObjectValue arguments, String name, boolean defaultValue) {
        StateValue value = arguments.values().get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof StateValue.BooleanValue bool) {
            return bool.value();
        }
        throw new IllegalArgumentException(name + " must be a boolean.");
    }

    static int integer(StateValue value, String name) {
        if (!(value instanceof StateValue.NumberValue number)) {
            throw new IllegalArgumentException(name + " must be an integer.");
        }
        BigDecimal decimal = number.value();
        try {
            return decimal.intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " must be an integer.", exception);
        }
    }

    static List<StateValue> array(StateValue.ObjectValue arguments, String name) {
        StateValue value = arguments.values().get(name);
        if (value instanceof StateValue.ArrayValue array) {
            return array.values();
        }
        throw new IllegalArgumentException(name + " must be an array.");
    }

    static StateValue.ObjectValue object(StateValue value, String name) {
        if (value instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw new IllegalArgumentException(name + " must be an object.");
    }

    static StateValue nullable(String value) {
        return value == null ? StateValue.nullValue() : StateValue.string(value);
    }

    static List<StateValue> stateValues(List<? extends StateValue> values) {
        return new ArrayList<>(values);
    }
}
