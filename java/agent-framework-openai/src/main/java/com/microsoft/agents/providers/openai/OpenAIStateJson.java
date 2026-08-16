// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.agents.core.StateValue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

final class OpenAIStateJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenAIStateJson() {}

    static String write(StateValue value) {
        try {
            return MAPPER.writeValueAsString(toJava(value));
        } catch (JsonProcessingException exception) {
            throw new OpenAIProtocolException("Unable to encode OpenAI JSON value.", null, "invalid_json");
        }
    }

    static StateValue read(String value) {
        try {
            return fromNode(MAPPER.readTree(value));
        } catch (JsonProcessingException exception) {
            throw new OpenAIProtocolException("OpenAI returned malformed JSON arguments.", null, "invalid_json");
        }
    }

    static Object toJava(StateValue value) {
        return switch (value) {
            case StateValue.NullValue _ -> null;
            case StateValue.BooleanValue booleanValue -> booleanValue.value();
            case StateValue.NumberValue number -> number.value();
            case StateValue.StringValue string -> string.value();
            case StateValue.ArrayValue array ->
                array.values().stream().map(OpenAIStateJson::toJava).toList();
            case StateValue.ObjectValue object -> {
                LinkedHashMap<String, Object> values = new LinkedHashMap<>();
                object.values().forEach((key, item) -> values.put(key, toJava(item)));
                yield values;
            }
        };
    }

    private static StateValue fromNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return StateValue.nullValue();
        }
        if (node.isBoolean()) {
            return StateValue.bool(node.booleanValue());
        }
        if (node.isNumber()) {
            return StateValue.number(new BigDecimal(node.asText()));
        }
        if (node.isTextual()) {
            return StateValue.string(node.textValue());
        }
        if (node.isArray()) {
            ArrayList<StateValue> values = new ArrayList<>();
            node.forEach(item -> values.add(fromNode(item)));
            return StateValue.array(values);
        }
        if (node.isObject()) {
            LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
            node.properties().forEach(entry -> values.put(entry.getKey(), fromNode(entry.getValue())));
            return StateValue.object(values);
        }
        throw new OpenAIProtocolException("OpenAI returned an unsupported JSON value.", null, "invalid_json");
    }

    static StateValue fromJava(Object value) {
        return fromNode(MAPPER.valueToTree(value));
    }

    static Map<String, StateValue> fromJavaMap(Map<String, ?> values) {
        LinkedHashMap<String, StateValue> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, fromJava(value)));
        return Map.copyOf(result);
    }
}
