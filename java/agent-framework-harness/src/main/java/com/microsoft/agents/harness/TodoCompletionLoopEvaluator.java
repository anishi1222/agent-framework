// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import com.microsoft.agents.core.RunCancellation;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/** Continues while configured-mode todo items remain incomplete. */
public final class TodoCompletionLoopEvaluator implements LoopEvaluator {
    private final TodoProvider todoProvider;

    private final AgentModeProvider modeProvider;

    private final Set<String> loopingModes;

    /**
     * Creates an evaluator that continues in every mode while todos remain.
     *
     * @param todoProvider todo provider
     */
    public TodoCompletionLoopEvaluator(TodoProvider todoProvider) {
        this(todoProvider, null, null);
    }

    /**
     * Creates a mode-gated todo evaluator.
     *
     * @param todoProvider todo provider
     * @param modeProvider mode provider
     * @param loopingModes non-empty modes that permit reinvocation, or {@code null} for every mode
     */
    public TodoCompletionLoopEvaluator(
            TodoProvider todoProvider, AgentModeProvider modeProvider, Set<String> loopingModes) {
        this.todoProvider = Objects.requireNonNull(todoProvider, "todoProvider");
        this.modeProvider = modeProvider;
        if (loopingModes != null && loopingModes.isEmpty()) {
            throw new IllegalArgumentException("loopingModes must not be empty when present.");
        }
        if (loopingModes != null && modeProvider == null) {
            throw new IllegalArgumentException("loopingModes requires an AgentModeProvider.");
        }
        this.loopingModes = loopingModes == null
                ? null
                : loopingModes.stream()
                        .map(mode -> mode.strip().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public CompletionStage<LoopEvaluation> evaluateAsync(LoopContext context, RunCancellation cancellation) {
        CompletionStage<Boolean> modeAllows = loopingModes == null
                ? CompletableFuture.completedFuture(true)
                : modeProvider.getModeAsync(context.session(), cancellation).thenApply(loopingModes::contains);
        return modeAllows.thenCompose(allows -> {
            if (!allows) {
                return CompletableFuture.completedFuture(LoopEvaluation.stop());
            }
            return todoProvider
                    .getRemainingTodosAsync(context.session(), cancellation)
                    .thenApply(this::evaluation);
        });
    }

    private LoopEvaluation evaluation(List<TodoItem> remaining) {
        if (remaining.isEmpty()) {
            return LoopEvaluation.stop();
        }
        String todos = remaining.stream()
                .map(item -> "#" + item.id() + " " + item.title())
                .collect(Collectors.joining("\n- ", "- ", ""));
        return LoopEvaluation.continueWithFeedback("Continue working through the remaining todos:\n" + todos);
    }
}
