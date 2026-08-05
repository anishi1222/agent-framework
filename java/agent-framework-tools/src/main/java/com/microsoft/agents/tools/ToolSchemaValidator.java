// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.StateValue;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ToolSchemaValidator {
    private static final Set<String> SUPPORTED_KEYWORDS = Set.of(
            "type",
            "properties",
            "required",
            "additionalProperties",
            "items",
            "anyOf",
            "enum",
            "minLength",
            "maxLength",
            "description",
            "title");

    private ToolSchemaValidator() {}

    static void validateSchema(StateValue.ObjectValue schema, String path) {
        List<String> unsupported = schema.values().keySet().stream()
                .filter(key -> !SUPPORTED_KEYWORDS.contains(key))
                .sorted()
                .toList();
        if (!unsupported.isEmpty()) {
            throw new ToolBindingException("Unsupported JSON Schema keyword(s) at " + path + ": " + unsupported + ".");
        }
        String type = schemaType(schema);
        if (type != null
                && !Set.of("null", "boolean", "integer", "number", "string", "array", "object")
                        .contains(type)) {
            throw new ToolBindingException("Unsupported JSON Schema type '" + type + "' at " + path + ".");
        }
        if (schema.values().get("properties") instanceof StateValue.ObjectValue properties) {
            properties.values().forEach((name, value) -> {
                if (!(value instanceof StateValue.ObjectValue child)) {
                    throw new ToolBindingException(
                            "JSON Schema property '" + name + "' at " + path + " must be an object.");
                }
                validateSchema(child, path + ".properties." + name);
            });
        }
        if (schema.values().get("required") instanceof StateValue.ArrayValue required) {
            Set<String> names = new HashSet<>();
            for (StateValue value : required.values()) {
                if (!(value instanceof StateValue.StringValue name) || !names.add(name.value())) {
                    throw new ToolBindingException(
                            "JSON Schema required entries at " + path + " must be unique strings.");
                }
            }
            if (schema.values().get("properties") instanceof StateValue.ObjectValue properties
                    && !properties.values().keySet().containsAll(names)) {
                throw new ToolBindingException(
                        "JSON Schema required entries at " + path + " must name declared properties.");
            }
        }
        StateValue additional = schema.values().get("additionalProperties");
        if (additional != null
                && !(additional instanceof StateValue.BooleanValue)
                && !(additional instanceof StateValue.ObjectValue)) {
            throw new ToolBindingException(
                    "JSON Schema additionalProperties at " + path + " must be Boolean or an object.");
        }
        if (additional instanceof StateValue.ObjectValue child) {
            validateSchema(child, path + ".additionalProperties");
        }
        StateValue items = schema.values().get("items");
        if (items != null && !(items instanceof StateValue.ObjectValue)) {
            throw new ToolBindingException("JSON Schema items at " + path + " must be an object.");
        }
        if (items instanceof StateValue.ObjectValue child) {
            validateSchema(child, path + ".items");
        }
        StateValue anyOf = schema.values().get("anyOf");
        if (anyOf != null
                && (!(anyOf instanceof StateValue.ArrayValue array)
                        || array.values().isEmpty())) {
            throw new ToolBindingException("JSON Schema anyOf at " + path + " must be a non-empty array.");
        }
        if (anyOf instanceof StateValue.ArrayValue alternatives) {
            if (type != null) {
                throw new ToolBindingException("JSON Schema at " + path + " cannot combine type and anyOf.");
            }
            for (int index = 0; index < alternatives.values().size(); index++) {
                StateValue alternative = alternatives.values().get(index);
                if (!(alternative instanceof StateValue.ObjectValue child)) {
                    throw new ToolBindingException(
                            "JSON Schema anyOf entry at " + path + "[" + index + "] must be an object.");
                }
                validateSchema(child, path + ".anyOf[" + index + "]");
            }
        }
        int minimum = integerKeyword(schema, "minLength", 0);
        int maximum = integerKeyword(schema, "maxLength", Integer.MAX_VALUE);
        if (minimum < 0 || maximum < 0 || minimum > maximum) {
            throw new ToolBindingException(
                    "JSON Schema string lengths at " + path + " must be non-negative and ordered.");
        }
    }

    static void validate(StateValue value, StateValue.ObjectValue schema, String path) {
        StateValue anyOfValue = schema.values().get("anyOf");
        if (anyOfValue instanceof StateValue.ArrayValue alternatives) {
            ToolBindingException lastFailure = null;
            for (StateValue alternative : alternatives.values()) {
                if (!(alternative instanceof StateValue.ObjectValue alternativeSchema)) {
                    throw new ToolBindingException("Schema anyOf entry at " + path + " must be an object.");
                }
                try {
                    validate(value, alternativeSchema, path);
                    return;
                } catch (ToolBindingException failure) {
                    lastFailure = failure;
                }
            }
            throw new ToolBindingException("Value at " + path + " does not match any allowed schema.", lastFailure);
        }

        String type = schemaType(schema);
        if (type == null) {
            return;
        }
        switch (type) {
            case "null" -> {
                if (value != StateValue.NullValue.INSTANCE) {
                    throw mismatch(path, type);
                }
            }
            case "boolean" -> {
                if (!(value instanceof StateValue.BooleanValue)) {
                    throw mismatch(path, type);
                }
            }
            case "integer" -> {
                if (!(value instanceof StateValue.NumberValue number)
                        || number.value().stripTrailingZeros().scale() > 0) {
                    throw mismatch(path, type);
                }
            }
            case "number" -> {
                if (!(value instanceof StateValue.NumberValue)) {
                    throw mismatch(path, type);
                }
            }
            case "string" -> validateString(value, schema, path);
            case "array" -> validateArray(value, schema, path);
            case "object" -> validateObject(value, schema, path);
            default -> throw new ToolBindingException("Unsupported JSON Schema type '" + type + "' at " + path + ".");
        }
    }

    private static void validateString(StateValue value, StateValue.ObjectValue schema, String path) {
        if (!(value instanceof StateValue.StringValue stringValue)) {
            throw mismatch(path, "string");
        }
        StateValue enumValue = schema.values().get("enum");
        if (enumValue instanceof StateValue.ArrayValue allowed
                && allowed.values().stream().noneMatch(stringValue::equals)) {
            throw new ToolBindingException("String at " + path + " is not one of the declared enum values.");
        }
        int minimum = integerKeyword(schema, "minLength", 0);
        int maximum = integerKeyword(schema, "maxLength", Integer.MAX_VALUE);
        if (stringValue.value().length() < minimum || stringValue.value().length() > maximum) {
            throw new ToolBindingException(
                    "String length at " + path + " must be between " + minimum + " and " + maximum + ".");
        }
    }

    private static void validateArray(StateValue value, StateValue.ObjectValue schema, String path) {
        if (!(value instanceof StateValue.ArrayValue array)) {
            throw mismatch(path, "array");
        }
        StateValue itemSchema = schema.values().get("items");
        if (itemSchema instanceof StateValue.ObjectValue objectSchema) {
            for (int index = 0; index < array.values().size(); index++) {
                validate(array.values().get(index), objectSchema, path + "[" + index + "]");
            }
        }
    }

    private static void validateObject(StateValue value, StateValue.ObjectValue schema, String path) {
        if (!(value instanceof StateValue.ObjectValue object)) {
            throw mismatch(path, "object");
        }
        Map<String, StateValue> properties =
                schema.values().get("properties") instanceof StateValue.ObjectValue propertyObject
                        ? propertyObject.values()
                        : Map.of();
        List<String> required = schema.values().get("required") instanceof StateValue.ArrayValue requiredArray
                ? requiredArray.values().stream()
                        .map(item -> {
                            if (!(item instanceof StateValue.StringValue stringValue)) {
                                throw new ToolBindingException(
                                        "Required schema entry at " + path + " must be a string.");
                            }
                            return stringValue.value();
                        })
                        .toList()
                : List.of();
        List<String> missing = required.stream()
                .filter(name -> !object.values().containsKey(name))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new ToolBindingException("Missing required field(s) at " + path + ": " + missing + ".");
        }
        StateValue additional = schema.values().get("additionalProperties");
        for (Map.Entry<String, StateValue> entry : object.values().entrySet()) {
            StateValue declared = properties.get(entry.getKey());
            if (declared instanceof StateValue.ObjectValue declaredSchema) {
                validate(entry.getValue(), declaredSchema, path + "." + entry.getKey());
            } else if (additional instanceof StateValue.BooleanValue bool && !bool.value()) {
                throw new ToolBindingException("Unexpected field '" + entry.getKey() + "' at " + path + ".");
            } else if (additional instanceof StateValue.ObjectValue additionalSchema) {
                validate(entry.getValue(), additionalSchema, path + "." + entry.getKey());
            }
        }
    }

    private static String schemaType(StateValue.ObjectValue schema) {
        StateValue type = schema.values().get("type");
        if (type == null) {
            return null;
        }
        if (type instanceof StateValue.StringValue stringValue) {
            return stringValue.value();
        }
        throw new ToolBindingException("JSON Schema type must be a string.");
    }

    private static int integerKeyword(StateValue.ObjectValue schema, String name, int defaultValue) {
        StateValue value = schema.values().get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof StateValue.NumberValue number) {
            try {
                return number.value().intValueExact();
            } catch (ArithmeticException exception) {
                throw new ToolBindingException("JSON Schema " + name + " must be an integer.", exception);
            }
        }
        throw new ToolBindingException("JSON Schema " + name + " must be an integer.");
    }

    private static ToolBindingException mismatch(String path, String type) {
        return new ToolBindingException("Expected JSON Schema type " + type + " at " + path + ".");
    }
}
