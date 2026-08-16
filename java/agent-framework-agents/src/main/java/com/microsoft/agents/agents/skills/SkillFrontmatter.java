// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Describes level-one skill discovery metadata.
 *
 * @param name lowercase kebab-case skill name
 * @param description human-readable skill description
 * @param license optional license name or reference
 * @param compatibility optional compatibility description
 * @param allowedTools optional space-delimited pre-approved tool names
 * @param metadata immutable arbitrary metadata
 */
public record SkillFrontmatter(
        String name,
        String description,
        String license,
        String compatibility,
        String allowedTools,
        Map<String, String> metadata) {
    /** Maximum skill-name length from the Agent Skills specification. */
    public static final int MAX_NAME_LENGTH = 64;

    /** Maximum skill-description length from the Agent Skills specification. */
    public static final int MAX_DESCRIPTION_LENGTH = 1024;

    /** Maximum compatibility-description length from the Agent Skills specification. */
    public static final int MAX_COMPATIBILITY_LENGTH = 500;

    private static final Pattern VALID_NAME = Pattern.compile("^[a-z0-9]([a-z0-9]*-[a-z0-9])*[a-z0-9]*$");

    /** Creates validated immutable frontmatter. */
    public SkillFrontmatter {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name cannot be empty.");
        }
        if (name.length() > MAX_NAME_LENGTH || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid skill name '"
                    + name
                    + "': must be "
                    + MAX_NAME_LENGTH
                    + " characters or fewer, use lowercase letters, numbers, and hyphens, and "
                    + "must not start or end with a hyphen or contain consecutive hyphens.");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Skill description cannot be empty.");
        }
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "Skill '" + name + "' description must be " + MAX_DESCRIPTION_LENGTH + " characters or fewer.");
        }
        license = optionalNonBlank(license, "license");
        if (compatibility != null && compatibility.length() > MAX_COMPATIBILITY_LENGTH) {
            throw new IllegalArgumentException(
                    "Skill compatibility must be " + MAX_COMPATIBILITY_LENGTH + " characters or fewer.");
        }
        allowedTools = optionalNonBlank(allowedTools, "allowedTools");
        Objects.requireNonNull(metadata, "metadata");
        LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        metadata.forEach((key, value) ->
                copied.put(requireNonBlank(key, "metadata key"), Objects.requireNonNull(value, "metadata value")));
        metadata = java.util.Collections.unmodifiableMap(copied);
    }

    /**
     * Creates required skill metadata without optional fields.
     *
     * @param name skill name
     * @param description skill description
     */
    public SkillFrontmatter(String name, String description) {
        this(name, description, null, null, null, Map.of());
    }

    private static String optionalNonBlank(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank when present.");
        }
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
