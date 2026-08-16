// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows.declarative;

import com.microsoft.agents.core.StateValue;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parses strict JSON and YAML declarative workflow documents. */
public final class DeclarativeWorkflowDefinitionParser {
    private static final Set<String> ROOT_FIELDS =
            Set.of("kind", "id", "schemaVersion", "allowCycles", "entry", "output", "nodes", "edges");

    private static final Set<String> NODE_FIELDS = Set.of("id", "executor");

    private static final Set<String> DIRECT_FIELDS = Set.of("kind", "source", "target");

    private static final Set<String> CONDITIONAL_FIELDS = Set.of("kind", "source", "target", "condition");

    private static final Set<String> FAN_OUT_FIELDS = Set.of("kind", "source", "targets");

    private static final Set<String> FAN_IN_FIELDS = Set.of("kind", "sources", "target");

    private DeclarativeWorkflowDefinitionParser() {}

    /**
     * Parses a strict JSON workflow definition.
     *
     * @param json complete JSON document
     * @return immutable workflow definition
     */
    public static DeclarativeWorkflowDefinition parseJson(String json) {
        return map(StrictWorkflowDocumentParser.parseJson(json));
    }

    /**
     * Parses a strict YAML workflow definition.
     *
     * @param yaml complete YAML document
     * @return immutable workflow definition
     */
    public static DeclarativeWorkflowDefinition parseYaml(String yaml) {
        return map(StrictWorkflowDocumentParser.parseYaml(yaml));
    }

    /**
     * Parses UTF-8 JSON or YAML selected by a {@code .json}, {@code .yaml}, or {@code .yml}
     * extension.
     *
     * @param path workflow definition path
     * @return immutable workflow definition
     */
    public static DeclarativeWorkflowDefinition parse(Path path) {
        Path checked = path.toAbsolutePath().normalize();
        String fileName = checked.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (Files.size(checked) > StrictWorkflowDocumentParser.MAX_DOCUMENT_CHARACTERS * 4L) {
                throw new DeclarativeWorkflowParseException("Workflow definition file is too large.");
            }
            String document = Files.readString(checked, StandardCharsets.UTF_8);
            if (fileName.endsWith(".json")) {
                return parseJson(document);
            }
            if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
                return parseYaml(document);
            }
            throw new DeclarativeWorkflowParseException("Workflow definition path must end in .json, .yaml, or .yml.");
        } catch (IOException failure) {
            throw new DeclarativeWorkflowParseException(
                    "Unable to read workflow definition file '" + checked + "'.", failure);
        }
    }

    private static DeclarativeWorkflowDefinition map(StateValue root) {
        try {
            StateValue.ObjectValue workflow = requireObject(root, "$");
            rejectUnknown(workflow, ROOT_FIELDS, "$");
            return new DeclarativeWorkflowDefinition(
                    requireString(workflow, "kind", "$"),
                    requireString(workflow, "id", "$"),
                    optionalInteger(workflow, "schemaVersion", "$", 1),
                    optionalBoolean(workflow, "allowCycles", "$", false),
                    requireString(workflow, "entry", "$"),
                    requireString(workflow, "output", "$"),
                    mapNodes(requireArray(require(workflow, "nodes", "$"), "$.nodes")),
                    mapEdges(optionalArray(workflow, "edges", "$")));
        } catch (DeclarativeWorkflowParseException failure) {
            throw failure;
        } catch (DeclarativeWorkflowValidationException failure) {
            throw new DeclarativeWorkflowParseException(failure.getMessage(), failure);
        }
    }

    private static List<DeclarativeNodeDefinition> mapNodes(StateValue.ArrayValue nodes) {
        ArrayList<DeclarativeNodeDefinition> mapped =
                new ArrayList<>(nodes.values().size());
        for (int index = 0; index < nodes.values().size(); index++) {
            String path = "$.nodes[" + index + "]";
            StateValue.ObjectValue node = requireObject(nodes.values().get(index), path);
            rejectUnknown(node, NODE_FIELDS, path);
            mapped.add(new DeclarativeNodeDefinition(
                    requireString(node, "id", path), requireString(node, "executor", path)));
        }
        return List.copyOf(mapped);
    }

    private static List<DeclarativeEdgeDefinition> mapEdges(StateValue.ArrayValue edges) {
        ArrayList<DeclarativeEdgeDefinition> mapped =
                new ArrayList<>(edges.values().size());
        for (int index = 0; index < edges.values().size(); index++) {
            String path = "$.edges[" + index + "]";
            StateValue.ObjectValue edge = requireObject(edges.values().get(index), path);
            String kind = requireString(edge, "kind", path);
            mapped.add(
                    switch (kind) {
                        case "direct" -> {
                            rejectUnknown(edge, DIRECT_FIELDS, path);
                            yield new DirectEdgeDefinition(
                                    requireString(edge, "source", path), requireString(edge, "target", path));
                        }
                        case "conditional" -> {
                            rejectUnknown(edge, CONDITIONAL_FIELDS, path);
                            yield new ConditionalEdgeDefinition(
                                    requireString(edge, "source", path),
                                    requireString(edge, "target", path),
                                    requireString(edge, "condition", path));
                        }
                        case "fanOut" -> {
                            rejectUnknown(edge, FAN_OUT_FIELDS, path);
                            yield new FanOutEdgeDefinition(
                                    requireString(edge, "source", path),
                                    stringList(require(edge, "targets", path), path + ".targets"));
                        }
                        case "fanIn" -> {
                            rejectUnknown(edge, FAN_IN_FIELDS, path);
                            yield new FanInEdgeDefinition(
                                    stringList(require(edge, "sources", path), path + ".sources"),
                                    requireString(edge, "target", path));
                        }
                        default ->
                            throw new DeclarativeWorkflowParseException(
                                    "Unsupported edge kind '" + kind + "' at '" + path + "'.");
                    });
        }
        return List.copyOf(mapped);
    }

    private static StateValue require(StateValue.ObjectValue object, String name, String path) {
        StateValue value = object.values().get(name);
        if (value == null) {
            throw new DeclarativeWorkflowParseException("Required field '" + path + "." + name + "' is missing.");
        }
        return value;
    }

    private static StateValue.ObjectValue requireObject(StateValue value, String path) {
        if (value instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw typeError(path, "object");
    }

    private static StateValue.ArrayValue requireArray(StateValue value, String path) {
        if (value instanceof StateValue.ArrayValue array) {
            return array;
        }
        throw typeError(path, "array");
    }

    private static StateValue.ArrayValue optionalArray(StateValue.ObjectValue object, String name, String path) {
        StateValue value = object.values().get(name);
        return value == null ? StateValue.array(List.of()) : requireArray(value, path + "." + name);
    }

    private static String requireString(StateValue.ObjectValue object, String name, String path) {
        StateValue value = require(object, name, path);
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw typeError(path + "." + name, "string");
    }

    private static List<String> stringList(StateValue value, String path) {
        StateValue.ArrayValue array = requireArray(value, path);
        ArrayList<String> strings = new ArrayList<>(array.values().size());
        for (int index = 0; index < array.values().size(); index++) {
            StateValue item = array.values().get(index);
            if (!(item instanceof StateValue.StringValue string)) {
                throw typeError(path + "[" + index + "]", "string");
            }
            strings.add(string.value());
        }
        return List.copyOf(strings);
    }

    private static int optionalInteger(StateValue.ObjectValue object, String name, String path, int defaultValue) {
        StateValue value = object.values().get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof StateValue.NumberValue number) {
            BigDecimal decimal = number.value();
            try {
                return decimal.intValueExact();
            } catch (ArithmeticException failure) {
                throw new DeclarativeWorkflowParseException(
                        "Field '" + path + "." + name + "' must be a 32-bit integer.", failure);
            }
        }
        throw typeError(path + "." + name, "integer");
    }

    private static boolean optionalBoolean(
            StateValue.ObjectValue object, String name, String path, boolean defaultValue) {
        StateValue value = object.values().get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof StateValue.BooleanValue bool) {
            return bool.value();
        }
        throw typeError(path + "." + name, "boolean");
    }

    private static void rejectUnknown(StateValue.ObjectValue object, Set<String> allowed, String path) {
        List<String> unknown = object.values().keySet().stream()
                .filter(name -> !allowed.contains(name))
                .sorted(Comparator.naturalOrder())
                .toList();
        if (!unknown.isEmpty()) {
            throw new DeclarativeWorkflowParseException(
                    "Unknown field" + (unknown.size() == 1 ? "" : "s") + " at '" + path + "': " + unknown + ".");
        }
    }

    private static DeclarativeWorkflowParseException typeError(String path, String expected) {
        return new DeclarativeWorkflowParseException("Field '" + path + "' must be " + expected + ".");
    }
}
