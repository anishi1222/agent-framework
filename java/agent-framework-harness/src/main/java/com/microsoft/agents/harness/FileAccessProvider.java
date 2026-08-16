// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.agents.ContextProvider;
import com.microsoft.agents.agents.ContextProviderRequest;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.harness.files.AgentFileStore;
import com.microsoft.agents.harness.files.FileEditor;
import com.microsoft.agents.harness.files.FileStoreEntry;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.Tool;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Contributes approval-gated tools for one shared persistent file namespace. */
public final class FileAccessProvider implements ContextProvider {
    /** Default context-provider identifier. */
    public static final String DEFAULT_SOURCE_ID = "file_access";

    /** Shared-file write tool. */
    public static final String WRITE_TOOL_NAME = "file_access_write";

    /** Shared-file read tool. */
    public static final String READ_TOOL_NAME = "file_access_read";

    /** Shared-file delete tool. */
    public static final String DELETE_TOOL_NAME = "file_access_delete";

    /** Shared-file list tool. */
    public static final String LIST_TOOL_NAME = "file_access_ls";

    /** Shared-file search tool. */
    public static final String GREP_TOOL_NAME = "file_access_grep";

    /** Shared-file exact replacement tool. */
    public static final String REPLACE_TOOL_NAME = "file_access_replace";

    /** Shared-file line replacement tool. */
    public static final String REPLACE_LINES_TOOL_NAME = "file_access_replace_lines";

    /** Built-in shared-file guidance. */
    public static final String DEFAULT_INSTRUCTIONS = """
            Shared file-access tools operate on persistent data visible across sessions.
            Read only what is needed, preserve unrelated content, and request approval for shared
            reads or mutations unless the host has explicitly relaxed that policy.""";

    private final AgentFileStore store;

    private final FileAccessProviderOptions options;

    private final AsyncOperationQueue operations = new AsyncOperationQueue();

    /**
     * Creates a secure provider requiring approval for every shared operation.
     *
     * @param store caller-owned shared store
     */
    public FileAccessProvider(AgentFileStore store) {
        this(store, FileAccessProviderOptions.defaults());
    }

    /**
     * Creates a configured shared-file provider.
     *
     * @param store caller-owned shared store
     * @param options provider options
     */
    public FileAccessProvider(AgentFileStore store, FileAccessProviderOptions options) {
        this.store = Objects.requireNonNull(store, "store");
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public String id() {
        return options.sourceId();
    }

    /** Returns the caller-owned store. */
    public AgentFileStore store() {
        return store;
    }

    @Override
    public CompletionStage<ContextContribution> provideAsync(ContextProviderRequest request) {
        Objects.requireNonNull(request, "request");
        return CompletableFuture.completedFuture(
                new ContextContribution(List.of(options.instructions()), List.of(), Map.of(), createTools()));
    }

    private List<Tool> createTools() {
        ArrayList<Tool> tools = new ArrayList<>();
        ToolApprovalMode readMode = options.disableReadOnlyToolApproval()
                ? ToolApprovalMode.NEVER_REQUIRE
                : ToolApprovalMode.ALWAYS_REQUIRE;
        ToolApprovalMode writeMode =
                options.disableWriteToolApproval() ? ToolApprovalMode.NEVER_REQUIRE : ToolApprovalMode.ALWAYS_REQUIRE;
        tools.add(FunctionTool.create(
                metadata(
                        READ_TOOL_NAME,
                        "Reads one shared UTF-8 text file.",
                        readMode,
                        FileProviderSupport.pathSchema("path")),
                (context, arguments) -> operations
                        .submit(() ->
                                store.readAsync(HarnessToolSupport.string(arguments, "path"), context.cancellation()))
                        .thenApply(content -> {
                            if (content == null) {
                                throw new IllegalArgumentException("File was not found.");
                            }
                            return StateValue.string(content);
                        })));
        tools.add(FunctionTool.create(
                metadata(
                        LIST_TOOL_NAME,
                        "Lists direct shared-file children.",
                        readMode,
                        FileProviderSupport.listSchema()),
                (context, arguments) -> operations.submit(() -> list(
                        optional(arguments, "directory", ""),
                        HarnessToolSupport.optionalString(arguments, "glob_pattern"),
                        context.cancellation()))));
        tools.add(FunctionTool.create(
                metadata(
                        GREP_TOOL_NAME,
                        "Searches shared UTF-8 text files.",
                        readMode,
                        FileProviderSupport.grepSchema()),
                (context, arguments) -> operations
                        .submit(() -> store.searchAsync(
                                optional(arguments, "directory", ""),
                                HarnessToolSupport.string(arguments, "pattern"),
                                HarnessToolSupport.optionalString(arguments, "glob_pattern"),
                                HarnessToolSupport.optionalBoolean(arguments, "recursive", false),
                                context.cancellation()))
                        .thenApply(FileProviderSupport::searchResults)));
        if (!options.disableWriteTools()) {
            tools.addFirst(FunctionTool.create(
                    metadata(
                            WRITE_TOOL_NAME,
                            "Writes one shared UTF-8 text file.",
                            writeMode,
                            FileProviderSupport.writeSchema("path")),
                    (context, arguments) -> operations
                            .submit(() -> store.writeAsync(
                                    HarnessToolSupport.string(arguments, "path"),
                                    HarnessToolSupport.string(arguments, "content"),
                                    HarnessToolSupport.optionalBoolean(arguments, "overwrite", false),
                                    context.cancellation()))
                            .thenApply(ignored -> StateValue.string("written"))));
            tools.add(FunctionTool.create(
                    metadata(
                            DELETE_TOOL_NAME,
                            "Deletes one shared file.",
                            writeMode,
                            FileProviderSupport.pathSchema("path")),
                    (context, arguments) -> operations
                            .submit(() -> store.deleteAsync(
                                    HarnessToolSupport.string(arguments, "path"), context.cancellation()))
                            .thenApply(StateValue::bool)));
            tools.add(FunctionTool.create(
                    metadata(
                            REPLACE_TOOL_NAME,
                            "Replaces exact text in one shared file.",
                            writeMode,
                            FileProviderSupport.replaceSchema("path")),
                    (context, arguments) -> operations.submit(() -> replace(arguments, context.cancellation()))));
            tools.add(FunctionTool.create(
                    metadata(
                            REPLACE_LINES_TOOL_NAME,
                            "Applies one-based line edits to one shared file.",
                            writeMode,
                            FileProviderSupport.replaceLinesSchema("path")),
                    (context, arguments) -> operations.submit(() -> replaceLines(arguments, context.cancellation()))));
        }
        return List.copyOf(tools);
    }

    private CompletionStage<StateValue> list(
            String directory, String globPattern, com.microsoft.agents.core.RunCancellation cancellation) {
        return store.listChildrenAsync(directory, cancellation).thenApply(entries -> {
            if (globPattern == null || globPattern.isBlank()) {
                return FileProviderSupport.entries(entries);
            }
            var matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);
            List<FileStoreEntry> filtered = entries.stream()
                    .filter(entry ->
                            matcher.matches(java.nio.file.Path.of(entry.path()).getFileName()))
                    .toList();
            return FileProviderSupport.entries(filtered);
        });
    }

    private CompletionStage<StateValue> replace(
            StateValue.ObjectValue arguments, com.microsoft.agents.core.RunCancellation cancellation) {
        String path = HarnessToolSupport.string(arguments, "path");
        return store.readAsync(path, cancellation).thenCompose(content -> {
            if (content == null) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("File '" + path + "' was not found."));
            }
            String replacement = FileEditor.replace(
                    content,
                    HarnessToolSupport.string(arguments, "old_text"),
                    HarnessToolSupport.string(arguments, "new_text"),
                    HarnessToolSupport.optionalBoolean(arguments, "replace_all", false));
            return store.writeAsync(path, replacement, true, cancellation)
                    .thenApply(ignored -> StateValue.string("replaced"));
        });
    }

    private CompletionStage<StateValue> replaceLines(
            StateValue.ObjectValue arguments, com.microsoft.agents.core.RunCancellation cancellation) {
        String path = HarnessToolSupport.string(arguments, "path");
        return store.readAsync(path, cancellation).thenCompose(content -> {
            if (content == null) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("File '" + path + "' was not found."));
            }
            String replacement = FileEditor.replaceLines(content, FileProviderSupport.lineEdits(arguments));
            return store.writeAsync(path, replacement, true, cancellation)
                    .thenApply(ignored -> StateValue.string("replaced"));
        });
    }

    private static ToolMetadata metadata(
            String name, String description, ToolApprovalMode approvalMode, StateValue.ObjectValue input) {
        return new ToolMetadata(
                name,
                description,
                Set.of(ToolCapability.FUNCTION),
                approvalMode,
                input,
                HarnessToolSupport.OPEN_OUTPUT);
    }

    private static String optional(StateValue.ObjectValue arguments, String name, String defaultValue) {
        String value = HarnessToolSupport.optionalString(arguments, name);
        return value == null ? defaultValue : value;
    }
}
