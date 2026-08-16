// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness.files;

import java.util.Objects;

/**
 * Describes one matching text line.
 *
 * @param lineNumber one-based line number
 * @param line complete line text
 */
public record FileSearchMatch(int lineNumber, String line) {
    /** Creates a validated match. */
    public FileSearchMatch {
        if (lineNumber <= 0) {
            throw new IllegalArgumentException("lineNumber must be greater than zero.");
        }
        line = Objects.requireNonNull(line, "line");
    }
}
