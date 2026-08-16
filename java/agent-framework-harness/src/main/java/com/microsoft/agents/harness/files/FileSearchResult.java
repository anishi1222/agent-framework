// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness.files;

import java.util.List;
import java.util.Objects;

/**
 * Contains bounded matches from one file.
 *
 * @param fileName normalized relative file name
 * @param snippet bounded snippet around the first match
 * @param matchingLines ordered matching lines
 */
public record FileSearchResult(String fileName, String snippet, List<FileSearchMatch> matchingLines) {
    /** Creates an immutable result. */
    public FileSearchResult {
        fileName = Objects.requireNonNull(fileName, "fileName");
        snippet = Objects.requireNonNull(snippet, "snippet");
        matchingLines = List.copyOf(Objects.requireNonNull(matchingLines, "matchingLines"));
        if (matchingLines.isEmpty()) {
            throw new IllegalArgumentException("matchingLines must not be empty.");
        }
    }
}
