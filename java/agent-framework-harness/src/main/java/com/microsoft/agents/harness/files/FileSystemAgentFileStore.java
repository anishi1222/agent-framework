// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness.files;

import com.google.re2j.Pattern;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Confines UTF-8 files beneath one physical root and rejects symbolic-link traversal.
 *
 * <p>Replacement writes use a sibling temporary file and an atomic move when the file system
 * supports it. Create-only writes use exclusive creation so they never replace an existing entry.
 */
public final class FileSystemAgentFileStore implements AgentFileStore {
    private static final long SEARCH_TIMEOUT_SECONDS = 10;

    private final Path root;

    private final Object rootFileKey;

    private final boolean secureDirectoryStreamsSupported;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Creates a store rooted at one caller-selected directory.
     *
     * @param rootDirectory physical root
     */
    public FileSystemAgentFileStore(Path rootDirectory) {
        this(rootDirectory, true);
    }

    FileSystemAgentFileStore(Path rootDirectory, boolean enableSecureDirectoryStreams) {
        root = Objects.requireNonNull(rootDirectory, "rootDirectory")
                .toAbsolutePath()
                .normalize();
        try {
            Files.createDirectories(root);
            ensureNotLink(root);
            BasicFileAttributes attributes =
                    Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()) {
                throw new IllegalArgumentException("rootDirectory must identify a directory.");
            }
            rootFileKey = attributes.fileKey();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                boolean secureDirectoryStream = rootFileKey != null && stream instanceof SecureDirectoryStream<?>;
                if (enableSecureDirectoryStreams && !secureDirectoryStream) {
                    throw new FileStoreException(
                            "FileSystemAgentFileStore requires SecureDirectoryStream support to prevent "
                                    + "symbolic-link races.");
                }
                secureDirectoryStreamsSupported = enableSecureDirectoryStreams;
            }
        } catch (IOException failure) {
            throw new FileStoreException("Failed to initialize the agent file-store root.", failure);
        }
    }

    @Override
    public CompletionStage<Void> writeAsync(
            String path, String content, boolean overwrite, RunCancellation cancellation) {
        String normalized = StorePaths.normalizeFilePath(path);
        String safeContent = java.util.Objects.requireNonNull(content, "content");
        return runAsync(() -> {
            requireActive(cancellation);
            if (secureDirectoryStreamsSupported) {
                writeSecure(normalized, safeContent, overwrite, cancellation);
                return null;
            }
            Path target = resolveSafe(normalized);
            Path parent = target.getParent();
            createDirectoriesSafe(parent);
            ensureNotLink(target);
            if (!overwrite && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(target.toString());
            }
            Path temporary = Files.createTempFile(parent, ".agent-framework-", ".tmp");
            try {
                Files.writeString(temporary, safeContent, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                requireActive(cancellation);
                move(temporary, target, overwrite);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return null;
        });
    }

    @Override
    public CompletionStage<String> readAsync(String path, RunCancellation cancellation) {
        String normalized = StorePaths.normalizeFilePath(path);
        return supplyAsync(() -> {
            requireActive(cancellation);
            if (secureDirectoryStreamsSupported) {
                return readSecure(normalized, cancellation);
            }
            Path target = resolveSafe(normalized);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            ensureNotLink(target);
            Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try (var channel = Files.newByteChannel(target, options)) {
                byte[] bytes = new byte[Math.toIntExact(channel.size())];
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                    requireActive(cancellation);
                }
                return StandardCharsets.UTF_8
                        .decode(java.nio.ByteBuffer.wrap(bytes))
                        .toString();
            }
        });
    }

    @Override
    public CompletionStage<Boolean> deleteAsync(String path, RunCancellation cancellation) {
        String normalized = StorePaths.normalizeFilePath(path);
        return supplyAsync(() -> {
            requireActive(cancellation);
            if (secureDirectoryStreamsSupported) {
                return deleteSecure(normalized);
            }
            Path target = resolveSafe(normalized);
            ensureNotLink(target);
            return Files.deleteIfExists(target);
        });
    }

    @Override
    public CompletionStage<List<FileStoreEntry>> listChildrenAsync(String directory, RunCancellation cancellation) {
        String normalized = StorePaths.normalizeDirectoryPath(directory);
        return supplyAsync(() -> {
            requireActive(cancellation);
            if (secureDirectoryStreamsSupported) {
                return listSecure(normalized, cancellation);
            }
            Path target = resolveSafe(normalized);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return List.of();
            }
            ensureNotLink(target);
            ArrayList<FileStoreEntry> entries = new ArrayList<>();
            try (DirectoryStream<Path> children = Files.newDirectoryStream(target)) {
                for (Path child : children) {
                    requireActive(cancellation);
                    if (Files.isSymbolicLink(child)) {
                        continue;
                    }
                    String relative = toRelative(child);
                    entries.add(new FileStoreEntry(relative, Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)));
                }
            }
            entries.sort(Comparator.comparing(FileStoreEntry::directory)
                    .reversed()
                    .thenComparing(FileStoreEntry::path, String.CASE_INSENSITIVE_ORDER));
            return List.copyOf(entries);
        });
    }

    @Override
    public CompletionStage<Boolean> fileExistsAsync(String path, RunCancellation cancellation) {
        String normalized = StorePaths.normalizeFilePath(path);
        return supplyAsync(() -> {
            requireActive(cancellation);
            if (secureDirectoryStreamsSupported) {
                return fileExistsSecure(normalized);
            }
            Path target = resolveSafe(normalized);
            ensureNotLink(target);
            return Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS);
        });
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
        return supplyAsync(() -> {
            long deadline = System.nanoTime()
                    + java.time.Duration.ofSeconds(SEARCH_TIMEOUT_SECONDS).toNanos();
            requireSearchActive(cancellation, deadline);
            if (secureDirectoryStreamsSupported) {
                return searchSecure(normalized, pattern, glob, recursive, cancellation, deadline);
            }
            Path target = resolveSafe(normalized);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return List.of();
            }
            ensureNotLink(target);
            ArrayList<FileSearchResult> results = new ArrayList<>();
            int depth = recursive ? Integer.MAX_VALUE : 1;
            try (var paths = Files.walk(target, depth)) {
                paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> !Files.isSymbolicLink(path))
                        .forEach(path -> {
                            requireSearchActive(cancellation, deadline);
                            String relative = toRelative(path);
                            if (!FileSearchSupport.matchesGlob(glob, relative)) {
                                return;
                            }
                            try {
                                FileSearchResult result = FileSearchSupport.search(
                                        relative,
                                        Files.readString(path, StandardCharsets.UTF_8),
                                        pattern,
                                        () -> requireSearchActive(cancellation, deadline));
                                if (result != null) {
                                    results.add(result);
                                }
                            } catch (IOException exception) {
                                throw new FileStoreException("Failed to search '" + relative + "'.", exception);
                            }
                        });
            }
            results.sort(Comparator.comparing(FileSearchResult::fileName, String.CASE_INSENSITIVE_ORDER));
            return List.copyOf(results);
        });
    }

    @Override
    public CompletionStage<Void> createDirectoryAsync(String path, RunCancellation cancellation) {
        String normalized = StorePaths.normalizeDirectoryPath(path);
        return runAsync(() -> {
            requireActive(cancellation);
            if (secureDirectoryStreamsSupported) {
                try (SecureDirectoryHandle handle = openSecureDirectory(pathOf(normalized), true)) {
                    if (handle.directory() == null) {
                        throw new IllegalStateException("Secure directory traversal returned no directory.");
                    }
                }
            } else {
                createDirectoriesSafe(resolveSafe(normalized));
            }
            return null;
        });
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void writeSecure(String normalized, String content, boolean overwrite, RunCancellation cancellation)
            throws IOException {
        Path relative = pathOf(normalized);
        Path leaf = relative.getFileName();
        try (SecureDirectoryHandle parent = openSecureDirectory(relative.getParent(), true)) {
            SecureDirectoryStream<Path> directory = parent.directory();
            BasicFileAttributes existing = readAttributes(directory, leaf);
            if (existing != null) {
                rejectSymbolicLink(relative, existing);
                if (!existing.isRegularFile()) {
                    throw new IllegalArgumentException("File paths must identify regular files: " + normalized);
                }
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (!overwrite) {
                if (existing != null) {
                    throw new FileAlreadyExistsException(normalized);
                }
                boolean created = false;
                try (SeekableByteChannel channel = directory.newByteChannel(
                        leaf,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                    created = true;
                    writeBytes(channel, bytes, cancellation);
                } catch (IOException | RuntimeException failure) {
                    if (created) {
                        deleteSecureIfPresent(directory, leaf);
                    }
                    throw failure;
                }
                return;
            }

            Path temporary = Path.of(".agent-framework-" + UUID.randomUUID() + ".tmp");
            try {
                try (SeekableByteChannel channel = directory.newByteChannel(
                        temporary,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                    writeBytes(channel, bytes, cancellation);
                }
                requireActive(cancellation);
                try {
                    directory.move(temporary, directory, leaf);
                } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException unsupportedReplacement) {
                    try (SeekableByteChannel channel = directory.newByteChannel(
                            leaf,
                            Set.of(
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE,
                                    StandardOpenOption.TRUNCATE_EXISTING,
                                    LinkOption.NOFOLLOW_LINKS))) {
                        writeBytes(channel, bytes, cancellation);
                    }
                }
            } finally {
                deleteSecureIfPresent(directory, temporary);
            }
        }
    }

    private String readSecure(String normalized, RunCancellation cancellation) throws IOException {
        Path relative = pathOf(normalized);
        try (SecureDirectoryHandle parent = openSecureDirectory(relative.getParent(), false)) {
            BasicFileAttributes attributes = readAttributes(parent.directory(), relative.getFileName());
            if (attributes == null) {
                return null;
            }
            rejectSymbolicLink(relative, attributes);
            if (!attributes.isRegularFile()) {
                throw new IllegalArgumentException("File paths must identify regular files: " + normalized);
            }
            try (SeekableByteChannel channel = parent.directory()
                    .newByteChannel(
                            relative.getFileName(), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                return new String(readBytes(channel, cancellation), StandardCharsets.UTF_8);
            }
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    private boolean deleteSecure(String normalized) throws IOException {
        Path relative = pathOf(normalized);
        try (SecureDirectoryHandle parent = openSecureDirectory(relative.getParent(), false)) {
            BasicFileAttributes attributes = readAttributes(parent.directory(), relative.getFileName());
            if (attributes == null) {
                return false;
            }
            rejectSymbolicLink(relative, attributes);
            if (attributes.isDirectory()) {
                throw new IllegalArgumentException("File paths must not identify directories: " + normalized);
            }
            parent.directory().deleteFile(relative.getFileName());
            return true;
        } catch (NoSuchFileException missing) {
            return false;
        }
    }

    private List<FileStoreEntry> listSecure(String normalized, RunCancellation cancellation) throws IOException {
        ArrayList<FileStoreEntry> entries = new ArrayList<>();
        try (SecureDirectoryHandle handle = openSecureDirectory(pathOf(normalized), false)) {
            for (Path child : handle.directory()) {
                requireActive(cancellation);
                Path name = child.getFileName();
                BasicFileAttributes attributes = readAttributes(handle.directory(), name);
                if (attributes == null || attributes.isSymbolicLink()) {
                    continue;
                }
                entries.add(new FileStoreEntry(joinRelative(normalized, name), attributes.isDirectory()));
            }
        } catch (NoSuchFileException missing) {
            return List.of();
        }
        entries.sort(Comparator.comparing(FileStoreEntry::directory)
                .reversed()
                .thenComparing(FileStoreEntry::path, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(entries);
    }

    private boolean fileExistsSecure(String normalized) throws IOException {
        Path relative = pathOf(normalized);
        try (SecureDirectoryHandle parent = openSecureDirectory(relative.getParent(), false)) {
            BasicFileAttributes attributes = readAttributes(parent.directory(), relative.getFileName());
            if (attributes == null) {
                return false;
            }
            rejectSymbolicLink(relative, attributes);
            return attributes.isRegularFile();
        } catch (NoSuchFileException missing) {
            return false;
        }
    }

    private List<FileSearchResult> searchSecure(
            String normalized,
            Pattern pattern,
            PathMatcher glob,
            boolean recursive,
            RunCancellation cancellation,
            long deadline)
            throws IOException {
        ArrayList<FileSearchResult> results = new ArrayList<>();
        try (SecureDirectoryHandle handle = openSecureDirectory(pathOf(normalized), false)) {
            searchSecureDirectory(
                    handle.directory(), normalized, pattern, glob, recursive, cancellation, deadline, results);
        } catch (NoSuchFileException missing) {
            return List.of();
        }
        results.sort(Comparator.comparing(FileSearchResult::fileName, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(results);
    }

    private void searchSecureDirectory(
            SecureDirectoryStream<Path> directory,
            String prefix,
            Pattern pattern,
            PathMatcher glob,
            boolean recursive,
            RunCancellation cancellation,
            long deadline,
            List<FileSearchResult> results)
            throws IOException {
        for (Path child : directory) {
            requireSearchActive(cancellation, deadline);
            Path name = child.getFileName();
            BasicFileAttributes attributes = readAttributes(directory, name);
            if (attributes == null || attributes.isSymbolicLink()) {
                continue;
            }
            String relative = joinRelative(prefix, name);
            if (attributes.isRegularFile() && FileSearchSupport.matchesGlob(glob, relative)) {
                try (SeekableByteChannel channel =
                        directory.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                    FileSearchResult result = FileSearchSupport.search(
                            relative,
                            new String(readBytes(channel, cancellation), StandardCharsets.UTF_8),
                            pattern,
                            () -> requireSearchActive(cancellation, deadline));
                    if (result != null) {
                        results.add(result);
                    }
                }
            } else if (recursive && attributes.isDirectory()) {
                try (SecureDirectoryStream<Path> childDirectory =
                        directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                    searchSecureDirectory(
                            childDirectory, relative, pattern, glob, true, cancellation, deadline, results);
                }
            }
        }
    }

    private SecureDirectoryHandle openSecureDirectory(Path relative, boolean create) throws IOException {
        SecureDirectoryStream<Path> current = openSecureRoot();
        Path absolute = root;
        try {
            if (relative != null) {
                for (Path segment : relative) {
                    Path nextAbsolute = absolute.resolve(segment);
                    SecureDirectoryStream<Path> next;
                    BasicFileAttributes existing = readAttributes(current, segment);
                    if (existing != null) {
                        rejectSymbolicLink(nextAbsolute, existing);
                        if (!existing.isDirectory()) {
                            throw new java.nio.file.NotDirectoryException(nextAbsolute.toString());
                        }
                    }
                    try {
                        next = current.newDirectoryStream(segment, LinkOption.NOFOLLOW_LINKS);
                    } catch (NoSuchFileException missing) {
                        if (!create) {
                            throw missing;
                        }
                        ensureRootIdentity();
                        try {
                            Files.createDirectory(nextAbsolute);
                        } catch (FileAlreadyExistsException racedCreation) {
                            // The descriptor-relative open below validates the raced entry.
                        }
                        next = current.newDirectoryStream(segment, LinkOption.NOFOLLOW_LINKS);
                    }
                    current.close();
                    current = next;
                    absolute = nextAbsolute;
                }
            }
            return new SecureDirectoryHandle(current);
        } catch (IOException | RuntimeException failure) {
            current.close();
            throw failure;
        }
    }

    @SuppressWarnings("unchecked")
    private SecureDirectoryStream<Path> openSecureRoot() throws IOException {
        ensureRootIdentity();
        DirectoryStream<Path> stream = Files.newDirectoryStream(root);
        if (!(stream instanceof SecureDirectoryStream<?>)) {
            stream.close();
            throw new IllegalStateException("The configured file system no longer supports secure directory streams.");
        }
        SecureDirectoryStream<Path> secure = (SecureDirectoryStream<Path>) stream;
        BasicFileAttributes attributes =
                secure.getFileAttributeView(BasicFileAttributeView.class).readAttributes();
        if (!Objects.equals(rootFileKey, attributes.fileKey())) {
            secure.close();
            throw new IllegalArgumentException("The configured file-store root was replaced.");
        }
        return secure;
    }

    private static BasicFileAttributes readAttributes(SecureDirectoryStream<Path> directory, Path path)
            throws IOException {
        try {
            return directory
                    .getFileAttributeView(path, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes();
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    private static void rejectSymbolicLink(Path path, BasicFileAttributes attributes) {
        if (attributes.isSymbolicLink()) {
            throw new IllegalArgumentException("Symbolic links are not allowed in agent file stores: " + path);
        }
    }

    private static void writeBytes(SeekableByteChannel channel, byte[] bytes, RunCancellation cancellation)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            requireActive(cancellation);
            channel.write(buffer);
        }
    }

    private static byte[] readBytes(SeekableByteChannel channel, RunCancellation cancellation) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        while (channel.read(buffer) >= 0) {
            requireActive(cancellation);
            buffer.flip();
            output.write(buffer.array(), 0, buffer.remaining());
            buffer.clear();
        }
        return output.toByteArray();
    }

    private static void deleteSecureIfPresent(SecureDirectoryStream<Path> directory, Path path) throws IOException {
        try {
            directory.deleteFile(path);
        } catch (NoSuchFileException missing) {
            // Nothing remains to clean up.
        }
    }

    private static Path pathOf(String normalized) {
        return normalized.isEmpty() ? null : Path.of(normalized);
    }

    private static String joinRelative(String prefix, Path name) {
        return prefix.isEmpty() ? name.toString() : prefix + "/" + name;
    }

    private void ensureRootIdentity() throws IOException {
        BasicFileAttributes attributes =
                Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()
                || attributes.isSymbolicLink()
                || !Objects.equals(rootFileKey, attributes.fileKey())) {
            throw new IllegalArgumentException("The configured file-store root was replaced.");
        }
    }

    private Path resolveSafe(String normalized) throws IOException {
        ensureRootIdentity();
        Path target = normalized.isEmpty() ? root : root.resolve(normalized).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("path escapes the configured store root.");
        }
        ensurePathComponentsNotLinks(target);
        return target;
    }

    private void createDirectoriesSafe(Path directory) throws IOException {
        if (directory == null) {
            return;
        }
        Path relative = root.relativize(directory);
        Path current = root;
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(root);
        }
        ensureNotLink(root);
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(current);
            }
            ensureNotLink(current);
        }
    }

    private void ensurePathComponentsNotLinks(Path target) throws IOException {
        Path current = root;
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            ensureNotLink(current);
        }
        Path relative = root.relativize(target);
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                ensureNotLink(current);
            }
        }
    }

    private static void ensureNotLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("Symbolic links are not allowed in agent file stores: " + path);
        }
    }

    private String toRelative(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static void move(Path source, Path target, boolean overwrite) throws IOException {
        if (!overwrite) {
            Files.move(source, target);
            return;
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private <T> CompletableFuture<T> supplyAsync(CheckedSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return supplier.get();
                    } catch (RuntimeException failure) {
                        throw failure;
                    } catch (Exception failure) {
                        throw new FileStoreException("Agent file-store operation failed.", failure);
                    }
                },
                executor);
    }

    private CompletionStage<Void> runAsync(CheckedSupplier<Void> supplier) {
        return supplyAsync(supplier);
    }

    private static void requireActive(RunCancellation cancellation) {
        if (java.util.Objects.requireNonNull(cancellation, "cancellation").isCancellationRequested()
                || Thread.currentThread().isInterrupted()) {
            throw new RunCancelledException();
        }
    }

    private static void requireSearchActive(RunCancellation cancellation, long deadline) {
        requireActive(cancellation);
        if (System.nanoTime() >= deadline) {
            throw new IllegalArgumentException("File search exceeded the 10 second limit.");
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private record SecureDirectoryHandle(SecureDirectoryStream<Path> directory) implements AutoCloseable {
        private SecureDirectoryHandle {
            directory = Objects.requireNonNull(directory, "directory");
        }

        @Override
        public void close() throws IOException {
            directory.close();
        }
    }
}
