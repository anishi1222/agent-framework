// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows.declarative;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.agents.core.StateValue;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.Event;

final class StrictWorkflowDocumentParser {
    static final int MAX_DOCUMENT_CHARACTERS = 1_000_000;

    private static final int MAX_DEPTH = 64;

    private static final int MAX_NUMBER_LENGTH = 1_000;

    private static final ObjectMapper JSON = createJsonMapper();

    private StrictWorkflowDocumentParser() {}

    static StateValue parseJson(String document) {
        requireDocument(document, "JSON");
        try {
            JsonNode root = JSON.readTree(document);
            if (root == null) {
                throw new DeclarativeWorkflowParseException("JSON workflow definition is empty.");
            }
            return fromJson(root, 0);
        } catch (DeclarativeWorkflowParseException failure) {
            throw failure;
        } catch (JsonProcessingException failure) {
            JsonLocation location = failure.getLocation();
            String suffix =
                    location == null ? "" : " at line " + location.getLineNr() + ", column " + location.getColumnNr();
            throw new DeclarativeWorkflowParseException("Malformed JSON workflow definition" + suffix + ".", failure);
        }
    }

    static StateValue parseYaml(String document) {
        requireDocument(document, "YAML");
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(MAX_DEPTH);
        options.setCodePointLimit(MAX_DOCUMENT_CHARACTERS);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try {
            for (Event event : yaml.parse(new StringReader(document))) {
                if (event instanceof AliasEvent) {
                    throw new DeclarativeWorkflowParseException(
                            "YAML aliases are not allowed in declarative workflow definitions.");
                }
            }
            Iterator<Object> documents = yaml.loadAll(document).iterator();
            if (!documents.hasNext()) {
                throw new DeclarativeWorkflowParseException("YAML workflow definition is empty.");
            }
            Object root = documents.next();
            if (documents.hasNext()) {
                throw new DeclarativeWorkflowParseException(
                        "YAML workflow definition must contain exactly one document.");
            }
            return fromYaml(root, 0);
        } catch (DeclarativeWorkflowParseException failure) {
            throw failure;
        } catch (YAMLException failure) {
            throw new DeclarativeWorkflowParseException("Malformed YAML workflow definition.", failure);
        }
    }

    private static ObjectMapper createJsonMapper() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_DEPTH)
                .maxStringLength(MAX_DOCUMENT_CHARACTERS)
                .maxNameLength(MAX_DOCUMENT_CHARACTERS)
                .maxNumberLength(MAX_NUMBER_LENGTH)
                .build();
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        return new ObjectMapper(factory)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    }

    private static void requireDocument(String document, String format) {
        if (document == null || document.isBlank()) {
            throw new DeclarativeWorkflowParseException(format + " workflow definition is empty.");
        }
        if (document.length() > MAX_DOCUMENT_CHARACTERS) {
            throw new DeclarativeWorkflowParseException(
                    format + " workflow definition exceeds " + MAX_DOCUMENT_CHARACTERS + " characters.");
        }
    }

    private static StateValue fromJson(JsonNode node, int depth) {
        requireDepth(depth);
        if (node.isObject()) {
            LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                values.put(name, fromJson(node.get(name), depth + 1));
            }
            return StateValue.object(values);
        }
        if (node.isArray()) {
            ArrayList<StateValue> values = new ArrayList<>(node.size());
            for (JsonNode value : node) {
                values.add(fromJson(value, depth + 1));
            }
            return StateValue.array(values);
        }
        if (node.isTextual()) {
            return StateValue.string(node.textValue());
        }
        if (node.isBoolean()) {
            return StateValue.bool(node.booleanValue());
        }
        if (node.isIntegralNumber()) {
            return StateValue.integer(node.bigIntegerValue());
        }
        if (node.isNumber()) {
            BigDecimal value = node.decimalValue();
            if (!Double.isFinite(value.doubleValue())) {
                throw new DeclarativeWorkflowParseException("JSON numbers must be finite.");
            }
            return StateValue.number(value);
        }
        if (node.isNull()) {
            return StateValue.nullValue();
        }
        throw new DeclarativeWorkflowParseException("Unsupported JSON token in declarative workflow definition.");
    }

    private static StateValue fromYaml(Object value, int depth) {
        requireDepth(depth);
        if (value == null) {
            return StateValue.nullValue();
        }
        if (value instanceof String string) {
            return StateValue.string(string);
        }
        if (value instanceof Boolean bool) {
            return StateValue.bool(bool);
        }
        if (value instanceof BigInteger integer) {
            return StateValue.integer(integer);
        }
        if (value instanceof BigDecimal decimal) {
            return StateValue.number(decimal);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return StateValue.integer(((Number) value).longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw new DeclarativeWorkflowParseException("YAML numbers must be finite.");
            }
            return StateValue.number(BigDecimal.valueOf(number));
        }
        if (value instanceof List<?> list) {
            ArrayList<StateValue> values = new ArrayList<>(list.size());
            for (Object item : list) {
                values.add(fromYaml(item, depth + 1));
            }
            return StateValue.array(values);
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String name)) {
                    throw new DeclarativeWorkflowParseException("YAML object member names must be strings.");
                }
                values.put(name, fromYaml(entry.getValue(), depth + 1));
            }
            return StateValue.object(values);
        }
        throw new DeclarativeWorkflowParseException(
                "Unsupported YAML scalar type '" + value.getClass().getSimpleName() + "'.");
    }

    private static void requireDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw new DeclarativeWorkflowParseException(
                    "Declarative workflow definition exceeds maximum nesting depth " + MAX_DEPTH + ".");
        }
    }
}
