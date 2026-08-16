// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.RunCancellation;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Returns a fixed immutable skill list. */
public final class InMemorySkillsSource implements SkillsSource {
    private final List<Skill> skills;

    /**
     * Creates an in-memory source.
     *
     * @param skills skills in declaration order
     */
    public InMemorySkillsSource(List<? extends Skill> skills) {
        Objects.requireNonNull(skills, "skills");
        this.skills = skills.stream()
                .map(skill -> Objects.requireNonNull(skill, "skill"))
                .toList();
    }

    @Override
    public CompletionStage<List<Skill>> getSkillsAsync(SkillsSourceContext context, RunCancellation cancellation) {
        Objects.requireNonNull(context, "context");
        SkillValidation.requireActive(cancellation);
        return CompletableFuture.completedFuture(skills);
    }
}
