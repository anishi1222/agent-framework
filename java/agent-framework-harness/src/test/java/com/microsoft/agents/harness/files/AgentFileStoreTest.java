// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.microsoft.agents.core.DefaultRunCancellation;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentFileStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storePaths_shouldRejectRootedAndTraversalPaths() {
        assertThat(StorePaths.normalizeFilePath("docs/readme.md")).isEqualTo("docs/readme.md");
        assertThat(StorePaths.normalizeDirectoryPath("")).isEmpty();

        assertThatThrownBy(() -> StorePaths.normalizeFilePath("../secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorePaths.normalizeFilePath("./secret")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorePaths.normalizeFilePath("/secret")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorePaths.normalizeFilePath("C:\\secret"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inMemoryStore_shouldUseCaseInsensitiveKeysAndListDirectoriesBeforeFiles() {
        InMemoryAgentFileStore store = new InMemoryAgentFileStore();
        DefaultRunCancellation cancellation = new DefaultRunCancellation();

        store.writeAsync("Docs/Guide.md", "hello\nworld", cancellation)
                .toCompletableFuture()
                .join();
        store.writeAsync("root.txt", "root", cancellation).toCompletableFuture().join();
        assertThatThrownBy(() -> store.writeAsync("docs/guide.md", "unexpected", false, cancellation)
                        .toCompletableFuture()
                        .join())
                .hasRootCauseInstanceOf(java.nio.file.FileAlreadyExistsException.class);

        assertThat(store.readAsync("docs/GUIDE.md", cancellation)
                        .toCompletableFuture()
                        .join())
                .isEqualTo("hello\nworld");
        assertThat(store.listChildrenAsync("", cancellation)
                        .toCompletableFuture()
                        .join())
                .extracting(FileStoreEntry::path)
                .containsExactly("Docs", "root.txt");
        assertThat(store.searchAsync("", "world", "*.md", true, cancellation)
                        .toCompletableFuture()
                        .join())
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.fileName()).isEqualTo("Docs/Guide.md");
                    assertThat(result.matchingLines())
                            .extracting(FileSearchMatch::lineNumber)
                            .containsExactly(2);
                });
    }

    @Test
    void fileSystemStore_shouldRoundTripAtomicallyAndRejectSymlinkTraversal() throws IOException {
        try (FileSystemAgentFileStore store = new FileSystemAgentFileStore(temporaryDirectory.resolve("store"))) {
            DefaultRunCancellation cancellation = new DefaultRunCancellation();
            store.writeAsync("notes/today.md", "first\nsecond", cancellation)
                    .toCompletableFuture()
                    .join();

            assertThat(store.readAsync("notes/today.md", cancellation)
                            .toCompletableFuture()
                            .join())
                    .isEqualTo("first\nsecond");
            assertThatThrownBy(() -> store.writeAsync("notes/today.md", "unexpected", false, cancellation)
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(java.nio.file.FileAlreadyExistsException.class);
            assertThat(store.readAsync("notes/today.md", cancellation)
                            .toCompletableFuture()
                            .join())
                    .isEqualTo("first\nsecond");
            store.writeAsync("notes/today.md", "replaced", true, cancellation)
                    .toCompletableFuture()
                    .join();
            assertThat(store.readAsync("notes/today.md", cancellation)
                            .toCompletableFuture()
                            .join())
                    .isEqualTo("replaced");
            assertThat(store.searchAsync("notes", "second", "*.md", false, cancellation)
                            .toCompletableFuture()
                            .join())
                    .isEmpty();

            Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
            Files.writeString(outside.resolve("secret.txt"), "outside");
            boolean symlinkCreated;
            try {
                Files.createSymbolicLink(temporaryDirectory.resolve("store").resolve("linked"), outside);
                Files.createSymbolicLink(
                        temporaryDirectory.resolve("store").resolve("notes").resolve("alias.md"),
                        outside.resolve("secret.txt"));
                symlinkCreated = true;
            } catch (UnsupportedOperationException | IOException exception) {
                symlinkCreated = false;
            }
            if (symlinkCreated) {
                assertThatThrownBy(() -> store.writeAsync("linked/secret.txt", "secret", cancellation)
                                .toCompletableFuture()
                                .join())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> store.readAsync("linked/secret.txt", cancellation)
                                .toCompletableFuture()
                                .join())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> store.listChildrenAsync("linked", cancellation)
                                .toCompletableFuture()
                                .join())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> store.searchAsync("linked", "outside", "*.txt", false, cancellation)
                                .toCompletableFuture()
                                .join())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> store.deleteAsync("linked/secret.txt", cancellation)
                                .toCompletableFuture()
                                .join())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> store.readAsync("notes/alias.md", cancellation)
                                .toCompletableFuture()
                                .join())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> store.writeAsync("notes/alias.md", "escaped", true, cancellation)
                                .toCompletableFuture()
                                .join())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> store.fileExistsAsync("notes/alias.md", cancellation)
                                .toCompletableFuture()
                                .join())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> store.deleteAsync("notes/alias.md", cancellation)
                                .toCompletableFuture()
                                .join())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class);
                assertThat(Files.readString(outside.resolve("secret.txt"))).isEqualTo("outside");
            }
        }
    }

    @Test
    void fileSystemFallback_shouldEnforceConfinementAndOverwriteSemantics() throws IOException {
        Path root = temporaryDirectory.resolve("fallback-store");
        try (FileSystemAgentFileStore store = new FileSystemAgentFileStore(root, false)) {
            DefaultRunCancellation cancellation = new DefaultRunCancellation();
            store.createDirectoryAsync("notes/archive", cancellation)
                    .toCompletableFuture()
                    .join();
            store.writeAsync("notes/today.md", "first", false, cancellation)
                    .toCompletableFuture()
                    .join();

            assertThatThrownBy(() -> store.writeAsync("notes/today.md", "unexpected", false, cancellation)
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(java.nio.file.FileAlreadyExistsException.class);
            store.writeAsync("notes/today.md", "replaced", true, cancellation)
                    .toCompletableFuture()
                    .join();
            assertThat(store.readAsync("notes/today.md", cancellation)
                            .toCompletableFuture()
                            .join())
                    .isEqualTo("replaced");

            Path outside = Files.createDirectories(temporaryDirectory.resolve("fallback-outside"));
            Files.writeString(outside.resolve("secret.txt"), "outside");
            try {
                Files.createSymbolicLink(root.resolve("linked"), outside);
            } catch (UnsupportedOperationException | IOException exception) {
                return;
            }
            assertThatThrownBy(() -> store.writeAsync("linked/secret.txt", "escaped", cancellation)
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.readAsync("linked/secret.txt", cancellation)
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThat(Files.readString(outside.resolve("secret.txt"))).isEqualTo("outside");
        }
    }

    @Test
    void fileSystemStore_shouldFailClosedWithoutSecureDirectoryStreams() throws IOException {
        // Arrange
        URI archive =
                URI.create("jar:" + temporaryDirectory.resolve("store.zip").toUri());

        // Act / Assert
        try (FileSystem fileSystem = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
            assertThatThrownBy(() -> new FileSystemAgentFileStore(fileSystem.getPath("/store")))
                    .isInstanceOf(FileStoreException.class)
                    .hasMessageContaining("SecureDirectoryStream");
        }
    }

    @Test
    void fileSystemStore_shouldRejectRootReplacement() throws IOException {
        Path root = temporaryDirectory.resolve("store");
        try (FileSystemAgentFileStore store = new FileSystemAgentFileStore(root)) {
            DefaultRunCancellation cancellation = new DefaultRunCancellation();
            store.writeAsync("before.txt", "before", cancellation)
                    .toCompletableFuture()
                    .join();

            Path originalRoot = temporaryDirectory.resolve("original-store");
            Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
            Files.move(root, originalRoot);
            try {
                Files.createSymbolicLink(root, outside);
            } catch (UnsupportedOperationException | IOException exception) {
                return;
            }

            assertThatThrownBy(() -> store.writeAsync("escaped.txt", "escaped", cancellation)
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThat(outside.resolve("escaped.txt")).doesNotExist();
        }
    }

    @Test
    void fileEditor_shouldEnforceUniqueReplacementsAndOneBasedLineEdits() {
        assertThat(FileEditor.replace("one two one", "one", "1", true)).isEqualTo("1 two 1");
        assertThatThrownBy(() -> FileEditor.replace("one two one", "one", "1", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(FileEditor.replaceLines(
                        "alpha\nbeta\ngamma", List.of(new FileLineEdit(2, "BETA"), new FileLineEdit(3, ""))))
                .isEqualTo("alpha\nBETA");
        assertThatThrownBy(() ->
                        FileEditor.replaceLines("alpha", List.of(new FileLineEdit(1, "A"), new FileLineEdit(1, "B"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void search_shouldUseLinearTimeRegexEvaluation() {
        InMemoryAgentFileStore store = new InMemoryAgentFileStore();
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        store.writeAsync("input.txt", "a".repeat(10_000) + "!", cancellation)
                .toCompletableFuture()
                .join();

        assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> assertThat(store.searchAsync("", "(a+)+$", "*.txt", false, cancellation)
                                .toCompletableFuture()
                                .join())
                        .isEmpty());
    }
}
