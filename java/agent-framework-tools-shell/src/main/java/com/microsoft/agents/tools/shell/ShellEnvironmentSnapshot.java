// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Captures shell syntax, platform, working directory, and selected CLI versions.
 *
 * @param family shell command family
 * @param operatingSystem platform description
 * @param shellVersion optional shell version
 * @param workingDirectory reported working directory, possibly empty
 * @param toolVersions ordered CLI versions; null values identify unavailable tools
 */
public record ShellEnvironmentSnapshot(
        ShellFamily family,
        String operatingSystem,
        String shellVersion,
        String workingDirectory,
        Map<String, String> toolVersions) {
    /** Creates a validated detached snapshot. */
    public ShellEnvironmentSnapshot {
        family = Objects.requireNonNull(family, "family");
        operatingSystem = requireNonBlank(operatingSystem, "operatingSystem");
        if (shellVersion != null && shellVersion.isBlank()) {
            throw new IllegalArgumentException("shellVersion must not be blank when present.");
        }
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(toolVersions, "toolVersions");
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        toolVersions.forEach((name, version) -> {
            String checkedName = requireNonBlank(name, "tool name");
            if (version != null && version.isBlank()) {
                throw new IllegalArgumentException("tool versions must not be blank when present.");
            }
            copy.put(checkedName, version);
        });
        toolVersions = Collections.unmodifiableMap(copy);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
