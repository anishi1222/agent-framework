// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.StateValue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class SkillRendering {
    private SkillRendering() {}

    static String codeDefinedContent(
            SkillFrontmatter frontmatter,
            String instructions,
            List<SkillResource> resources,
            List<SkillScript> scripts) {
        return "<name>"
                + xml(frontmatter.name(), false)
                + "</name>\n<description>"
                + xml(frontmatter.description(), false)
                + "</description>\n\n<instructions>\n"
                + instructions
                + "\n</instructions>\n\n"
                + resourcesBlock(resources)
                + "\n\n"
                + scriptsBlock(scripts);
    }

    static String fileContent(String rawContent, List<SkillResource> resources, List<SkillScript> scripts) {
        return rawContent + "\n\n" + resourcesBlock(resources) + "\n\n" + scriptsBlock(scripts);
    }

    static String availableSkills(String template, List<Skill> skills) {
        ArrayList<Skill> sorted = new ArrayList<>(skills);
        sorted.sort(Comparator.comparing(skill -> skill.frontmatter().name()));
        StringBuilder entries = new StringBuilder();
        for (Skill skill : sorted) {
            if (!entries.isEmpty()) {
                entries.append('\n');
            }
            entries.append("  <skill>\n")
                    .append("    <name>")
                    .append(xml(skill.frontmatter().name(), false))
                    .append("</name>\n")
                    .append("    <description>")
                    .append(xml(skill.frontmatter().description(), false))
                    .append("</description>\n")
                    .append("  </skill>");
        }
        return template.replace("{skills}", entries)
                .replace("{resource_instructions}", SkillsProvider.RESOURCE_INSTRUCTIONS)
                .replace("{runner_instructions}", SkillsProvider.SCRIPT_RUNNER_INSTRUCTIONS);
    }

    static String xml(String value, boolean attribute) {
        String escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        if (attribute) {
            escaped = escaped.replace("\"", "&quot;").replace("'", "&#x27;");
        }
        return escaped;
    }

    private static String resourcesBlock(List<SkillResource> resources) {
        if (resources.isEmpty()) {
            return "<available_resources />";
        }
        StringBuilder result = new StringBuilder("<available_resources>\n");
        for (SkillResource resource : resources) {
            result.append("  <resource name=\"")
                    .append(xml(resource.name(), true))
                    .append('"');
            if (resource.description() != null) {
                result.append(" description=\"")
                        .append(xml(resource.description(), true))
                        .append('"');
            }
            result.append("/>\n");
        }
        return result.append("</available_resources>").toString();
    }

    private static String scriptsBlock(List<SkillScript> scripts) {
        if (scripts.isEmpty()) {
            return "<available_scripts />";
        }
        StringBuilder result = new StringBuilder("<available_scripts>\n");
        for (SkillScript script : scripts) {
            result.append("  <script name=\"").append(xml(script.name(), true)).append('"');
            if (script.description() != null) {
                result.append(" description=\"")
                        .append(xml(script.description(), true))
                        .append('"');
            }
            if (script.parametersSchema() == null) {
                result.append("/>\n");
            } else {
                result.append(">\n    <parameters_schema>")
                        .append(xml(toJson(script.parametersSchema()), false))
                        .append("</parameters_schema>\n  </script>\n");
            }
        }
        return result.append("</available_scripts>").toString();
    }

    private static String toJson(StateValue value) {
        if (value == StateValue.NullValue.INSTANCE) {
            return "null";
        }
        return switch (value) {
            case StateValue.ObjectValue object ->
                object.values().entrySet().stream()
                        .map(entry -> quote(entry.getKey()) + ":" + toJson(entry.getValue()))
                        .collect(java.util.stream.Collectors.joining(",", "{", "}"));
            case StateValue.ArrayValue array ->
                array.values().stream()
                        .map(SkillRendering::toJson)
                        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
            case StateValue.StringValue string -> quote(string.value());
            case StateValue.NumberValue number ->
                number.value().stripTrailingZeros().toPlainString();
            case StateValue.BooleanValue bool -> Boolean.toString(bool.value());
            default ->
                throw new IllegalArgumentException(
                        "Unsupported skill JSON value: " + value.getClass().getName());
        };
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }
}
