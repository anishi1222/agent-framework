// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness.files;

import com.microsoft.agents.core.RunCancellation;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Provides asynchronous access to a confined agent file namespace.
 *
 * <p>All paths are relative slash-separated paths. Implementations must reject rooted paths,
 * traversal segments, and symbolic-link escapes.
 */
public interface AgentFileStore extends AutoCloseable {
    /**
     * Writes one UTF-8 text file.
     *
     * @param path relative file path
     * @param content text content
     * @param overwrite whether an existing file may be replaced
     * @param cancellation caller-owned cancellation
     * @return completion stage
     */
    CompletionStage<Void> writeAsync(String path, String content, boolean overwrite, RunCancellation cancellation);

    /**
     * Writes one UTF-8 text file, replacing an existing file.
     *
     * @param path relative file path
     * @param content text content
     * @param cancellation caller-owned cancellation
     * @return completion stage
     */
    default CompletionStage<Void> writeAsync(String path, String content, RunCancellation cancellation) {
        return writeAsync(path, content, true, cancellation);
    }

    /**
     * Reads one UTF-8 text file.
     *
     * @param path relative file path
     * @param cancellation caller-owned cancellation
     * @return file content, or {@code null} when absent
     */
    CompletionStage<String> readAsync(String path, RunCancellation cancellation);

    /**
     * Deletes one file.
     *
     * @param path relative file path
     * @param cancellation caller-owned cancellation
     * @return whether a file was removed
     */
    CompletionStage<Boolean> deleteAsync(String path, RunCancellation cancellation);

    /**
     * Lists direct children with directories before files.
     *
     * @param directory relative directory, or an empty string for the root
     * @param cancellation caller-owned cancellation
     * @return immutable child list
     */
    CompletionStage<List<FileStoreEntry>> listChildrenAsync(String directory, RunCancellation cancellation);

    /**
     * Tests whether a file exists.
     *
     * @param path relative file path
     * @param cancellation caller-owned cancellation
     * @return whether the file exists
     */
    CompletionStage<Boolean> fileExistsAsync(String path, RunCancellation cancellation);

    /**
     * Searches text files using a bounded RE2-compatible regular expression.
     *
     * @param directory relative directory, or an empty string for the root
     * @param regexPattern RE2-compatible regular expression, at most 256 characters
     * @param globPattern optional file glob
     * @param recursive whether descendants are included
     * @param cancellation caller-owned cancellation
     * @return immutable ordered search results
     */
    CompletionStage<List<FileSearchResult>> searchAsync(
            String directory, String regexPattern, String globPattern, boolean recursive, RunCancellation cancellation);

    /**
     * Creates a relative directory.
     *
     * @param path relative directory
     * @param cancellation caller-owned cancellation
     * @return completion stage
     */
    CompletionStage<Void> createDirectoryAsync(String path, RunCancellation cancellation);

    /** Releases implementation-owned resources. */
    @Override
    default void close() {}
}
