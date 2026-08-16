// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SkillFrontmatterParser {
    private static final Pattern FRONTMATTER =
            Pattern.compile("\\A\\uFEFF?---\\s*$\\R(.+?)^---\\s*$", Pattern.MULTILINE | Pattern.DOTALL);

    private static final Pattern KEY_VALUE = Pattern.compile("^([\\w-]+)\\s*:\\s*(?:[\"'](.*?)[\"']|(.*?))\\s*$");

    private SkillFrontmatterParser() {}

    static SkillFrontmatter parse(String content) {
        Matcher frontmatter = FRONTMATTER.matcher(content);
        if (!frontmatter.find()) {
            throw new IllegalArgumentException("SKILL.md must begin with YAML frontmatter.");
        }
        String yaml = frontmatter.group(1);
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        String[] lines = yaml.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.isBlank() || line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                continue;
            }
            Matcher match = KEY_VALUE.matcher(line);
            if (!match.matches()) {
                continue;
            }
            String key = match.group(1);
            String value = match.group(2) != null ? match.group(2) : match.group(3);
            if ("metadata".equals(key) && value.isEmpty()) {
                while (index + 1 < lines.length
                        && (lines[index + 1].isBlank()
                                || lines[index + 1].charAt(0) == ' '
                                || lines[index + 1].charAt(0) == '\t')) {
                    String nested = lines[++index];
                    Matcher nestedMatch = KEY_VALUE.matcher(nested.stripLeading());
                    if (nestedMatch.matches()) {
                        metadata.put(
                                nestedMatch.group(1),
                                nestedMatch.group(2) != null ? nestedMatch.group(2) : nestedMatch.group(3));
                    }
                }
                continue;
            }
            if (value.startsWith("|") || value.startsWith(">")) {
                BlockScalar scalar = parseBlock(lines, index + 1, value);
                value = scalar.value();
                index = scalar.lastLine();
            }
            values.put(key, value);
        }
        return new SkillFrontmatter(
                values.get("name"),
                values.get("description"),
                emptyToNull(values.get("license")),
                emptyToNull(values.get("compatibility")),
                emptyToNull(values.get("allowed-tools")),
                metadata);
    }

    private static BlockScalar parseBlock(String[] lines, int start, String indicator) {
        java.util.ArrayList<String> collected = new java.util.ArrayList<>();
        int index = start;
        while (index < lines.length) {
            String line = lines[index];
            if (!line.isBlank() && line.charAt(0) != ' ' && line.charAt(0) != '\t') {
                break;
            }
            collected.add(line);
            index++;
        }
        while (!collected.isEmpty() && collected.getLast().isBlank()) {
            collected.removeLast();
        }
        if (collected.isEmpty()) {
            return new BlockScalar("", index - 1);
        }
        int indent = collected.stream()
                .filter(line -> !line.isBlank())
                .mapToInt(SkillFrontmatterParser::indent)
                .min()
                .orElse(0);
        java.util.List<String> normalized = collected.stream()
                .map(line -> line.isBlank() ? "" : line.substring(Math.min(indent, line.length())))
                .toList();
        String parsed = indicator.charAt(0) == '|'
                ? String.join("\n", normalized)
                : normalized.stream().filter(line -> !line.isEmpty()).collect(java.util.stream.Collectors.joining(" "));
        if (indicator.length() > 1 && indicator.charAt(1) == '+') {
            parsed += "\n";
        } else if (!(indicator.length() > 1 && indicator.charAt(1) == '-') && indicator.charAt(0) == '|') {
            parsed += "\n";
        }
        return new BlockScalar(parsed, index - 1);
    }

    private static int indent(String value) {
        int result = 0;
        while (result < value.length() && (value.charAt(result) == ' ' || value.charAt(result) == '\t')) {
            result++;
        }
        return result;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record BlockScalar(String value, int lastLine) {}
}
