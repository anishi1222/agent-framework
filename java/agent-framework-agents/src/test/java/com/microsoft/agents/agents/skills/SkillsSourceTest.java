// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.AgentRunContext;
import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunOptions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SkillsSourceTest {
    @Test
    void composedSources_shouldAggregateFilterAndDeduplicateFirstOccurrence() {
        Skill first = skill("shared", "first");
        Skill duplicate = skill("shared", "second");
        Skill retained = skill("retained", "retained");
        SkillsSource source = new DeduplicatingSkillsSource(new FilteringSkillsSource(
                new AggregatingSkillsSource(List.of(
                        new InMemorySkillsSource(List.of(first)),
                        new InMemorySkillsSource(List.of(duplicate, retained)))),
                (skill, ignored) -> !skill.frontmatter().description().equals("second")));

        List<Skill> result = source.getSkillsAsync(context("session"), new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        assertThat(result).extracting(skill -> skill.frontmatter().name()).containsExactly("shared", "retained");
        assertThat(result.getFirst().frontmatter().description()).isEqualTo("first");
    }

    @Test
    void cachingSource_shouldSingleFlightAndIsolateBySelectedKey() {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<List<Skill>> firstFetch = new CompletableFuture<>();
        SkillsSource inner = (context, cancellation) -> {
            int call = calls.incrementAndGet();
            return call == 1
                    ? firstFetch
                    : CompletableFuture.completedFuture(
                            List.of(skill(context.session().sessionId(), "dynamic")));
        };
        CachingSkillsSource source = new CachingSkillsSource(
                inner, null, context -> context.session().sessionId());
        SkillsSourceContext firstContext = context("first");

        CompletionStage<List<Skill>> first = source.getSkillsAsync(firstContext, new DefaultRunCancellation());
        CompletionStage<List<Skill>> concurrent = source.getSkillsAsync(firstContext, new DefaultRunCancellation());
        firstFetch.complete(List.of(skill("cached", "cached")));

        assertThat(first.toCompletableFuture().join())
                .extracting(skill -> skill.frontmatter().name())
                .containsExactly("cached");
        assertThat(concurrent.toCompletableFuture().join())
                .extracting(skill -> skill.frontmatter().name())
                .containsExactly("cached");
        assertThat(source.getSkillsAsync(firstContext, new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .extracting(skill -> skill.frontmatter().name())
                .containsExactly("cached");
        assertThat(source.getSkillsAsync(context("second"), new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .extracting(skill -> skill.frontmatter().name())
                .containsExactly("second");
        assertThat(calls).hasValue(2);
    }

    @Test
    void cachingSource_shouldRetryAfterFailureAndRefreshAtNonPositiveInterval() {
        AtomicInteger calls = new AtomicInteger();
        SkillsSource inner = (context, cancellation) -> {
            if (calls.incrementAndGet() == 1) {
                return CompletableFuture.failedFuture(new IllegalStateException("temporary"));
            }
            return CompletableFuture.completedFuture(List.of(skill("retry", "retry")));
        };
        CachingSkillsSource source = new CachingSkillsSource(inner, Duration.ZERO, ignored -> "key");

        assertThatThrownBy(() -> source.getSkillsAsync(context("one"), new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .hasRootCauseMessage("temporary");
        assertThat(source.getSkillsAsync(context("one"), new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .extracting(skill -> skill.frontmatter().name())
                .containsExactly("retry");
        assertThat(source.getSkillsAsync(context("one"), new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .extracting(skill -> skill.frontmatter().name())
                .containsExactly("retry");
        assertThat(calls).hasValue(3);
    }

    private static Skill skill(String name, String description) {
        return InlineSkill.builder(new SkillFrontmatter(name, description), "instructions")
                .build();
    }

    private static SkillsSourceContext context(String sessionId) {
        AgentSession session = new AgentSession(sessionId);
        RunCancellation cancellation = new DefaultRunCancellation();
        AgentRunContext runContext = new AgentRunContext(
                "run-" + sessionId,
                new AgentMetadata("agent", null, null),
                Instant.EPOCH,
                List.of(),
                RunOptions.empty(),
                cancellation,
                Map.of(),
                session,
                ContextContribution.empty());
        return new SkillsSourceContext(runContext, session);
    }
}
