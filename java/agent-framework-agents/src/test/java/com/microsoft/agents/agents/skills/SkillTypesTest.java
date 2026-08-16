// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.StateValue;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SkillTypesTest {
    @Test
    void skillFrontmatter_shouldValidateSpecificationFields() {
        assertThat(new SkillFrontmatter("code-review", "Reviews code.").name()).isEqualTo("code-review");
        assertThatThrownBy(() -> new SkillFrontmatter("Code Review", "Reviews code."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid skill name");
        assertThatThrownBy(() -> new SkillFrontmatter("code-review", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    @Test
    void inlineSkill_shouldRenderEscapeResolveAndCacheContent() {
        AtomicInteger resourceReads = new AtomicInteger();
        InlineSkillResource resource = new InlineSkillResource("style-guide", "Read <style>", ignored -> {
            resourceReads.incrementAndGet();
            return CompletableFuture.completedFuture(new SkillResourceContent.Text("concise"));
        });
        InlineSkillScript script = new InlineSkillScript(
                "format-text",
                "Format text",
                StateValue.object(Map.of(
                        "type",
                        StateValue.string("object"),
                        "properties",
                        StateValue.object(
                                Map.of("text", StateValue.object(Map.of("type", StateValue.string("string"))))))),
                (arguments, ignored) -> CompletableFuture.completedFuture(arguments.require("text")));
        InlineSkill skill = InlineSkill.builder(
                        new SkillFrontmatter("writing", "Write <well> & safely."), "Use the guide.")
                .resource(resource)
                .script(script)
                .build();
        DefaultRunCancellation cancellation = new DefaultRunCancellation();

        String first = skill.contentAsync(cancellation).toCompletableFuture().join();
        String second = skill.contentAsync(cancellation).toCompletableFuture().join();
        SkillResource resolved = skill.resourceAsync("STYLE-GUIDE", cancellation)
                .toCompletableFuture()
                .join();
        StateValue scriptResult = skill.scriptAsync("FORMAT-TEXT", cancellation)
                .toCompletableFuture()
                .join()
                .runAsync(skill, StateValue.object(Map.of("text", StateValue.string("done"))), cancellation)
                .toCompletableFuture()
                .join();

        assertThat(first)
                .isSameAs(second)
                .contains("<name>writing</name>")
                .contains("Write &lt;well&gt; &amp; safely.")
                .contains("name=\"style-guide\"")
                .contains("description=\"Read &lt;style&gt;\"")
                .contains("<parameters_schema>");
        assertThat(resolved.readAsync(cancellation).toCompletableFuture().join())
                .isEqualTo(new SkillResourceContent.Text("concise"));
        assertThat(resourceReads).hasValue(1);
        assertThat(scriptResult).isEqualTo(StateValue.string("done"));
    }

    @Test
    void inlineScript_shouldRejectArrayUnlessParserConvertsIt() {
        InlineSkillScript strict = new InlineSkillScript(
                "strict",
                null,
                null,
                (arguments, ignored) -> CompletableFuture.completedFuture(StateValue.string("ok")));
        InlineSkillScript parsed = new InlineSkillScript(
                "parsed",
                null,
                null,
                arguments -> StateValue.object(Map.of(
                        "count",
                        StateValue.integer(
                                ((StateValue.ArrayValue) arguments).values().size()))),
                (arguments, ignored) -> CompletableFuture.completedFuture(arguments.require("count")));
        Skill owner = InlineSkill.builder(new SkillFrontmatter("owner", "Owns scripts."), "Run scripts.")
                .build();
        StateValue.ArrayValue values = StateValue.array(List.of(StateValue.string("one"), StateValue.string("two")));

        assertThatThrownBy(() -> strict.runAsync(owner, values, new DefaultRunCancellation()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires object arguments");
        assertThat(parsed.runAsync(owner, values, new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isEqualTo(StateValue.integer(2));
    }

    @Test
    void classSkill_shouldSnapshotOverriddenMembersOnce() {
        AtomicInteger calls = new AtomicInteger();
        ClassSkill skill = new ClassSkill(new SkillFrontmatter("class-skill", "Class skill.")) {
            @Override
            protected String instructions() {
                calls.incrementAndGet();
                return "Class instructions.";
            }

            @Override
            protected List<? extends SkillResource> resources() {
                return List.of(InlineSkillResource.text("reference", null, "value"));
            }
        };

        String first = skill.contentAsync(new DefaultRunCancellation())
                .toCompletableFuture()
                .join();
        String second = skill.contentAsync(new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        assertThat(first).isSameAs(second).contains("Class instructions.");
        assertThat(calls).hasValue(1);
    }
}
