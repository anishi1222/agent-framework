// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness.files;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Applies deterministic text and line replacements for file providers. */
public final class FileEditor {
    private FileEditor() {}

    /**
     * Replaces one or all exact text occurrences.
     *
     * @param content source content
     * @param oldText required source text
     * @param newText replacement text
     * @param replaceAll whether multiple occurrences are allowed
     * @return replaced content
     */
    public static String replace(String content, String oldText, String newText, boolean replaceAll) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(oldText, "oldText");
        Objects.requireNonNull(newText, "newText");
        if (oldText.isEmpty()) {
            throw new IllegalArgumentException("oldText must not be empty.");
        }
        int first = content.indexOf(oldText);
        if (first < 0) {
            throw new IllegalArgumentException("oldText was not found.");
        }
        int second = content.indexOf(oldText, first + oldText.length());
        if (!replaceAll && second >= 0) {
            throw new IllegalArgumentException(
                    "oldText occurs more than once; set replaceAll to replace every occurrence.");
        }
        return replaceAll
                ? content.replace(oldText, newText)
                : content.substring(0, first) + newText + content.substring(first + oldText.length());
    }

    /**
     * Applies unique one-based line edits.
     *
     * @param content source content
     * @param edits line edits
     * @return replaced content
     */
    public static String replaceLines(String content, List<FileLineEdit> edits) {
        Objects.requireNonNull(content, "content");
        List<FileLineEdit> safeEdits = List.copyOf(Objects.requireNonNull(edits, "edits"));
        if (safeEdits.isEmpty()) {
            throw new IllegalArgumentException("edits must not be empty.");
        }
        ArrayList<String> lines = new ArrayList<>(List.of(content.split("\\R", -1)));
        Set<Integer> seen = new HashSet<>();
        for (FileLineEdit edit : safeEdits) {
            Objects.requireNonNull(edit, "edits contains null");
            if (!seen.add(edit.lineNumber())) {
                throw new IllegalArgumentException("Duplicate line edit for line " + edit.lineNumber() + ".");
            }
            if (edit.lineNumber() > lines.size()) {
                throw new IllegalArgumentException("Line " + edit.lineNumber() + " is outside the file.");
            }
        }
        safeEdits.stream()
                .sorted(java.util.Comparator.comparingInt(FileLineEdit::lineNumber)
                        .reversed())
                .forEach(edit -> {
                    int index = edit.lineNumber() - 1;
                    if (edit.newLine().isEmpty()) {
                        lines.remove(index);
                    } else {
                        lines.set(index, edit.newLine());
                    }
                });
        return String.join("\n", lines);
    }
}
