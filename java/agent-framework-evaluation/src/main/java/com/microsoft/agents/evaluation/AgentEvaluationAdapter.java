// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Adapts a provider-neutral {@link Agent} to asynchronous evaluation cases.
 *
 * @param <T> optional structured agent response type
 */
public final class AgentEvaluationAdapter<T> {
    private final Agent<T> agent;

    /**
     * Creates a non-owning agent evaluation adapter.
     *
     * @param agent agent to evaluate
     */
    public AgentEvaluationAdapter(Agent<T> agent) {
        this.agent = Objects.requireNonNull(agent, "agent");
    }

    /**
     * Evaluates cases with default options and an adapter-owned cancellation signal.
     *
     * @param cases evaluation cases
     * @param evaluator evaluation backend
     * @return terminal evaluation-result stage
     */
    public CompletionStage<EvalResults> evaluateAsync(List<EvaluationCase> cases, Evaluator evaluator) {
        return evaluateAsync(cases, evaluator, AgentEvaluationOptions.defaults(), new DefaultRunCancellation());
    }

    /**
     * Evaluates cases asynchronously with caller-owned cancellation.
     *
     * @param cases evaluation cases
     * @param evaluator evaluation backend
     * @param options execution options
     * @param cancellation caller-owned cancellation signal
     * @return terminal evaluation-result stage
     */
    public CompletionStage<EvalResults> evaluateAsync(
            List<EvaluationCase> cases,
            Evaluator evaluator,
            AgentEvaluationOptions options,
            RunCancellation cancellation) {
        List<EvaluationCase> checkedCases = EvaluationValidation.copyList(cases, "cases");
        if (checkedCases.isEmpty()) {
            throw new IllegalArgumentException("cases must contain at least one evaluation case.");
        }
        Evaluator checkedEvaluator = Objects.requireNonNull(evaluator, "evaluator");
        AgentEvaluationOptions checkedOptions = Objects.requireNonNull(options, "options");
        RunCancellation checkedCancellation = Objects.requireNonNull(cancellation, "cancellation");
        if (checkedCancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }

        List<EvalItem> items = new ArrayList<>();
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
        for (int repetition = 0; repetition < checkedOptions.repetitions(); repetition++) {
            for (EvaluationCase evaluationCase : checkedCases) {
                chain = chain.thenCompose(ignored -> runCaseAsync(evaluationCase, checkedOptions, checkedCancellation)
                        .thenAccept(items::add));
            }
        }
        CompletionStage<EvalResults> result = chain.thenCompose(ignored -> {
            CompletionStage<EvalResults> evaluationStage = checkedEvaluator.evaluateAsync(
                    List.copyOf(items), checkedOptions.evaluationName(), checkedCancellation);
            if (evaluationStage == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("The evaluator returned a null stage."));
            }
            return CancellationSupport.linked(evaluationStage, checkedCancellation);
        });
        return CancellationSupport.linked(result, checkedCancellation);
    }

    private CompletionStage<EvalItem> runCaseAsync(
            EvaluationCase evaluationCase, AgentEvaluationOptions options, RunCancellation cancellation) {
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        CompletionStage<AgentResponse<T>> responseStage;
        try {
            responseStage = agent.runAsync(evaluationCase.inputMessages(), options.runOptions(), cancellation);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (responseStage == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("The agent returned a null stage."));
        }
        return CancellationSupport.linked(responseStage, cancellation)
                .thenApply(response -> evaluationCase.toEvalItem(
                        Objects.requireNonNull(response, "agent response").messages()));
    }
}
