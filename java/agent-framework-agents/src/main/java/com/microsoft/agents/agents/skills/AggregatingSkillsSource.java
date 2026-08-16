// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.RunCancellation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Concatenates multiple skill sources in declaration order. */
public final class AggregatingSkillsSource implements SkillsSource {
    private final List<SkillsSource> sources;

    /**
     * Creates an aggregating source.
     *
     * @param sources sources in precedence order
     */
    public AggregatingSkillsSource(List<? extends SkillsSource> sources) {
        Objects.requireNonNull(sources, "sources");
        this.sources = sources.stream()
                .map(source -> Objects.requireNonNull(source, "source"))
                .toList();
    }

    @Override
    public CompletionStage<List<Skill>> getSkillsAsync(SkillsSourceContext context, RunCancellation cancellation) {
        Objects.requireNonNull(context, "context");
        SkillValidation.requireActive(cancellation);
        CompletableFuture<List<Skill>> sequence = CompletableFuture.completedFuture(new ArrayList<>());
        for (SkillsSource source : sources) {
            sequence = sequence.thenCompose(accumulated -> source.getSkillsAsync(context, cancellation)
                    .thenApply(skills -> {
                        accumulated.addAll(Objects.requireNonNull(skills, "skills"));
                        return accumulated;
                    }));
        }
        return sequence.thenApply(List::copyOf);
    }
}
