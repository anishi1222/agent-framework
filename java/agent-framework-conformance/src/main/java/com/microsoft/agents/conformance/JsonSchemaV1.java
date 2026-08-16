// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Shared, strict JSON shape checks for fixture schema version 1. */
final class JsonSchemaV1 {
    private JsonSchemaV1() {}

    static void exactObject(JsonNode node, String sourceName, String... requiredFields) {
        object(node, sourceName, List.of(requiredFields), List.of());
    }

    static void object(JsonNode node, String sourceName, List<String> requiredFields, List<String> optionalFields) {
        if (!node.isObject()) {
            throw invalid(sourceName + " must be a JSON object.");
        }
        Set<String> allowed = new HashSet<>(requiredFields);
        allowed.addAll(optionalFields);
        for (String requiredField : requiredFields) {
            if (!node.has(requiredField)) {
                throw invalid(sourceName + " is missing required field '" + requiredField + "'.");
            }
        }
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                throw invalid(sourceName + " contains unknown field '" + field + "'.");
            }
        }
    }

    static JsonNode require(JsonNode object, String field, String sourceName) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            throw invalid(sourceName + " is missing required field '" + field + "'.");
        }
        return value;
    }

    static JsonNode requireNullable(JsonNode object, String field, String sourceName) {
        if (!object.has(field)) {
            throw invalid(sourceName + " is missing required field '" + field + "'.");
        }
        return object.get(field);
    }

    static JsonNode requireObject(JsonNode object, String field, String sourceName) {
        JsonNode value = require(object, field, sourceName);
        if (!value.isObject()) {
            throw invalid(path(sourceName, field) + " must be a JSON object.");
        }
        return value;
    }

    static JsonNode requireArray(JsonNode object, String field, String sourceName, boolean nonEmpty) {
        JsonNode value = require(object, field, sourceName);
        if (!value.isArray()) {
            throw invalid(path(sourceName, field) + " must be an array.");
        }
        if (nonEmpty && value.isEmpty()) {
            throw invalid(path(sourceName, field) + " must not be empty.");
        }
        return value;
    }

    static String requireText(JsonNode object, String field, String sourceName) {
        JsonNode value = require(object, field, sourceName);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw invalid(path(sourceName, field) + " must be a non-blank string.");
        }
        return value.textValue();
    }

    static String requireString(JsonNode object, String field, String sourceName) {
        JsonNode value = require(object, field, sourceName);
        if (!value.isTextual()) {
            throw invalid(path(sourceName, field) + " must be a string.");
        }
        return value.textValue();
    }

    static String optionalText(JsonNode object, String field, String sourceName) {
        JsonNode value = object.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw invalid(path(sourceName, field) + " must be a non-blank string.");
        }
        return value.textValue();
    }

    static int requireInteger(JsonNode object, String field, String sourceName) {
        JsonNode value = require(object, field, sourceName);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(path(sourceName, field) + " must be an integer.");
        }
        return value.intValue();
    }

    static int requireNonNegativeInteger(JsonNode object, String field, String sourceName) {
        int value = requireInteger(object, field, sourceName);
        if (value < 0) {
            throw invalid(path(sourceName, field) + " must be non-negative.");
        }
        return value;
    }

    static int requirePositiveInteger(JsonNode object, String field, String sourceName) {
        int value = requireInteger(object, field, sourceName);
        if (value <= 0) {
            throw invalid(path(sourceName, field) + " must be positive.");
        }
        return value;
    }

    static void requireNumber(JsonNode object, String field, String sourceName) {
        if (!require(object, field, sourceName).isNumber()) {
            throw invalid(path(sourceName, field) + " must be a number.");
        }
    }

    static boolean requireBoolean(JsonNode object, String field, String sourceName) {
        JsonNode value = require(object, field, sourceName);
        if (!value.isBoolean()) {
            throw invalid(path(sourceName, field) + " must be a Boolean.");
        }
        return value.booleanValue();
    }

    static void requireLiteral(JsonNode object, String field, String expected, String sourceName) {
        String actual = requireText(object, field, sourceName);
        if (!expected.equals(actual)) {
            throw invalid(path(sourceName, field) + " must be '" + expected + "'.");
        }
    }

    static List<String> requireTextArray(
            JsonNode object, String field, String sourceName, boolean nonEmpty, boolean unique) {
        JsonNode array = requireArray(object, field, sourceName, nonEmpty);
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (int index = 0; index < array.size(); index++) {
            JsonNode element = array.get(index);
            if (!element.isTextual() || element.textValue().isBlank()) {
                throw invalid(path(sourceName, field) + "[" + index + "] must be a non-blank string.");
            }
            String value = element.textValue();
            if (unique && !seen.add(value)) {
                throw invalid(path(sourceName, field) + " contains duplicate value '" + value + "'.");
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    static void requireBooleanArray(JsonNode object, String field, String sourceName, boolean nonEmpty) {
        JsonNode array = requireArray(object, field, sourceName, nonEmpty);
        for (int index = 0; index < array.size(); index++) {
            if (!array.get(index).isBoolean()) {
                throw invalid(path(sourceName, field) + "[" + index + "] must be a Boolean.");
            }
        }
    }

    static void requireIntegerArray(JsonNode object, String field, String sourceName, boolean nonEmpty) {
        JsonNode array = requireArray(object, field, sourceName, nonEmpty);
        for (int index = 0; index < array.size(); index++) {
            JsonNode value = array.get(index);
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                throw invalid(path(sourceName, field) + "[" + index + "] must be an integer.");
            }
        }
    }

    static void requireIntegerObject(JsonNode object, String field, String sourceName) {
        JsonNode values = requireObject(object, field, sourceName);
        if (values.isEmpty()) {
            throw invalid(path(sourceName, field) + " must not be empty.");
        }
        values.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                throw invalid(path(sourceName, field) + "." + entry.getKey() + " must be an integer.");
            }
        });
    }

    static String indexed(String sourceName, int index) {
        return sourceName + "[" + index + "]";
    }

    static String path(String sourceName, String field) {
        return sourceName + " field '" + field + "'";
    }

    static ConformanceValidationException invalid(String message) {
        return new ConformanceValidationException(message);
    }
}
