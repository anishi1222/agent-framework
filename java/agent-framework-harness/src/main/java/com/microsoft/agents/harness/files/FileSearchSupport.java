// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness.files;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class FileSearchSupport {
    static final int MAX_PATTERN_LENGTH = 256;

    private static final int SNIPPET_RADIUS = 50;

    private FileSearchSupport() {}

    static Pattern pattern(String regexPattern) {
        if (regexPattern == null || regexPattern.isEmpty()) {
            throw new IllegalArgumentException("regexPattern must not be empty.");
        }
        if (regexPattern.length() > MAX_PATTERN_LENGTH) {
            throw new IllegalArgumentException("regexPattern must not exceed " + MAX_PATTERN_LENGTH + " characters.");
        }
        return Pattern.compile(regexPattern);
    }

    static PathMatcher glob(String globPattern) {
        if (globPattern == null || globPattern.isBlank()) {
            return ignored -> true;
        }
        return FileSystems.getDefault().getPathMatcher("glob:" + globPattern);
    }

    static boolean matchesGlob(PathMatcher matcher, String relativePath) {
        Path path = Path.of(relativePath);
        return matcher.matches(path) || matcher.matches(path.getFileName());
    }

    static FileSearchResult search(String fileName, String content, Pattern pattern) {
        return search(fileName, content, pattern, () -> {});
    }

    static FileSearchResult search(String fileName, String content, Pattern pattern, Runnable checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        String[] lines = content.split("\\R", -1);
        ArrayList<FileSearchMatch> matches = new ArrayList<>();
        String snippet = null;
        for (int index = 0; index < lines.length; index++) {
            checkpoint.run();
            Matcher matcher = pattern.matcher(lines[index]);
            if (!matcher.find()) {
                continue;
            }
            matches.add(new FileSearchMatch(index + 1, lines[index]));
            if (snippet == null) {
                int start = Math.max(0, matcher.start() - SNIPPET_RADIUS);
                int end = Math.min(lines[index].length(), matcher.end() + SNIPPET_RADIUS);
                snippet = lines[index].substring(start, end);
            }
        }
        return matches.isEmpty()
                ? null
                : new FileSearchResult(fileName, snippet == null ? "" : snippet, List.copyOf(matches));
    }
}
