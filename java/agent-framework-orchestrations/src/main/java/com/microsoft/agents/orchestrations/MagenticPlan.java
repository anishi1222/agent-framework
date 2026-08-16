// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.ValidationException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Represents one immutable version of a Magentic task plan.
 *
 * @param revision zero-based plan revision
 * @param summary non-blank plan summary
 * @param tasks non-empty ordered task list
 */
public record MagenticPlan(int revision, String summary, List<MagenticTask> tasks) {
    /** Creates a validated immutable plan. */
    public MagenticPlan {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative.");
        }
        summary = OrchestrationValidation.requireText(summary, "summary");
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
        if (tasks.isEmpty()) {
            throw new ValidationException("Magentic plan tasks must not be empty.");
        }
        HashSet<String> identifiers = new HashSet<>();
        for (MagenticTask task : tasks) {
            MagenticTask checked = Objects.requireNonNull(task, "tasks contains null");
            if (!identifiers.add(checked.id())) {
                throw new ValidationException("Duplicate Magentic task id '" + checked.id() + "'.");
            }
        }
    }
}
