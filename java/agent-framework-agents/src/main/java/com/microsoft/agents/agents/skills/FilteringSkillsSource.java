// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.RunCancellation;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.BiPredicate;

/** Filters discovered skills with a context-aware predicate. */
public final class FilteringSkillsSource extends DelegatingSkillsSource {
    private final BiPredicate<Skill, SkillsSourceContext> predicate;

    /**
     * Creates a filtering source.
     *
     * @param innerSource decorated source
     * @param predicate inclusion predicate
     */
    public FilteringSkillsSource(SkillsSource innerSource, BiPredicate<Skill, SkillsSourceContext> predicate) {
        super(innerSource);
        this.predicate = Objects.requireNonNull(predicate, "predicate");
    }

    @Override
    public CompletionStage<List<Skill>> getSkillsAsync(SkillsSourceContext context, RunCancellation cancellation) {
        Objects.requireNonNull(context, "context");
        return innerSource()
                .getSkillsAsync(context, cancellation)
                .thenApply(skills -> skills.stream()
                        .filter(skill -> predicate.test(skill, context))
                        .toList());
    }
}
