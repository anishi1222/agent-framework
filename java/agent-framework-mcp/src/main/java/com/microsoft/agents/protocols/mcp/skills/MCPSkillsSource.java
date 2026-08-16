// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp.skills;

import com.microsoft.agents.agents.skills.FileSkillsSource;
import com.microsoft.agents.agents.skills.FileSkillsSourceOptions;
import com.microsoft.agents.agents.skills.Skill;
import com.microsoft.agents.agents.skills.SkillFrontmatter;
import com.microsoft.agents.agents.skills.SkillsSource;
import com.microsoft.agents.agents.skills.SkillsSourceContext;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StructuredOutputs;
import com.microsoft.agents.protocols.mcp.MCPClient;
import com.microsoft.agents.protocols.mcp.MCPReadResourceResult;
import com.microsoft.agents.protocols.mcp.MCPResourceContents;
import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Discovers Agent Skills from the SEP-2640 {@code skill://index.json} MCP resource.
 *
 * <p>{@code skill-md} entries are lazy. {@code archive} entries are reconciled into a bounded
 * source-owned directory, extracted with traversal and decompression limits, and delegated to the
 * same file-skill parser used for local skills. Unknown entry types, including the still-proposed
 * {@code mcp-resource-template}, are ignored.
 */
public final class MCPSkillsSource implements SkillsSource, AutoCloseable {
    /** Well-known MCP Agent Skills index URI. */
    public static final URI INDEX_URI = URI.create("skill://index.json");

    private final MCPResourceReader reader;
    private final MCPSkillsSourceOptions options;
    private final ReentrantLock archiveLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final boolean ownsArchiveDirectory;
    private volatile Path archiveDirectory;

    /**
     * Creates a source using an MCP client.
     *
     * @param client caller-owned MCP client
     */
    public MCPSkillsSource(MCPClient client) {
        this(client, MCPSkillsSourceOptions.defaults());
    }

    /**
     * Creates a configured source using an MCP client.
     *
     * @param client caller-owned MCP client
     * @param options source options
     */
    public MCPSkillsSource(MCPClient client, MCPSkillsSourceOptions options) {
        this(Objects.requireNonNull(client, "client")::readResourceAsync, options, options.archiveDirectory() == null);
    }

    /**
     * Creates a source using an injectable resource reader.
     *
     * @param reader resource reader resolved for every operation
     * @param options source options
     */
    public MCPSkillsSource(MCPResourceReader reader, MCPSkillsSourceOptions options) {
        this(reader, options, options.archiveDirectory() == null);
    }

    private MCPSkillsSource(MCPResourceReader reader, MCPSkillsSourceOptions options, boolean ownsArchiveDirectory) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.options = Objects.requireNonNull(options, "options");
        this.ownsArchiveDirectory = ownsArchiveDirectory;
        archiveDirectory = options.archiveDirectory();
    }

    @Override
    public CompletionStage<List<Skill>> getSkillsAsync(SkillsSourceContext context, RunCancellation cancellation) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(cancellation, "cancellation");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("MCP skills source is closed."));
        }
        CompletableFuture<List<Skill>> result = new CompletableFuture<>();
        Thread.ofVirtual().name("agent-framework-mcp-skills").start(() -> {
            boolean locked = false;
            List<Skill> skills = null;
            Throwable failure = null;
            try {
                acquireDiscoveryLock(cancellation);
                locked = true;
                if (closed.get()) {
                    throw new IllegalStateException("MCP skills source is closed.");
                }
                skills = discover(context, cancellation);
            } catch (Throwable caught) {
                failure = caught;
            } finally {
                if (locked) {
                    archiveLock.unlock();
                }
            }
            if (failure == null) {
                result.complete(skills);
            } else {
                result.completeExceptionally(failure);
            }
        });
        return result.minimalCompletionStage();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        archiveLock.lock();
        try {
            if (ownsArchiveDirectory && archiveDirectory != null) {
                try {
                    deleteTree(archiveDirectory, archiveDirectory);
                } catch (IOException ignored) {
                    // Source shutdown is best-effort because no archive content is executable.
                }
            }
        } finally {
            archiveLock.unlock();
        }
    }

    private List<Skill> discover(SkillsSourceContext context, RunCancellation cancellation) throws IOException {
        MCPReadResourceResult indexResource;
        try {
            indexResource = read(INDEX_URI, cancellation);
        } catch (RuntimeException failure) {
            if (isNotFound(failure)) {
                reconcileArchives(List.of(), context, cancellation);
                return List.of();
            }
            throw failure;
        }
        String json = indexResource.contents().stream()
                .filter(MCPResourceContents.Text.class::isInstance)
                .map(MCPResourceContents.Text.class::cast)
                .map(MCPResourceContents.Text::text)
                .collect(java.util.stream.Collectors.joining("\n"));
        if (json.isBlank()) {
            reconcileArchives(List.of(), context, cancellation);
            return List.of();
        }
        StateValue parsed;
        try {
            parsed = StructuredOutputs.parseJson(json);
        } catch (SerializationException malformed) {
            reconcileArchives(List.of(), context, cancellation);
            return List.of();
        }
        if (!(parsed instanceof StateValue.ObjectValue index)) {
            reconcileArchives(List.of(), context, cancellation);
            return List.of();
        }

        List<IndexEntry> entries = entries(index);
        ArrayList<Skill> result = new ArrayList<>();
        ArrayList<IndexEntry> archives = new ArrayList<>();
        for (IndexEntry entry : entries) {
            if ("skill-md".equalsIgnoreCase(entry.type())) {
                Skill skill = skillMd(entry);
                if (skill != null) {
                    result.add(skill);
                }
            } else if ("archive".equalsIgnoreCase(entry.type())) {
                archives.add(entry);
            }
        }
        result.addAll(reconcileArchives(archives, context, cancellation));
        return List.copyOf(result);
    }

    private Skill skillMd(IndexEntry entry) {
        if (entry.name() == null || entry.description() == null || entry.url() == null) {
            return null;
        }
        try {
            return new MCPSkill(
                    new SkillFrontmatter(entry.name(), entry.description()), URI.create(entry.url()), reader);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private List<Skill> reconcileArchives(
            List<IndexEntry> entries, SkillsSourceContext context, RunCancellation cancellation) throws IOException {
        requireActive(cancellation);
        Path root = archiveRoot();
        Set<String> advertised = entries.stream()
                .map(IndexEntry::name)
                .filter(Objects::nonNull)
                .filter(MCPSkillsSource::safeDirectoryName)
                .collect(java.util.stream.Collectors.toSet());
        prune(root, advertised);
        for (IndexEntry entry : entries) {
            requireActive(cancellation);
            if (!validArchiveEntry(entry)) {
                continue;
            }
            Path target = root.resolve(entry.name()).normalize();
            if (!target.startsWith(root)) {
                continue;
            }
            try {
                deleteTree(target, root);
                MCPReadResourceResult resource = read(URI.create(entry.url()), cancellation);
                MCPResourceContents.Binary binary = resource.contents().stream()
                        .filter(MCPResourceContents.Binary.class::isInstance)
                        .map(MCPResourceContents.Binary.class::cast)
                        .findFirst()
                        .orElse(null);
                if (binary == null) {
                    continue;
                }
                byte[] data = binary.data();
                if (data.length == 0 || data.length > options.archiveMaxSizeBytes()) {
                    continue;
                }
                MCPArchiveExtractor.extract(data, binary.mediaType(), binary.uri(), target, options);
            } catch (RuntimeException failure) {
                deleteTree(target, root);
                if (!isNotFound(failure)) {
                    throw failure;
                }
            } catch (IOException failure) {
                deleteTree(target, root);
            }
        }
        FileSkillsSourceOptions fileOptions = FileSkillsSourceOptions.builder()
                .resourceExtensions(options.archiveResourceExtensions())
                .scriptExtensions(Set.of())
                .searchDepth(options.archiveResourceSearchDepth())
                .build();
        return new FileSkillsSource(List.of(root), fileOptions)
                .getSkillsAsync(context, cancellation)
                .toCompletableFuture()
                .join();
    }

    private MCPReadResourceResult read(URI uri, RunCancellation cancellation) {
        try {
            return reader.readAsync(uri, cancellation).toCompletableFuture().join();
        } catch (CompletionException failure) {
            Throwable cause = RunHandles.unwrap(failure);
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw failure;
        }
    }

    private Path archiveRoot() throws IOException {
        Path current = archiveDirectory;
        if (current == null) {
            synchronized (this) {
                current = archiveDirectory;
                if (current == null) {
                    current = Files.createTempDirectory("agent-framework-mcp-skills-")
                            .toAbsolutePath()
                            .normalize();
                    archiveDirectory = current;
                }
            }
        }
        Files.createDirectories(current);
        return current;
    }

    private static List<IndexEntry> entries(StateValue.ObjectValue index) {
        StateValue value = index.values().get("skills");
        if (!(value instanceof StateValue.ArrayValue array)) {
            return List.of();
        }
        ArrayList<IndexEntry> result = new ArrayList<>();
        for (StateValue item : array.values()) {
            if (item instanceof StateValue.ObjectValue object) {
                result.add(new IndexEntry(
                        text(object, "name"), text(object, "type"), text(object, "description"), text(object, "url")));
            }
        }
        return List.copyOf(result);
    }

    private static String text(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        return value instanceof StateValue.StringValue string ? string.value() : null;
    }

    private static boolean validArchiveEntry(IndexEntry entry) {
        if (!safeDirectoryName(entry.name())
                || entry.url() == null
                || entry.url().isBlank()) {
            return false;
        }
        try {
            return URI.create(entry.url()).isAbsolute();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean safeDirectoryName(String name) {
        return name != null
                && !name.isBlank()
                && !name.equals(".")
                && !name.equals("..")
                && name.indexOf('/') < 0
                && name.indexOf('\\') < 0
                && name.chars().noneMatch(character -> character == 0);
    }

    private static boolean isNotFound(Throwable failure) {
        return MCPSkillErrors.isNotFound(failure);
    }

    private void acquireDiscoveryLock(RunCancellation cancellation) {
        while (true) {
            requireActive(cancellation);
            try {
                if (archiveLock.tryLock(50, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RunCancelledException("Interrupted while waiting to discover MCP skills.", exception);
            }
        }
    }

    private static void requireActive(RunCancellation cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        if (cancellation.isCancellationRequested()) {
            throw new RunCancelledException();
        }
    }

    private static void prune(Path root, Set<String> advertised) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                        && !advertised.contains(child.getFileName().toString())) {
                    deleteTree(child, root);
                }
            }
        }
    }

    private static void deleteTree(Path target, Path allowedRoot) throws IOException {
        Path normalizedRoot = allowedRoot.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot) || normalizedTarget.equals(normalizedRoot.getParent())) {
            throw new IOException("Refusing to delete outside the MCP archive directory.");
        }
        if (!Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(normalizedTarget)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record IndexEntry(String name, String type, String description, String url) {}
}
