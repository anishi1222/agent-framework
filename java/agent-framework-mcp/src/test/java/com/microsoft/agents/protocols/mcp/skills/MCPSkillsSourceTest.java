// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.AgentRunContext;
import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.agents.skills.FileSkill;
import com.microsoft.agents.agents.skills.Skill;
import com.microsoft.agents.agents.skills.SkillResourceContent;
import com.microsoft.agents.agents.skills.SkillsSourceContext;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.protocols.mcp.MCPProtocolException;
import com.microsoft.agents.protocols.mcp.MCPReadResourceResult;
import com.microsoft.agents.protocols.mcp.MCPResourceContents;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MCPSkillsSourceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void skillMdEntry_shouldDiscoverLazilyCacheContentAndResolveResources() {
        URI skillUri = URI.create("skill://catalog/writing/SKILL.md");
        URI resourceUri = URI.create("skill://catalog/writing/guide.md");
        AtomicInteger skillReads = new AtomicInteger();
        LinkedHashMap<URI, MCPReadResourceResult> resources = new LinkedHashMap<>();
        resources.put(MCPSkillsSource.INDEX_URI, text(MCPSkillsSource.INDEX_URI, """
                        {"schema":"1","skills":[
                          {"name":"writing","type":"skill-md","description":"Write well.",
                           "url":"skill://catalog/writing/SKILL.md"},
                          {"name":"template","type":"mcp-resource-template",
                           "description":"Deferred.","url":"skill://catalog/{name}"}
                        ]}
                        """));
        resources.put(skillUri, text(skillUri, "# Writing\nUse concise prose."));
        resources.put(resourceUri, text(resourceUri, "Guide"));
        MCPResourceReader reader = (uri, cancellation) -> {
            if (uri.equals(skillUri)) {
                skillReads.incrementAndGet();
            }
            MCPReadResourceResult value = resources.get(uri);
            return value == null
                    ? CompletableFuture.failedFuture(notFound())
                    : CompletableFuture.completedFuture(value);
        };
        MCPSkillsSource source = new MCPSkillsSource(reader, MCPSkillsSourceOptions.defaults());

        List<Skill> skills = source.getSkillsAsync(context(), new DefaultRunCancellation())
                .toCompletableFuture()
                .join();
        MCPSkill skill = (MCPSkill) skills.getFirst();

        assertThat(skills).hasSize(1);
        assertThat(skillReads).hasValue(0);
        assertThat(skill.contentAsync(new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isEqualTo("# Writing\nUse concise prose.");
        assertThat(skill.contentAsync(new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isEqualTo("# Writing\nUse concise prose.");
        assertThat(skillReads).hasValue(1);
        assertThat(skill.resourceAsync("guide.md", new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join()
                        .readAsync(new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isEqualTo(new SkillResourceContent.Text("Guide"));
        assertThat(skill.resourceAsync("../secret", new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isNull();
        assertThat(skill.resourceAsync("missing.md", new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isNull();
        source.close();
    }

    @Test
    void archiveEntry_shouldExtractReuseFileSkillsAndNeverExposeScriptsAsExecutable() throws IOException {
        URI archiveUri = URI.create("skill://catalog/archive-skill.zip");
        byte[] archive = zip(Map.of("SKILL.md", """
                ---
                name: archive-skill
                description: Archived skill.
                ---
                # Archive
                """, "guide.md", "guide", "scripts/run.py", "print('unsafe')"));
        AtomicInteger indexVersion = new AtomicInteger();
        MCPResourceReader reader = (uri, cancellation) -> {
            if (uri.equals(MCPSkillsSource.INDEX_URI)) {
                String skills = indexVersion.get() == 0 ? """
                          [{"name":"archive-skill","type":"archive","description":"Archived.",
                            "url":"skill://catalog/archive-skill.zip"}]
                          """ : "[]";
                return CompletableFuture.completedFuture(text(uri, "{\"schema\":\"1\",\"skills\":" + skills + "}"));
            }
            if (uri.equals(archiveUri)) {
                return CompletableFuture.completedFuture(binary(uri, "application/zip", archive));
            }
            return CompletableFuture.failedFuture(notFound());
        };
        MCPSkillsSourceOptions options = MCPSkillsSourceOptions.builder()
                .archiveDirectory(temporaryDirectory.resolve("archives"))
                .archiveResourceExtensions(Set.of(".md", ".py"))
                .build();
        MCPSkillsSource source = new MCPSkillsSource(reader, options);

        List<Skill> skills = source.getSkillsAsync(context(), new DefaultRunCancellation())
                .toCompletableFuture()
                .join();
        FileSkill skill = (FileSkill) skills.getFirst();

        assertThat(skill.resourceAsync("guide.md", new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isNotNull();
        assertThat(skill.resourceAsync("scripts/run.py", new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isNotNull();
        assertThat(skill.scriptAsync("scripts/run.py", new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isNull();
        assertThat(Files.isDirectory(options.archiveDirectory().resolve("archive-skill")))
                .isTrue();

        indexVersion.incrementAndGet();
        assertThat(source.getSkillsAsync(context(), new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isEmpty();
        assertThat(Files.exists(options.archiveDirectory().resolve("archive-skill")))
                .isFalse();
        source.close();
    }

    @Test
    void indexFailure_shouldIgnoreNotFoundButPropagateOtherProtocolErrors() {
        MCPSkillsSource absent = new MCPSkillsSource(
                (uri, cancellation) -> CompletableFuture.failedFuture(notFound()),
                MCPSkillsSourceOptions.builder()
                        .archiveDirectory(temporaryDirectory.resolve("absent"))
                        .build());
        MCPSkillsSource failed = new MCPSkillsSource(
                (uri, cancellation) -> CompletableFuture.failedFuture(
                        new MCPProtocolException(-32603, "resources/read", "internal", null)),
                MCPSkillsSourceOptions.builder()
                        .archiveDirectory(temporaryDirectory.resolve("failed"))
                        .build());

        assertThat(absent.getSkillsAsync(context(), new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isEmpty();
        assertThatThrownBy(() -> failed.getSkillsAsync(context(), new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .hasRootCauseInstanceOf(MCPProtocolException.class);
        absent.close();
        failed.close();
    }

    @Test
    void malformedOrNonObjectIndex_shouldReturnEmptySkills() {
        MCPSkillsSource malformed = sourceForIndex("not json", "malformed");
        MCPSkillsSource array = sourceForIndex("[]", "array");

        assertThat(malformed
                        .getSkillsAsync(context(), new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isEmpty();
        assertThat(array.getSkillsAsync(context(), new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isEmpty();
        malformed.close();
        array.close();
    }

    @Test
    void skillContentFailure_shouldNotPoisonSuccessfulCache() {
        AtomicInteger reads = new AtomicInteger();
        URI skillUri = URI.create("skill://catalog/retry/SKILL.md");
        MCPSkill skill = new MCPSkill(
                new com.microsoft.agents.agents.skills.SkillFrontmatter("retry", "Retry skill."),
                skillUri,
                (uri, cancellation) -> reads.incrementAndGet() == 1
                        ? CompletableFuture.failedFuture(new RunCancelledException())
                        : CompletableFuture.completedFuture(text(uri, "loaded")));

        assertThatThrownBy(() -> skill.contentAsync(new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .hasRootCauseInstanceOf(RunCancelledException.class);
        assertThat(skill.contentAsync(new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isEqualTo("loaded");
        assertThat(skill.contentAsync(new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isEqualTo("loaded");
        assertThat(reads).hasValue(2);
    }

    @Test
    void queuedDiscovery_shouldObserveItsOwnCancellation() throws Exception {
        CompletableFuture<MCPReadResourceResult> blockedIndex = new CompletableFuture<>();
        CountDownLatch indexStarted = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        MCPSkillsSource source = new MCPSkillsSource(
                (uri, cancellation) -> {
                    if (reads.incrementAndGet() == 1) {
                        indexStarted.countDown();
                        return blockedIndex;
                    }
                    return CompletableFuture.completedFuture(text(uri, "{\"skills\":[]}"));
                },
                MCPSkillsSourceOptions.builder()
                        .archiveDirectory(temporaryDirectory.resolve("queued"))
                        .build());
        CompletableFuture<List<Skill>> first =
                source.getSkillsAsync(context(), new DefaultRunCancellation()).toCompletableFuture();
        org.assertj.core.api.Assertions.assertThat(indexStarted.await(2, TimeUnit.SECONDS))
                .isTrue();
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        CompletableFuture<List<Skill>> queued =
                source.getSkillsAsync(context(), cancellation).toCompletableFuture();

        cancellation.cancel();
        assertThatThrownBy(() -> queued.get(2, TimeUnit.SECONDS)).hasRootCauseInstanceOf(RunCancelledException.class);
        blockedIndex.complete(text(MCPSkillsSource.INDEX_URI, "{\"skills\":[]}"));
        assertThat(first.join()).isEmpty();
        source.close();
    }

    @Test
    void archiveEntry_shouldSkipTraversalAndConfiguredLimitViolations() throws IOException {
        byte[] traversal = zip(Map.of(
                "SKILL.md", "---\nname: archive-skill\ndescription: Archived.\n---\n", "../escape.txt", "escape"));
        MCPResourceReader reader = archiveReader(traversal);
        MCPSkillsSource source = new MCPSkillsSource(
                reader,
                MCPSkillsSourceOptions.builder()
                        .archiveDirectory(temporaryDirectory.resolve("limited"))
                        .archiveMaxFileCount(1)
                        .build());

        assertThat(source.getSkillsAsync(context(), new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isEmpty();
        assertThat(Files.exists(temporaryDirectory.resolve("escape.txt"))).isFalse();
        source.close();
    }

    private MCPSkillsSource sourceForIndex(String index, String directoryName) {
        return new MCPSkillsSource(
                (uri, cancellation) -> CompletableFuture.completedFuture(text(uri, index)),
                MCPSkillsSourceOptions.builder()
                        .archiveDirectory(temporaryDirectory.resolve(directoryName))
                        .build());
    }

    private static MCPResourceReader archiveReader(byte[] archive) {
        URI archiveUri = URI.create("skill://catalog/archive-skill.zip");
        return (uri, cancellation) -> {
            if (uri.equals(MCPSkillsSource.INDEX_URI)) {
                return CompletableFuture.completedFuture(text(uri, """
                        {"schema":"1","skills":[
                          {"name":"archive-skill","type":"archive","description":"Archived.",
                           "url":"skill://catalog/archive-skill.zip"}
                        ]}
                        """));
            }
            return uri.equals(archiveUri)
                    ? CompletableFuture.completedFuture(binary(uri, "application/zip", archive))
                    : CompletableFuture.failedFuture(notFound());
        };
    }

    private static SkillsSourceContext context() {
        AgentSession session = new AgentSession("mcp-skills");
        AgentRunContext runContext = new AgentRunContext(
                "mcp-skills-run",
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
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
