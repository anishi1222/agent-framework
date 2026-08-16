// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

final class SkillValidation {
    private static final Pattern MEMBER_NAME = Pattern.compile("^[a-z0-9]([a-z0-9]*-[a-z0-9])*[a-z0-9]*$");

    private SkillValidation() {}

    static String requireMemberName(String value, String kind) {
        Objects.requireNonNull(value, kind + " name");
        if (value.isBlank()
                || value.length() > SkillFrontmatter.MAX_NAME_LENGTH
                || !MEMBER_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(kind
                    + " name must be non-blank lowercase kebab-case and "
                    + SkillFrontmatter.MAX_NAME_LENGTH
                    + " characters or fewer.");
        }
        return value;
    }

    static String requireRelativeName(String value, String kind) {
        Objects.requireNonNull(value, kind + " name");
        String normalized = value.replace('\\', '/');
        if (normalized.isBlank()
                || normalized.startsWith("/")
                || normalized.contains("://")
                || Path.of(normalized).isAbsolute()
                || java.util.Arrays.stream(normalized.split("/"))
                        .anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
            throw new IllegalArgumentException(kind + " name must be a safe relative path.");
        }
        return normalized;
    }

    static String optionalNonBlank(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank when present.");
        }
        return value;
    }

    static void requireActive(RunCancellation cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        if (cancellation.isCancellationRequested()) {
            throw new RunCancelledException();
        }
    }

    static String caseKey(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
