// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness.files;

import java.util.Objects;

/**
 * Replaces or deletes one one-based text line.
 *
 * @param lineNumber one-based line number
 * @param newLine replacement text; an empty value deletes the line
 */
public record FileLineEdit(int lineNumber, String newLine) {
    /** Creates a validated line edit. */
    public FileLineEdit {
        if (lineNumber <= 0) {
            throw new IllegalArgumentException("lineNumber must be greater than zero.");
        }
        newLine = Objects.requireNonNull(newLine, "newLine");
    }
}
