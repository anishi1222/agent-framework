// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.RunCancellation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Removes duplicate skill names case-insensitively while retaining the first occurrence. */
public final class DeduplicatingSkillsSource extends DelegatingSkillsSource {
    /**
     * Creates a deduplicating source.
     *
     * @param innerSource decorated source
     */
    public DeduplicatingSkillsSource(SkillsSource innerSource) {
        super(innerSource);
    }

    @Override
    public CompletionStage<List<Skill>> getSkillsAsync(SkillsSourceContext context, RunCancellation cancellation) {
        return innerSource().getSkillsAsync(context, cancellation).thenApply(skills -> {
            Objects.requireNonNull(skills, "skills");
            Set<String> names = new HashSet<>();
            ArrayList<Skill> result = new ArrayList<>();
            for (Skill skill : skills) {
                Objects.requireNonNull(skill, "skill");
                if (names.add(SkillValidation.caseKey(skill.frontmatter().name()))) {
                    result.add(skill);
                }
            }
            return List.copyOf(result);
        });
    }
}
