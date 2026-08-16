// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.declarative;

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
import java.util.Map;
import java.util.Set;

/** Parses strict JSON and YAML prompt-agent documents without exposing parser-library types. */
public final class PromptAgentDefinitionParser {
    private static final Set<String> AGENT_FIELDS = Set.of(
            "kind",
            "name",
            "displayName",
            "description",
            "metadata",
            "model",
            "tools",
            "contextProviders",
            "instructions",
            "additionalInstructions");

    private static final Set<String> MODEL_FIELDS = Set.of("id", "provider", "apiType", "options");

    private static final Set<String> OPTION_FIELDS = Set.of(
            "frequencyPenalty",
            "maxOutputTokens",
            "presencePenalty",
            "seed",
            "temperature",
            "topP",
            "stopSequences",
            "allowMultipleToolCalls");

    private PromptAgentDefinitionParser() {}

    /**
     * Parses a strict JSON definition.
     *
     * @param json complete JSON document
     * @return immutable prompt-agent definition
     */
    public static PromptAgentDefinition parseJson(String json) {
        return map(StrictAgentDocumentParser.parseJson(json));
    }

    /**
     * Parses a strict YAML definition.
     *
     * @param yaml complete YAML document
     * @return immutable prompt-agent definition
     */
    public static PromptAgentDefinition parseYaml(String yaml) {
        return map(StrictAgentDocumentParser.parseYaml(yaml));
    }

    /**
     * Parses UTF-8 JSON or YAML selected by a {@code .json}, {@code .yaml}, or {@code .yml}
     * extension.
     *
     * @param path definition path
     * @return immutable prompt-agent definition
     */
    public static PromptAgentDefinition parse(Path path) {
        Path checked = path.toAbsolutePath().normalize();
        String fileName = checked.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (Files.size(checked) > StrictAgentDocumentParser.MAX_DOCUMENT_CHARACTERS * 4L) {
                throw new DeclarativeAgentParseException("Agent definition file is too large.");
            }
            String document = Files.readString(checked, StandardCharsets.UTF_8);
            if (fileName.endsWith(".json")) {
                return parseJson(document);
            }
            if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
                return parseYaml(document);
            }
            throw new DeclarativeAgentParseException("Agent definition path must end in .json, .yaml, or .yml.");
        } catch (IOException failure) {
            throw new DeclarativeAgentParseException(
                    "Unable to read agent definition file '" + checked + "'.", failure);
        }
    }

    private static PromptAgentDefinition map(StateValue root) {
        try {
            StateValue.ObjectValue agent = requireObject(root, "$");
            rejectUnknown(agent, AGENT_FIELDS, "$");
            PromptModelDefinition model = mapModel(requireObject(require(agent, "model", "$"), "$.model"));
            return new PromptAgentDefinition(
                    requireString(agent, "kind", "$"),
                    requireString(agent, "name", "$"),
                    optionalString(agent, "displayName", "$"),
                    optionalString(agent, "description", "$"),
                    optionalObject(agent, "metadata", "$").values(),
                    model,
                    optionalStringList(agent, "tools", "$"),
                    optionalStringList(agent, "contextProviders", "$"),
                    optionalString(agent, "instructions", "$"),
                    optionalString(agent, "additionalInstructions", "$"));
        } catch (DeclarativeAgentParseException failure) {
            throw failure;
        } catch (DeclarativeAgentValidationException failure) {
            throw new DeclarativeAgentParseException(failure.getMessage(), failure);
        }
    }

    private static PromptModelDefinition mapModel(StateValue.ObjectValue model) {
        rejectUnknown(model, MODEL_FIELDS, "$.model");
        StateValue optionsValue = model.values().get("options");
        PromptModelOptions options = optionsValue == null
                ? PromptModelOptions.empty()
                : mapOptions(requireObject(optionsValue, "$.model.options"));
        return new PromptModelDefinition(
                requireString(model, "id", "$.model"),
                optionalString(model, "provider", "$.model"),
                optionalString(model, "apiType", "$.model"),
                options);
    }

    private static PromptModelOptions mapOptions(StateValue.ObjectValue options) {
        rejectUnknown(options, OPTION_FIELDS, "$.model.options");
        return new PromptModelOptions(
                optionalDouble(options, "frequencyPenalty", "$.model.options"),
                optionalInteger(options, "maxOutputTokens", "$.model.options"),
                optionalDouble(options, "presencePenalty", "$.model.options"),
                optionalLong(options, "seed", "$.model.options"),
                optionalDouble(options, "temperature", "$.model.options"),
                optionalDouble(options, "topP", "$.model.options"),
                optionalStringList(options, "stopSequences", "$.model.options"),
                optionalBoolean(options, "allowMultipleToolCalls", "$.model.options"));
    }

    private static StateValue require(StateValue.ObjectValue object, String name, String path) {
        StateValue value = object.values().get(name);
        if (value == null) {
            throw new DeclarativeAgentParseException("Required field '" + path + "." + name + "' is missing.");
        }
        return value;
    }

    private static StateValue.ObjectValue requireObject(StateValue value, String path) {
        if (value instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw typeError(path, "object");
    }

    private static StateValue.ObjectValue optionalObject(StateValue.ObjectValue object, String name, String path) {
        StateValue value = object.values().get(name);
        return value == null ? StateValue.object(Map.of()) : requireObject(value, path + "." + name);
    }

    private static String requireString(StateValue.ObjectValue object, String name, String path) {
        StateValue value = require(object, name, path);
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw typeError(path + "." + name, "string");
    }

    private static String optionalString(StateValue.ObjectValue object, String name, String path) {
        StateValue value = object.values().get(name);
        if (value == null || value instanceof StateValue.NullValue) {
            return null;
        }
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw typeError(path + "." + name, "string or null");
    }

    private static List<String> optionalStringList(StateValue.ObjectValue object, String name, String path) {
        StateValue value = object.values().get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof StateValue.ArrayValue array)) {
            throw typeError(path + "." + name, "array of strings");
        }
        ArrayList<String> strings = new ArrayList<>(array.values().size());
        for (int index = 0; index < array.values().size(); index++) {
            StateValue item = array.values().get(index);
            if (!(item instanceof StateValue.StringValue string)) {
                throw typeError(path + "." + name + "[" + index + "]", "string");
            }
            strings.add(string.value());
        }
        return List.copyOf(strings);
    }

    private static Double optionalDouble(StateValue.ObjectValue object, String name, String path) {
        StateValue value = object.values().get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof StateValue.NumberValue number) {
            double converted = number.value().doubleValue();
            if (Double.isFinite(converted)) {
                return converted;
            }
        }
        throw typeError(path + "." + name, "finite number");
    }

    private static Integer optionalInteger(StateValue.ObjectValue object, String name, String path) {
        Long value = optionalLong(object, name, path);
        if (value == null) {
            return null;
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new DeclarativeAgentParseException(
                    "Field '" + path + "." + name + "' is outside the 32-bit integer range.");
        }
        return value.intValue();
    }

    private static Long optionalLong(StateValue.ObjectValue object, String name, String path) {
        StateValue value = object.values().get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof StateValue.NumberValue number) {
            BigDecimal decimal = number.value();
            try {
                return decimal.longValueExact();
            } catch (ArithmeticException failure) {
                throw new DeclarativeAgentParseException(
                        "Field '" + path + "." + name + "' must be an integer.", failure);
            }
        }
        throw typeError(path + "." + name, "integer");
    }

    private static Boolean optionalBoolean(StateValue.ObjectValue object, String name, String path) {
        StateValue value = object.values().get(name);
        if (value == null) {
            return null;
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
            throw new DeclarativeAgentParseException(
                    "Unknown field" + (unknown.size() == 1 ? "" : "s") + " at '" + path + "': " + unknown + ".");
        }
    }

    private static DeclarativeAgentParseException typeError(String path, String expected) {
        return new DeclarativeAgentParseException("Field '" + path + "' must be " + expected + ".");
    }
}
