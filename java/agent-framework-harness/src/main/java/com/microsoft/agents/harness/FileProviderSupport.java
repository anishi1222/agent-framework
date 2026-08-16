// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.harness.files.FileLineEdit;
import com.microsoft.agents.harness.files.FileSearchResult;
import com.microsoft.agents.harness.files.FileStoreEntry;
import java.util.List;
import java.util.Map;

final class FileProviderSupport {
    private FileProviderSupport() {}

    static StateValue entries(List<FileStoreEntry> entries) {
        return StateValue.array(entries.stream()
                .map(entry -> StateValue.object(Map.of(
                        "path", StateValue.string(entry.path()), "directory", StateValue.bool(entry.directory()))))
                .toList());
    }

    static StateValue searchResults(List<FileSearchResult> results) {
        return StateValue.array(results.stream()
                .map(result -> StateValue.object(Map.of(
                        "file_name",
                        StateValue.string(result.fileName()),
                        "snippet",
                        StateValue.string(result.snippet()),
                        "matching_lines",
                        StateValue.array(result.matchingLines().stream()
                                .map(match -> StateValue.object(Map.of(
                                        "line_number",
                                        StateValue.integer(match.lineNumber()),
                                        "line",
                                        StateValue.string(match.line()))))
                                .toList()))))
                .toList());
    }

    static List<FileLineEdit> lineEdits(StateValue.ObjectValue arguments) {
        return HarnessToolSupport.array(arguments, "edits").stream()
                .map(value -> HarnessToolSupport.object(value, "line edit"))
                .map(value -> new FileLineEdit(
                        HarnessToolSupport.integer(value.require("line_number"), "line_number"),
                        requiredString(value, "new_line")))
                .toList();
    }

    static StateValue.ObjectValue writeSchema(String pathName) {
        return HarnessToolSupport.objectSchema(
                Map.of(
                        pathName,
                        HarnessToolSupport.stringProperty("Relative file path."),
                        "content",
                        HarnessToolSupport.stringProperty("UTF-8 text content."),
                        "overwrite",
                        HarnessToolSupport.booleanProperty("Whether an existing file may be replaced.")),
                List.of(pathName, "content"));
    }

    static StateValue.ObjectValue pathSchema(String pathName) {
        return HarnessToolSupport.objectSchema(
                Map.of(pathName, HarnessToolSupport.stringProperty("Relative file path.")), List.of(pathName));
    }

    static StateValue.ObjectValue listSchema() {
        return HarnessToolSupport.objectSchema(
                Map.of(
                        "directory",
                        HarnessToolSupport.stringProperty("Relative directory; omit for the root."),
                        "glob_pattern",
                        HarnessToolSupport.stringProperty("Optional file glob.")),
                List.of());
    }

    static StateValue.ObjectValue grepSchema() {
        return HarnessToolSupport.objectSchema(
                Map.of(
                        "directory",
                        HarnessToolSupport.stringProperty("Relative directory; omit for the root."),
                        "pattern",
                        HarnessToolSupport.stringProperty("Java regular expression, at most 256 characters."),
                        "glob_pattern",
                        HarnessToolSupport.stringProperty("Optional file glob."),
                        "recursive",
                        HarnessToolSupport.booleanProperty("Whether descendants are searched.")),
                List.of("pattern"));
    }

    static StateValue.ObjectValue replaceSchema(String pathName) {
        return HarnessToolSupport.objectSchema(
                Map.of(
                        pathName,
                        HarnessToolSupport.stringProperty("Relative file path."),
                        "old_text",
                        HarnessToolSupport.stringProperty("Exact text to replace."),
                        "new_text",
                        HarnessToolSupport.stringProperty("Replacement text."),
                        "replace_all",
                        HarnessToolSupport.booleanProperty("Whether every occurrence is replaced.")),
                List.of(pathName, "old_text", "new_text"));
    }

    static StateValue.ObjectValue replaceLinesSchema(String pathName) {
        StateValue.ObjectValue edit = HarnessToolSupport.objectSchema(
                Map.of(
                        "line_number",
                        HarnessToolSupport.integerProperty("One-based line number."),
                        "new_line",
                        HarnessToolSupport.stringProperty("Replacement line; empty deletes the line.")),
                List.of("line_number", "new_line"));
        return HarnessToolSupport.objectSchema(
                Map.of(
                        pathName,
                        HarnessToolSupport.stringProperty("Relative file path."),
                        "edits",
                        HarnessToolSupport.arrayProperty(edit, "Line edits.")),
                List.of(pathName, "edits"));
    }

    private static String requiredString(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw new IllegalArgumentException(name + " must be a string.");
    }
}
