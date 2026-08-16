// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.InvocationId;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class SkillsProviderTest {
    @Test
    void provideAsync_shouldAdvertiseEscapedSortedSkillsAndApprovalGatedTools() {
        InlineSkill zeta = InlineSkill.builder(new SkillFrontmatter("zeta", "Zeta <skill>"), "Zeta instructions.")
                .build();
        InlineSkill alpha = InlineSkill.builder(new SkillFrontmatter("alpha", "Alpha & skill"), "Alpha instructions.")
                .resource(InlineSkillResource.text("guide", null, "resource-value"))
                .script(new InlineSkillScript(
                        "echo",
                        null,
                        null,
                        (arguments, ignored) -> CompletableFuture.completedFuture(
                                arguments.values().getOrDefault("value", StateValue.string("empty")))))
                .build();
        SkillsProvider provider = new SkillsProvider(List.of(zeta, alpha, alpha));

        ContextContribution contribution = provider.provideAsync(SkillsTestContexts.request("provider"))
                .toCompletableFuture()
                .join();

        assertThat(contribution.instructions()).singleElement().satisfies(instructions -> {
            assertThat(instructions).contains("Alpha &amp; skill").contains("Zeta &lt;skill&gt;");
            assertThat(instructions.indexOf("<name>alpha</name>"))
                    .isLessThan(instructions.indexOf("<name>zeta</name>"));
        });
        assertThat(contribution.tools())
                .extracting(tool -> tool.name())
                .containsExactly(
                        SkillsProvider.LOAD_SKILL_TOOL_NAME,
                        SkillsProvider.READ_SKILL_RESOURCE_TOOL_NAME,
                        SkillsProvider.RUN_SKILL_SCRIPT_TOOL_NAME);
        assertThat(contribution.tools())
                .allSatisfy(
                        tool -> assertThat(tool.metadata().approvalMode()).isEqualTo(ToolApprovalMode.ALWAYS_REQUIRE));
    }

    @Test
    void contributedTools_shouldLoadReadRunAndReturnNotFoundErrors() {
        InlineSkill skill = InlineSkill.builder(new SkillFrontmatter("alpha", "Alpha skill."), "Alpha instructions.")
                .resource(InlineSkillResource.text("guide", null, "resource-value"))
                .script(new InlineSkillScript(
                        "echo",
                        null,
                        null,
                        (arguments, ignored) -> CompletableFuture.completedFuture(
                                arguments.values().getOrDefault("value", StateValue.string("empty")))))
                .build();
        ContextContribution contribution = new SkillsProvider(List.of(skill))
                .provideAsync(SkillsTestContexts.request("tools"))
                .toCompletableFuture()
                .join();
        Map<String, FunctionTool> tools = contribution.tools().stream()
                .map(FunctionTool.class::cast)
                .collect(java.util.stream.Collectors.toMap(FunctionTool::name, tool -> tool));
        ToolInvocationContext invocation = new ToolInvocationContext(
                "logical",
                "call",
                new InvocationId("invocation"),
                new DefaultRunCancellation(),
                Runnable::run,
                Map.of());

        StateValue loaded = tools.get(SkillsProvider.LOAD_SKILL_TOOL_NAME)
                .invokeAsync(invocation, StateValue.object(Map.of("skill_name", StateValue.string("ALPHA"))))
                .toCompletableFuture()
                .join();
        StateValue resource = tools.get(SkillsProvider.READ_SKILL_RESOURCE_TOOL_NAME)
                .invokeAsync(
                        invocation,
                        StateValue.object(Map.of(
                                "skill_name", StateValue.string("alpha"), "resource_name", StateValue.string("GUIDE"))))
                .toCompletableFuture()
                .join();
        StateValue script = tools.get(SkillsProvider.RUN_SKILL_SCRIPT_TOOL_NAME)
                .invokeAsync(
                        invocation,
                        StateValue.object(Map.of(
                                "skill_name",
                                StateValue.string("alpha"),
                                "script_name",
                                StateValue.string("echo"),
                                "args",
                                StateValue.object(Map.of("value", StateValue.string("script-value"))))))
                .toCompletableFuture()
                .join();
        StateValue missing = tools.get(SkillsProvider.LOAD_SKILL_TOOL_NAME)
                .invokeAsync(invocation, StateValue.object(Map.of("skill_name", StateValue.string("missing"))))
                .toCompletableFuture()
                .join();

        assertThat(loaded)
                .isInstanceOfSatisfying(
                        StateValue.StringValue.class,
                        value -> assertThat(value.value()).contains("Alpha instructions."));
        assertThat(resource).isEqualTo(StateValue.string("resource-value"));
        assertThat(script).isEqualTo(StateValue.string("script-value"));
        assertThat(missing).isEqualTo(StateValue.string("Error: Skill 'missing' not found."));
    }

    @Test
    void provideAsync_shouldUseCallerSourceWithoutUnsafeAutomaticCaching() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        SkillsSource source = (context, cancellation) -> CompletableFuture.completedFuture(List.of(InlineSkill.builder(
                        new SkillFrontmatter("skill-" + calls.incrementAndGet(), "Dynamic skill."), "Dynamic.")
                .build()));
        SkillsProvider provider = new SkillsProvider(source);

        ContextContribution first = provider.provideAsync(SkillsTestContexts.request("first"))
                .toCompletableFuture()
                .join();
        ContextContribution second = provider.provideAsync(SkillsTestContexts.request("second"))
                .toCompletableFuture()
                .join();

        assertThat(first.instructions().getFirst()).contains("skill-1");
        assertThat(second.instructions().getFirst()).contains("skill-2");
        assertThat(calls).hasValue(2);
    }
}
