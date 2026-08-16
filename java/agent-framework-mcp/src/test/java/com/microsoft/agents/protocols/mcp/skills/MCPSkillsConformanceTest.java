// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp.skills;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.AgentRunContext;
import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.agents.skills.FileSkill;
import com.microsoft.agents.agents.skills.Skill;
import com.microsoft.agents.agents.skills.SkillResourceContent;
import com.microsoft.agents.agents.skills.SkillsSourceContext;
import com.microsoft.agents.conformance.BehaviorFixture;
import com.microsoft.agents.conformance.ConformanceAssertions;
import com.microsoft.agents.conformance.ConformanceFixtureCatalog;
import com.microsoft.agents.conformance.ConformanceFixtureLoader;
import com.microsoft.agents.conformance.ConformanceValue;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.protocols.mcp.MCPProtocolException;
import com.microsoft.agents.protocols.mcp.MCPReadResourceResult;
import com.microsoft.agents.protocols.mcp.MCPResourceContents;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MCPSkillsConformanceTest {
    private final ConformanceFixtureCatalog catalog = new ConformanceFixtureLoader().loadDefault();

    @TempDir
    Path temporaryDirectory;

    @Test
    void jcfSkills003_shouldBindMcpDiscoveryResourcesAndArchiveSafety() throws IOException {
        BehaviorFixture fixture = (BehaviorFixture) catalog.requireCase("JCF-SKILLS-003");
        URI skillUri = URI.create("skill://catalog/remote/SKILL.md");
        URI guideUri = URI.create("skill://catalog/remote/references/guide.md");
        URI binaryUri = URI.create("skill://catalog/remote/assets/icon.bin");
        URI archiveUri = URI.create("skill://catalog/archive.tar");
        AtomicInteger skillDocumentReads = new AtomicInteger();
        AtomicInteger unsafeResourceReads = new AtomicInteger();
        AtomicReference<String> index = new AtomicReference<>(validIndex());
        byte[] archive = zip(Map.of(
                "SKILL.md",
                "---\nname: archive-skill\ndescription: Archived skill.\n---\nArchive.",
                "references/archive.md",
                "archive guide",
                "scripts/run.py",
                "print('never execute')"));
        MCPResourceReader reader = (uri, cancellation) -> {
            if (uri.equals(MCPSkillsSource.INDEX_URI)) {
                return CompletableFuture.completedFuture(text(uri, index.get()));
            }
            if (uri.equals(skillUri)) {
                skillDocumentReads.incrementAndGet();
                return CompletableFuture.completedFuture(text(uri, "# Remote\nRemote body."));
            }
            if (uri.equals(guideUri)) {
                return CompletableFuture.completedFuture(text(uri, "remote guide"));
            }
            if (uri.equals(binaryUri)) {
                return CompletableFuture.completedFuture(
                        binary(uri, "application/octet-stream", new byte[] {1, 2, 3, 4}));
            }
            if (uri.toString().contains("..") || uri.toString().contains("attacker")) {
                unsafeResourceReads.incrementAndGet();
            }
            if (uri.equals(archiveUri)) {
                return CompletableFuture.completedFuture(binary(uri, "application/x-tar", archive));
            }
            return CompletableFuture.failedFuture(notFound());
        };
        MCPSkillsSourceOptions options = MCPSkillsSourceOptions.builder()
                .archiveDirectory(temporaryDirectory.resolve("archives"))
                .archiveResourceExtensions(java.util.Set.of(".md", ".py"))
                .build();
        MCPSkillsSource source = new MCPSkillsSource(reader, options);
        DefaultRunCancellation cancellation = new DefaultRunCancellation();

        List<Skill> discovered = source.getSkillsAsync(context(), cancellation)
                .toCompletableFuture()
                .join();
        MCPSkill remote = (MCPSkill) discovered.stream()
                .filter(skill -> skill instanceof MCPSkill)
                .findFirst()
                .orElseThrow();
        FileSkill archived = (FileSkill) discovered.stream()
                .filter(skill -> skill instanceof FileSkill)
                .findFirst()
                .orElseThrow();
        boolean lazy = skillDocumentReads.get() == 0;
        String first = remote.contentAsync(cancellation).toCompletableFuture().join();
        String second = remote.contentAsync(cancellation).toCompletableFuture().join();
        boolean cached = first.equals(second) && skillDocumentReads.get() == 1;
        SkillResourceContent binaryContent = remote.resourceAsync("assets/icon.bin", cancellation)
                .toCompletableFuture()
                .join()
                .readAsync(cancellation)
                .toCompletableFuture()
                .join();
        boolean unknownResource = remote.resourceAsync("missing.md", cancellation)
                        .toCompletableFuture()
                        .join()
                == null;
        boolean traversalRejected = remote.resourceAsync("../escape.md", cancellation)
                                .toCompletableFuture()
                                .join()
                        == null
                && unsafeResourceReads.get() == 0;
        boolean templateDeferred = discovered.stream()
                .noneMatch(skill -> skill.frontmatter().name().equals("deferred"));
        boolean scriptNeverExecutable = archived.scriptAsync("scripts/run.py", cancellation)
                        .toCompletableFuture()
                        .join()
                == null;

        List<CompletableFuture<List<Skill>>> concurrent = new ArrayList<>();
        for (int call = 0; call < 8; call++) {
            concurrent.add(source.getSkillsAsync(context(), new DefaultRunCancellation())
                    .toCompletableFuture());
        }
        boolean concurrentSerialized =
                concurrent.stream().map(CompletableFuture::join).allMatch(skills -> skills.size() == 2);

        index.set("""
                {"schema":"1","skills":[
                  {"name":"remote","type":"skill-md","description":"Remote skill.",
                   "url":"skill://catalog/remote/SKILL.md"}
                ]}
                """);
        source.getSkillsAsync(context(), cancellation).toCompletableFuture().join();
        boolean stalePruned = !Files.exists(options.archiveDirectory().resolve("archive-skill"));

        boolean missingIndexEmpty = new MCPSkillsSource(
                        (uri, signal) -> CompletableFuture.failedFuture(notFound()),
                        MCPSkillsSourceOptions.builder()
                                .archiveDirectory(temporaryDirectory.resolve("missing"))
                                .build())
                .getSkillsAsync(context(), cancellation)
                .toCompletableFuture()
                .join()
                .isEmpty();
        boolean malformedIndexEmpty = new MCPSkillsSource(
                        (uri, signal) -> CompletableFuture.completedFuture(text(uri, "not json")),
                        MCPSkillsSourceOptions.builder()
                                .archiveDirectory(temporaryDirectory.resolve("malformed"))
                                .build())
                .getSkillsAsync(context(), cancellation)
                .toCompletableFuture()
                .join()
                .isEmpty();
        boolean realErrorsPropagate = realErrorsPropagate();
        boolean traversalContained = archiveTraversalContained();
        boolean linksSkipped = archiveLinksSkipped();
        boolean limitsEnforced = archiveLimitsEnforced();

        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        actual.put("skillDocumentLazy", bool(lazy));
        actual.put("skillDocumentCachedAfterSuccess", bool(cached));
        actual.put("missingIndexReturnsEmpty", bool(missingIndexEmpty));
        actual.put("malformedIndexReturnsEmpty", bool(malformedIndexEmpty));
        actual.put("realReadErrorsPropagate", bool(realErrorsPropagate));
        actual.put("unknownResourceReturnsNull", bool(unknownResource));
        actual.put("resourceTraversalRejectedLocally", bool(traversalRejected));
        actual.put(
                "binaryResourcesPreserved",
                bool(binaryContent instanceof SkillResourceContent.Binary value
                        && java.util.Arrays.equals(value.data(), new byte[] {1, 2, 3, 4})));
        actual.put(
                "archiveMagicBytesPreferred", bool(archived.frontmatter().name().equals("archive-skill")));
        actual.put("archiveTraversalContained", bool(traversalContained));
        actual.put("archiveLinksSkipped", bool(linksSkipped));
        actual.put("archiveLimitsEnforced", bool(limitsEnforced));
        actual.put("staleArchivesPruned", bool(stalePruned));
        actual.put("concurrentReconciliationSerialized", bool(concurrentSerialized));
        actual.put("archiveScriptsNeverExecutable", bool(scriptNeverExecutable));
        actual.put("templateEntriesDeferred", bool(templateDeferred));

        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
        source.close();
    }

    private boolean realErrorsPropagate() {
        MCPSkillsSource failed = new MCPSkillsSource(
                (uri, cancellation) -> CompletableFuture.failedFuture(
                        new MCPProtocolException(-32603, "resources/read", "internal", null)),
                MCPSkillsSourceOptions.builder()
                        .archiveDirectory(temporaryDirectory.resolve("failed"))
                        .build());
        try {
            assertThatThrownBy(() -> failed.getSkillsAsync(context(), new DefaultRunCancellation())
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(MCPProtocolException.class);
            return true;
        } finally {
            failed.close();
        }
    }

    private boolean archiveTraversalContained() throws IOException {
        Path target = temporaryDirectory.resolve("zip-slip");
        try {
            MCPArchiveExtractor.extract(
                    zip(Map.of("../escape.txt", "escape", "SKILL.md", "safe")),
                    "application/zip",
                    URI.create("skill://catalog/unsafe.zip"),
                    target,
                    MCPSkillsSourceOptions.defaults());
        } catch (IOException expected) {
            // Rejecting the archive is valid as long as no entry escapes.
        }
        return !Files.exists(temporaryDirectory.resolve("escape.txt"));
    }

    private boolean archiveLinksSkipped() throws IOException {
        Path target = temporaryDirectory.resolve("tar-links");
        MCPArchiveExtractor.extract(
                tarGzip(new TarEntry("SKILL.md", '0', "safe"), new TarEntry("outside-link", '2', "")),
                "application/gzip",
                URI.create("skill://catalog/links.tar.gz"),
                target,
                MCPSkillsSourceOptions.defaults());
        return Files.exists(target.resolve("SKILL.md")) && !Files.exists(target.resolve("outside-link"));
    }

    private boolean archiveLimitsEnforced() throws IOException {
        MCPSkillsSourceOptions limited =
                MCPSkillsSourceOptions.builder().archiveMaxFileCount(1).build();
        try {
            MCPArchiveExtractor.extract(
                    zip(Map.of("one.txt", "1", "two.txt", "2")),
                    "application/zip",
                    URI.create("skill://catalog/limited.zip"),
                    temporaryDirectory.resolve("limited"),
                    limited);
            return false;
        } catch (IOException expected) {
            return true;
        }
    }

    private static String validIndex() {
        return """
                {"schema":"1","skills":[
                  {"name":"remote","type":"skill-md","description":"Remote skill.",
                   "url":"skill://catalog/remote/SKILL.md"},
                  {"name":"archive-skill","type":"archive","description":"Archived skill.",
                   "url":"skill://catalog/archive.tar"},
                  {"name":"deferred","type":"mcp-resource-template","description":"Deferred.",
                   "url":"skill://catalog/{name}"}
                ]}
                """;
    }

    private static SkillsSourceContext context() {
        AgentSession session = new AgentSession("mcp-skills-conformance");
        AgentRunContext runContext = new AgentRunContext(
                "mcp-skills-conformance-run",
                new AgentMetadata("agent", null, null),
                Instant.EPOCH,
                List.of(),
                RunOptions.empty(),
                new DefaultRunCancellation(),
                Map.of(),
                session,
                ContextContribution.empty());
        return new SkillsSourceContext(runContext, session);
    }

    private static MCPReadResourceResult text(URI uri, String text) {
        return new MCPReadResourceResult(
                List.of(new MCPResourceContents.Text(uri, "text/plain", text, Map.of())), Map.of());
    }

    private static MCPReadResourceResult binary(URI uri, String mediaType, byte[] data) {
        return new MCPReadResourceResult(
                List.of(new MCPResourceContents.Binary(uri, mediaType, data, Map.of())), Map.of());
    }

    private static MCPProtocolException notFound() {
        return new MCPProtocolException(-32002, "resources/read", "not found", null);
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] tarGzip(TarEntry... entries) throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        for (TarEntry entry : entries) {
            byte[] content = entry.content().getBytes(StandardCharsets.UTF_8);
            byte[] header = new byte[512];
            write(header, 0, 100, entry.name());
            write(header, 100, 8, "0000644");
            write(header, 108, 8, "0000000");
            write(header, 116, 8, "0000000");
            write(header, 124, 12, String.format("%011o", content.length));
            write(header, 136, 12, "00000000000");
            java.util.Arrays.fill(header, 148, 156, (byte) ' ');
            header[156] = (byte) entry.type();
            write(header, 257, 6, "ustar");
            long checksum = 0;
            for (byte value : header) {
                checksum += Byte.toUnsignedInt(value);
            }
            write(header, 148, 8, String.format("%06o\0 ", checksum));
            tar.write(header);
            tar.write(content);
            tar.write(new byte[(512 - (content.length % 512)) % 512]);
        }
        tar.write(new byte[1024]);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(tar.toByteArray());
        }
        return compressed.toByteArray();
    }

    private static void write(byte[] target, int offset, int length, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(encoded, 0, target, offset, Math.min(encoded.length, length));
    }

    private static ConformanceValue bool(boolean value) {
        return new ConformanceValue.BooleanValue(value);
    }

    private record TarEntry(String name, char type, String content) {}
}
