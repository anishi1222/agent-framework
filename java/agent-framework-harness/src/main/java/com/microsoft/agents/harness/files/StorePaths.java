// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness.files;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Normalizes confined relative paths shared by all harness file stores. */
public final class StorePaths {
    private StorePaths() {}

    /**
     * Normalizes a required relative file path.
     *
     * @param path input path
     * @return normalized slash-separated path
     */
    public static String normalizeFilePath(String path) {
        return normalize(path, false, false);
    }

    /**
     * Normalizes a relative directory path.
     *
     * @param path input path; an empty value addresses the store root
     * @return normalized slash-separated path
     */
    public static String normalizeDirectoryPath(String path) {
        return normalize(path, true, true);
    }

    private static String normalize(String path, boolean allowEmpty, boolean directory) {
        String value = Objects.requireNonNull(path, "path").replace('\\', '/');
        if (value.isEmpty()) {
            if (allowEmpty) {
                return "";
            }
            throw new IllegalArgumentException("path must not be empty.");
        }
        if (value.indexOf('\0') >= 0
                || value.startsWith("/")
                || hasWindowsDrivePrefix(value)
                || value.endsWith("/") && !directory) {
            throw new IllegalArgumentException("path must be a confined relative path.");
        }
        String[] rawSegments = value.split("/", -1);
        ArrayList<String> segments = new ArrayList<>(rawSegments.length);
        for (String segment : rawSegments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("path contains an invalid segment.");
            }
            segments.add(segment);
        }
        return String.join("/", List.copyOf(segments));
    }

    private static boolean hasWindowsDrivePrefix(String value) {
        return value.length() >= 2 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':';
    }
}
