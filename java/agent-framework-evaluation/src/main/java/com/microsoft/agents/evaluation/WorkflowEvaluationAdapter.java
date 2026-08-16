// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.workflows.Workflow;
import com.microsoft.agents.workflows.WorkflowRunResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Adapts a provider-neutral {@link Workflow} to asynchronous evaluation cases.
 *
 * @param <I> workflow input type
 * @param <O> workflow output type
 */
public final class WorkflowEvaluationAdapter<I, O> {
    private final Workflow<I, O> workflow;
    private final WorkflowOutputMapper<O> outputMapper;

    /**
     * Creates a non-owning workflow evaluation adapter.
     *
     * @param workflow workflow to evaluate
     * @param outputMapper workflow-result mapper
     */
    public WorkflowEvaluationAdapter(Workflow<I, O> workflow, WorkflowOutputMapper<O> outputMapper) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.outputMapper = Objects.requireNonNull(outputMapper, "outputMapper");
    }

    /**
     * Evaluates cases with default options and an adapter-owned cancellation signal.
     *
     * @param cases workflow evaluation cases
     * @param evaluator evaluation backend
     * @return terminal evaluation-result stage
     */
    public CompletionStage<EvalResults> evaluateAsync(List<WorkflowEvaluationCase<I>> cases, Evaluator evaluator) {
        return evaluateAsync(cases, evaluator, WorkflowEvaluationOptions.defaults(), new DefaultRunCancellation());
    }

    /**
     * Evaluates workflow cases asynchronously with caller-owned cancellation.
     *
     * @param cases workflow evaluation cases
     * @param evaluator evaluation backend
     * @param options execution options
     * @param cancellation caller-owned cancellation signal
     * @return terminal evaluation-result stage
     */
    public CompletionStage<EvalResults> evaluateAsync(
            List<WorkflowEvaluationCase<I>> cases,
            Evaluator evaluator,
            WorkflowEvaluationOptions options,
            RunCancellation cancellation) {
        List<WorkflowEvaluationCase<I>> checkedCases = EvaluationValidation.copyList(cases, "cases");
        if (checkedCases.isEmpty()) {
            throw new IllegalArgumentException("cases must contain at least one evaluation case.");
        }
        Evaluator checkedEvaluator = Objects.requireNonNull(evaluator, "evaluator");
        WorkflowEvaluationOptions checkedOptions = Objects.requireNonNull(options, "options");
        RunCancellation checkedCancellation = Objects.requireNonNull(cancellation, "cancellation");
        if (checkedCancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }

        List<EvalItem> items = new ArrayList<>();
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
        for (int repetition = 0; repetition < checkedOptions.repetitions(); repetition++) {
            for (WorkflowEvaluationCase<I> evaluationCase : checkedCases) {
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
            WorkflowEvaluationCase<I> workflowCase, WorkflowEvaluationOptions options, RunCancellation cancellation) {
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        CompletionStage<WorkflowRunResult<O>> resultStage;
        try {
            resultStage = workflow.runAsync(workflowCase.input(), options.runOptions(), cancellation);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (resultStage == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("The workflow returned a null stage."));
        }
        return CancellationSupport.linked(resultStage, cancellation).thenApply(result -> {
            List<Message> responseMessages = outputMapper.map(Objects.requireNonNull(result, "workflow result"));
            return workflowCase
                    .evaluationCase()
                    .toEvalItem(EvaluationValidation.copyList(responseMessages, "responseMessages"));
        });
    }
}
