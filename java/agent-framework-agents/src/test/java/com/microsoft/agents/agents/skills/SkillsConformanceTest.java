// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.conformance.BehaviorFixture;
import com.microsoft.agents.conformance.ConformanceAssertions;
import com.microsoft.agents.conformance.ConformanceFixtureCatalog;
import com.microsoft.agents.conformance.ConformanceFixtureLoader;
import com.microsoft.agents.conformance.ConformanceValue;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.InvocationId;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolInvocationContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillsConformanceTest {
    private final ConformanceFixtureCatalog catalog = new ConformanceFixtureLoader().loadDefault();

    @TempDir
    Path temporaryDirectory;

    @Test
    void jcfSkills001_shouldBindSourceCompositionAndProviderTools() {
        BehaviorFixture fixture = (BehaviorFixture) catalog.requireCase("JCF-SKILLS-001");
        InlineSkill alpha = InlineSkill.builder(new SkillFrontmatter("alpha", "Alpha & skill"), "Alpha instructions.")
                .resource(InlineSkillResource.text("guide", null, "resource-value"))
                .script(new InlineSkillScript(
                        "echo",
                        null,
                        null,
                        (arguments, cancellation) -> CompletableFuture.completedFuture(
                                arguments.values().get("value"))))
                .build();
        InlineSkill zeta = InlineSkill.builder(new SkillFrontmatter("zeta", "Zeta <skill>"), "Zeta instructions.")
                .build();
        SkillsProvider provider = new SkillsProvider(List.of(zeta, alpha, alpha));

        ContextContribution contribution = provider.provideAsync(
                        SkillsTestContexts.request("skills-conformance-provider"))
                .toCompletableFuture()
                .join();
        String instructions = contribution.instructions().getFirst();
        Map<String, FunctionTool> tools = contribution.tools().stream()
                .map(FunctionTool.class::cast)
                .collect(java.util.stream.Collectors.toMap(FunctionTool::name, tool -> tool));
        ToolInvocationContext invocation = new ToolInvocationContext(
                "skills-conformance",
                "call",
                new InvocationId("skills-conformance:call"),
                new DefaultRunCancellation(),
                Runnable::run,
                Map.of());
        StateValue loaded = invoke(
                tools.get(SkillsProvider.LOAD_SKILL_TOOL_NAME),
                invocation,
                Map.of("skill_name", StateValue.string("ALPHA")));
        StateValue resource = invoke(
                tools.get(SkillsProvider.READ_SKILL_RESOURCE_TOOL_NAME),
                invocation,
                Map.of("skill_name", StateValue.string("alpha"), "resource_name", StateValue.string("GUIDE")));
        StateValue script = invoke(
                tools.get(SkillsProvider.RUN_SKILL_SCRIPT_TOOL_NAME),
                invocation,
                Map.of(
                        "skill_name",
                        StateValue.string("alpha"),
                        "script_name",
                        StateValue.string("ECHO"),
                        "args",
                        StateValue.object(Map.of("value", StateValue.string("script-value")))));

        AtomicInteger sourceCalls = new AtomicInteger();
        SkillsSource callerSource =
                (context, cancellation) -> CompletableFuture.completedFuture(List.of(InlineSkill.builder(
                                new SkillFrontmatter("dynamic-" + sourceCalls.incrementAndGet(), "Dynamic skill."),
                                "Dynamic.")
                        .build()));
        SkillsProvider callerProvider = new SkillsProvider(callerSource);
        callerProvider
                .provideAsync(SkillsTestContexts.request("caller-one"))
                .toCompletableFuture()
                .join();
        callerProvider
                .provideAsync(SkillsTestContexts.request("caller-two"))
                .toCompletableFuture()
                .join();

        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        actual.put("advertisedSkillNames", strings(List.of("alpha", "zeta")));
        actual.put(
                "providerToolNames",
                strings(List.of(
                        SkillsProvider.LOAD_SKILL_TOOL_NAME,
                        SkillsProvider.READ_SKILL_RESOURCE_TOOL_NAME,
                        SkillsProvider.RUN_SKILL_SCRIPT_TOOL_NAME)));
        actual.put("duplicateNamesRemoved", bool(occurrences(instructions, "<name>alpha</name>") == 1));
        actual.put(
                "descriptionsXmlEscaped",
                bool(instructions.contains("Alpha &amp; skill") && instructions.contains("Zeta &lt;skill&gt;")));
        actual.put("loadReturnsInstructions", bool(stringValue(loaded).contains("Alpha instructions.")));
        actual.put("resourceLookupCaseInsensitive", bool(resource.equals(StateValue.string("resource-value"))));
        actual.put("scriptLookupCaseInsensitive", bool(script.equals(StateValue.string("script-value"))));
        actual.put(
                "approvalRequiredByDefault",
                bool(tools.values().stream()
                        .allMatch(tool -> tool.metadata().approvalMode() == ToolApprovalMode.ALWAYS_REQUIRE)));
        actual.put("callerSourceAutoCached", bool(sourceCalls.get() == 1));

        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
    }

    @Test
    void jcfSkills002_shouldBindInlineClassAndFilesystemSkillContracts() throws IOException {
        BehaviorFixture fixture = (BehaviorFixture) catalog.requireCase("JCF-SKILLS-002");
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        InlineSkill inline = InlineSkill.builder(
                        new SkillFrontmatter("inline-skill", "Inline skill."), "Inline instructions.")
                .build();
        String firstInline =
                inline.contentAsync(cancellation).toCompletableFuture().join();
        String secondInline =
                inline.contentAsync(cancellation).toCompletableFuture().join();

        SnapshotSkill classSkill = new SnapshotSkill();
        classSkill.contentAsync(cancellation).toCompletableFuture().join();
        classSkill.resources.add(InlineSkillResource.text("late", null, "late"));
        boolean classSnapshotImmutable = classSkill
                        .resourceAsync("late", cancellation)
                        .toCompletableFuture()
                        .join()
                == null;

        Path skillDirectory = temporaryDirectory.resolve("file-skill");
        Files.createDirectories(skillDirectory.resolve("references"));
        Files.createDirectories(skillDirectory.resolve("scripts"));
        Files.writeString(skillDirectory.resolve("SKILL.md"), """
                ---
                name: file-skill
                description: Filesystem skill.
                ---
                # Filesystem
                """);
        Files.writeString(skillDirectory.resolve("references/guide.md"), "guide-value");
        Files.writeString(skillDirectory.resolve("scripts/run.py"), "print('test')");
        Path invalidDirectory = temporaryDirectory.resolve("wrong-directory");
        Files.createDirectories(invalidDirectory);
        Files.writeString(invalidDirectory.resolve("SKILL.md"), """
                ---
                name: mismatched
                description: Invalid directory binding.
                ---
                invalid
                """);
        boolean symlinkSkipped = true;
        Path symlink = skillDirectory.resolve("references/linked.md");
        try {
            Files.createSymbolicLink(symlink, skillDirectory.resolve("references/guide.md"));
        } catch (IOException | UnsupportedOperationException unavailable) {
            symlinkSkipped = !Files.exists(symlink);
        }
        FileSkillsSourceOptions options = FileSkillsSourceOptions.builder()
                .scriptRunner((skill, script, arguments, signal) ->
                        CompletableFuture.completedFuture(StateValue.string(script.name())))
                .build();
        List<Skill> discovered = new FileSkillsSource(List.of(temporaryDirectory), options)
                .getSkillsAsync(SkillsTestContexts.context("file-conformance"), cancellation)
                .toCompletableFuture()
                .join();
        FileSkill fileSkill = (FileSkill) discovered.getFirst();
        SkillResource guide = fileSkill
                .resourceAsync("REFERENCES/GUIDE.MD", cancellation)
                .toCompletableFuture()
                .join();
        SkillScript script = fileSkill
                .scriptAsync("SCRIPTS/RUN.PY", cancellation)
                .toCompletableFuture()
                .join();
        StateValue scriptResult = script.runAsync(fileSkill, StateValue.array(List.of()), cancellation)
                .toCompletableFuture()
                .join();

        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        actual.put("inlineContentDeterministic", bool(firstInline.equals(secondInline)));
        actual.put("classSnapshotImmutable", bool(classSnapshotImmutable));
        actual.put(
                "fileSkillDiscovered",
                bool(discovered.size() == 1 && fileSkill.frontmatter().name().equals("file-skill")));
        actual.put(
                "directoryNameRequired",
                bool(discovered.stream()
                        .noneMatch(skill -> skill.frontmatter().name().equals("mismatched"))));
        actual.put(
                "resourceReadable",
                bool(guide != null
                        && guide.readAsync(cancellation)
                                .toCompletableFuture()
                                .join()
                                .equals(new SkillResourceContent.Text("guide-value"))));
        actual.put("scriptRunnable", bool(scriptResult.equals(StateValue.string("scripts/run.py"))));
        actual.put("lookupCaseInsensitive", bool(guide != null && script != null));
        actual.put(
                "traversalRejected",
                bool(fileSkill
                                .resourceAsync("../guide.md", cancellation)
                                .toCompletableFuture()
                                .join()
                        == null));
        actual.put(
                "symlinksSkipped",
                bool(symlinkSkipped
                        && fileSkill
                                        .resourceAsync("references/linked.md", cancellation)
                                        .toCompletableFuture()
                                        .join()
                                == null));

        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
    }

    private static StateValue invoke(
            FunctionTool tool, ToolInvocationContext invocation, Map<String, StateValue> arguments) {
        return tool.invokeAsync(invocation, StateValue.object(arguments))
                .toCompletableFuture()
                .join();
    }

    private static int occurrences(String value, String fragment) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(fragment, offset)) >= 0) {
            count++;
            offset += fragment.length();
        }
        return count;
    }

    private static String stringValue(StateValue value) {
        return ((StateValue.StringValue) value).value();
    }

    private static ConformanceValue bool(boolean value) {
        return new ConformanceValue.BooleanValue(value);
    }

    private static ConformanceValue.ArrayValue strings(List<String> values) {
        return new ConformanceValue.ArrayValue(values.stream()
                .map(ConformanceValue.StringValue::new)
                .map(ConformanceValue.class::cast)
                .toList());
    }

    private static final class SnapshotSkill extends ClassSkill {
        private final List<SkillResource> resources =
                new java.util.ArrayList<>(List.of(InlineSkillResource.text("initial", null, "initial")));

        private SnapshotSkill() {
            super(new SkillFrontmatter("class-skill", "Class skill."));
        }

        @Override
        protected String instructions() {
            return "Class instructions.";
        }

        @Override
        protected List<? extends SkillResource> resources() {
            return resources;
        }
    }
}
