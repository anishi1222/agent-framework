// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import com.microsoft.agents.core.RunCancellation;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/** Continues while background-agent tasks remain running. */
public final class BackgroundTaskCompletionLoopEvaluator implements LoopEvaluator {
    private final BackgroundAgentsProvider provider;

    /**
     * Creates a background-task evaluator.
     *
     * @param provider background-agent provider
     */
    public BackgroundTaskCompletionLoopEvaluator(BackgroundAgentsProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public CompletionStage<LoopEvaluation> evaluateAsync(LoopContext context, RunCancellation cancellation) {
        List<BackgroundTaskInfo> running = provider.getIncompleteTasks(context.session());
        if (running.isEmpty()) {
            return CompletableFuture.completedFuture(LoopEvaluation.stop());
        }
        String tasks = running.stream()
                .map(task -> "#" + task.id() + " " + task.description())
                .collect(Collectors.joining("\n- ", "- ", ""));
        return CompletableFuture.completedFuture(LoopEvaluation.continueWithFeedback(
                "Wait for or inspect the running background tasks before finishing:\n" + tasks));
    }
}
