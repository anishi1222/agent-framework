// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness.files;

import com.google.re2j.Pattern;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Thread-safe process-local file store with case-insensitive path identity. */
public final class InMemoryAgentFileStore implements AgentFileStore {
    private final Object lock = new Object();

    private final Map<String, StoredFile> files = new LinkedHashMap<>();

    private final Map<String, String> directories = new LinkedHashMap<>();

    /** Creates an empty store containing only its logical root. */
    public InMemoryAgentFileStore() {
        directories.put("", "");
    }

    @Override
    public CompletionStage<Void> writeAsync(
            String path, String content, boolean overwrite, RunCancellation cancellation) {
        String normalized = StorePaths.normalizeFilePath(path);
        requireActive(cancellation);
        synchronized (lock) {
            String key = key(normalized);
            if (!overwrite && files.containsKey(key)) {
                return CompletableFuture.failedFuture(new FileStoreException(
                        "File '" + normalized + "' already exists.", new FileAlreadyExistsException(normalized)));
            }
            ensureDirectories(normalized);
            files.put(key, new StoredFile(normalized, java.util.Objects.requireNonNull(content, "content")));
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<String> readAsync(String path, RunCancellation cancellation) {
        String normalized = StorePaths.normalizeFilePath(path);
        requireActive(cancellation);
        synchronized (lock) {
            StoredFile file = files.get(key(normalized));
            return CompletableFuture.completedFuture(file == null ? null : file.content());
        }
    }

    @Override
    public CompletionStage<Boolean> deleteAsync(String path, RunCancellation cancellation) {
        String normalized = StorePaths.normalizeFilePath(path);
        requireActive(cancellation);
        synchronized (lock) {
            return CompletableFuture.completedFuture(files.remove(key(normalized)) != null);
        }
    }

    @Override
    public CompletionStage<List<FileStoreEntry>> listChildrenAsync(String directory, RunCancellation cancellation) {
        String normalized = StorePaths.normalizeDirectoryPath(directory);
        requireActive(cancellation);
        synchronized (lock) {
            String prefix = normalized.isEmpty() ? "" : normalized + "/";
            LinkedHashMap<String, FileStoreEntry> entries = new LinkedHashMap<>();
            directories.values().stream()
                    .filter(path -> !path.isEmpty() && directChild(path, prefix))
                    .forEach(path -> entries.put(key(path), new FileStoreEntry(path, true)));
            files.values().stream()
                    .filter(file -> directChild(file.path(), prefix))
                    .forEach(file -> entries.put(key(file.path()), new FileStoreEntry(file.path(), false)));
            return CompletableFuture.completedFuture(
                    entries.values().stream().sorted(entryComparator()).toList());
        }
    }

    @Override
    public CompletionStage<Boolean> fileExistsAsync(String path, RunCancellation cancellation) {
        String normalized = StorePaths.normalizeFilePath(path);
        requireActive(cancellation);
        synchronized (lock) {
            return CompletableFuture.completedFuture(files.containsKey(key(normalized)));
        }
    }

    @Override
    public CompletionStage<List<FileSearchResult>> searchAsync(
            String directory,
            String regexPattern,
            String globPattern,
            boolean recursive,
            RunCancellation cancellation) {
        String normalized = StorePaths.normalizeDirectoryPath(directory);
        Pattern pattern = FileSearchSupport.pattern(regexPattern);
        PathMatcher glob = FileSearchSupport.glob(globPattern);
        requireActive(cancellation);
        synchronized (lock) {
            String prefix = normalized.isEmpty() ? "" : normalized + "/";
            ArrayList<FileSearchResult> results = new ArrayList<>();
            for (StoredFile file : files.values()) {
                requireActive(cancellation);
                if (!file.path().startsWith(prefix)
                        || !recursive && !directChild(file.path(), prefix)
                        || !FileSearchSupport.matchesGlob(glob, file.path())) {
                    continue;
                }
                FileSearchResult result = FileSearchSupport.search(
                        file.path(), file.content(), pattern, () -> requireActive(cancellation));
                if (result != null) {
                    results.add(result);
                }
            }
            results.sort(Comparator.comparing(FileSearchResult::fileName, String.CASE_INSENSITIVE_ORDER));
            return CompletableFuture.completedFuture(List.copyOf(results));
        }
    }

    @Override
    public CompletionStage<Void> createDirectoryAsync(String path, RunCancellation cancellation) {
        String normalized = StorePaths.normalizeDirectoryPath(path);
        requireActive(cancellation);
        synchronized (lock) {
            if (!normalized.isEmpty()) {
                ensureDirectoryPath(normalized);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    private void ensureDirectories(String filePath) {
        int separator = filePath.lastIndexOf('/');
        if (separator >= 0) {
            ensureDirectoryPath(filePath.substring(0, separator));
        }
    }

    private void ensureDirectoryPath(String path) {
        String[] segments = path.split("/");
        StringBuilder current = new StringBuilder();
        for (String segment : segments) {
            if (!current.isEmpty()) {
                current.append('/');
            }
            current.append(segment);
            String value = current.toString();
            directories.putIfAbsent(key(value), value);
        }
    }

    private static boolean directChild(String path, String prefix) {
        if (!path.startsWith(prefix) || path.equals(prefix)) {
            return false;
        }
        return path.indexOf('/', prefix.length()) < 0;
    }

    private static Comparator<FileStoreEntry> entryComparator() {
        return Comparator.comparing(FileStoreEntry::directory)
                .reversed()
                .thenComparing(FileStoreEntry::path, String.CASE_INSENSITIVE_ORDER);
    }

    private static String key(String path) {
        return path.toLowerCase(Locale.ROOT);
    }

    private static void requireActive(RunCancellation cancellation) {
        if (java.util.Objects.requireNonNull(cancellation, "cancellation").isCancellationRequested()) {
            throw new RunCancelledException();
        }
    }

    private record StoredFile(String path, String content) {}
}
