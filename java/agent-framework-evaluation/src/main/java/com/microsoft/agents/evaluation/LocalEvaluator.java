// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Runs deterministic synchronous or asynchronous checks locally without provider calls.
 *
 * <p>Items and checks are processed in stable input order. An item passes only when at least one
 * check ran and every check passed.
 */
public final class LocalEvaluator implements Evaluator {
    private final String name;
    private final List<EvaluationCheck> checks;

    /**
     * Creates a local evaluator named {@code Local}.
     *
     * @param checks ordered checks
     */
    public LocalEvaluator(EvaluationCheck... checks) {
        this("Local", List.of(checks));
    }

    /**
     * Creates a named local evaluator.
     *
     * @param name non-blank evaluator name
     * @param checks ordered checks
     */
    public LocalEvaluator(String name, List<EvaluationCheck> checks) {
        this.name = EvaluationValidation.requireNonBlank(name, "name");
        this.checks = EvaluationValidation.copyList(checks, "checks");
        LinkedHashMap<String, Boolean> names = new LinkedHashMap<>();
        for (EvaluationCheck check : this.checks) {
            String checkName = EvaluationValidation.requireNonBlank(check.name(), "check name");
            if (names.put(checkName, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("Duplicate check name: " + checkName);
            }
        }
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * Returns the immutable ordered checks.
     *
     * @return local checks
     */
    public List<EvaluationCheck> checks() {
        return checks;
    }

    @Override
    public CompletionStage<EvalResults> evaluateAsync(
            List<EvalItem> items, String evaluationName, RunCancellation cancellation) {
        List<EvalItem> checkedItems = EvaluationValidation.copyList(items, "items");
        if (checkedItems.isEmpty()) {
            throw new IllegalArgumentException("items must contain at least one evaluation item.");
        }
        String checkedName = EvaluationValidation.requireNonBlank(evaluationName, "evaluationName");
        RunCancellation checkedCancellation = Objects.requireNonNull(cancellation, "cancellation");
        if (checkedCancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }

        EvaluationAccumulator accumulator = new EvaluationAccumulator();
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
        for (int index = 0; index < checkedItems.size(); index++) {
            int itemIndex = index;
            EvalItem item = checkedItems.get(index);
            chain = chain.thenCompose(ignored ->
                    evaluateItemAsync(itemIndex, item, checkedCancellation).thenAccept(accumulator::add));
        }
        CompletionStage<EvalResults> result = chain.thenApply(ignored -> accumulator.toResults(name, checkedName));
        return CancellationSupport.linked(result, checkedCancellation);
    }

    private CompletionStage<ItemOutcome> evaluateItemAsync(int itemIndex, EvalItem item, RunCancellation cancellation) {
        ItemAccumulator accumulator = new ItemAccumulator(itemIndex, item);
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
        for (EvaluationCheck check : checks) {
            chain = chain.thenCompose(
                    ignored -> evaluateCheckAsync(check, item, cancellation).thenAccept(accumulator::add));
        }
        return chain.thenApply(ignored -> accumulator.finish());
    }

    private CompletionStage<CheckOutcome> evaluateCheckAsync(
            EvaluationCheck check, EvalItem item, RunCancellation cancellation) {
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        CompletionStage<CheckResult> checkStage;
        try {
            checkStage = check.evaluateAsync(item, cancellation);
        } catch (RuntimeException failure) {
            return completedFailure(check, failure, cancellation);
        }
        if (checkStage == null) {
            return CompletableFuture.completedFuture(
                    CheckOutcome.error(check.name(), new IllegalStateException("The check returned a null stage.")));
        }
        return CancellationSupport.linked(checkStage, cancellation).handle((checkResult, failure) -> {
            if (failure != null) {
                Throwable cause = RunHandles.unwrap(failure);
                if (cause instanceof RunCancelledException || cancellation.isCancellationRequested()) {
                    throw new CompletionException(new RunCancelledException());
                }
                return CheckOutcome.error(check.name(), cause);
            }
            if (checkResult == null) {
                return CheckOutcome.error(check.name(), new IllegalStateException("The check returned a null result."));
            }
            return CheckOutcome.completed(check.name(), checkResult);
        });
    }

    private static CompletionStage<CheckOutcome> completedFailure(
            EvaluationCheck check, RuntimeException failure, RunCancellation cancellation) {
        if (failure instanceof RunCancelledException || cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        return CompletableFuture.completedFuture(CheckOutcome.error(check.name(), failure));
    }

    private record CheckOutcome(String name, EvalScoreResult score, Throwable error) {
        private static CheckOutcome completed(String name, CheckResult result) {
            return new CheckOutcome(
                    name,
                    new EvalScoreResult(name, result.score(), result.passed(), result.reason(), result.metadata()),
                    null);
        }

        private static CheckOutcome error(String name, Throwable failure) {
            String detail = failure.getClass().getSimpleName();
            if (failure.getMessage() != null && !failure.getMessage().isBlank()) {
                detail += ": " + failure.getMessage();
            }
            return new CheckOutcome(name, new EvalScoreResult(name, 0.0, null, detail), failure);
        }
    }

    private record ItemOutcome(EvalItemResult result, Map<String, EvalCounts> counts) {}

    private static final class ItemAccumulator {
        private final int itemIndex;
        private final EvalItem item;
        private final List<EvalScoreResult> scores = new ArrayList<>();
        private final Map<String, EvalCounts> counts = new LinkedHashMap<>();
        private final List<String> errors = new ArrayList<>();
        private boolean failed;

        private ItemAccumulator(int itemIndex, EvalItem item) {
            this.itemIndex = itemIndex;
            this.item = item;
        }

        private void add(CheckOutcome outcome) {
            scores.add(outcome.score());
            if (outcome.error() != null) {
                errors.add(outcome.name() + ": " + outcome.score().reason());
                counts.put(outcome.name(), new EvalCounts(0, 0, 1));
            } else if (Boolean.TRUE.equals(outcome.score().passed())) {
                counts.put(outcome.name(), new EvalCounts(1, 0, 0));
            } else {
                failed = true;
                counts.put(outcome.name(), new EvalCounts(0, 1, 0));
            }
        }

        private ItemOutcome finish() {
            EvalItemStatus status;
            String errorCode = null;
            String errorMessage = null;
            if (!errors.isEmpty()) {
                status = EvalItemStatus.ERROR;
                errorCode = "check_error";
                errorMessage = String.join("; ", errors);
            } else if (scores.isEmpty() || failed) {
                status = EvalItemStatus.FAIL;
            } else {
                status = EvalItemStatus.PASS;
            }
            EvalItemResult result = new EvalItemResult(
                    Integer.toString(itemIndex),
                    status,
                    scores,
                    item.query(),
                    item.response(),
                    errorCode,
                    errorMessage,
                    Map.of());
            return new ItemOutcome(result, Map.copyOf(counts));
        }
    }

    private static final class EvaluationAccumulator {
        private final List<EvalItemResult> items = new ArrayList<>();
        private final Map<String, MutableCounts> perEvaluator = new LinkedHashMap<>();

        private void add(ItemOutcome outcome) {
            items.add(outcome.result());
            outcome.counts()
                    .forEach((name, counts) -> perEvaluator
                            .computeIfAbsent(name, ignored -> new MutableCounts())
                            .add(counts));
        }

        private EvalResults toResults(String provider, String evaluationName) {
            int passed = 0;
            int failed = 0;
            int errored = 0;
            List<String> errorDetails = new ArrayList<>();
            for (EvalItemResult item : items) {
                switch (item.status()) {
                    case PASS -> passed++;
                    case FAIL -> failed++;
                    case ERROR -> {
                        errored++;
                        errorDetails.add(item.itemId() + ": " + item.errorMessage());
                    }
                }
            }
            Map<String, EvalCounts> immutablePerEvaluator = new LinkedHashMap<>();
            perEvaluator.forEach((name, counts) -> immutablePerEvaluator.put(name, counts.toCounts()));
            return new EvalResults(
                    provider,
                    evaluationName,
                    "local",
                    evaluationName,
                    EvalRunStatus.COMPLETED,
                    new EvalCounts(passed, failed, errored),
                    immutablePerEvaluator,
                    items,
                    Map.of(),
                    errorDetails.isEmpty() ? null : String.join("; ", errorDetails));
        }
    }

    private static final class MutableCounts {
        private int passed;
        private int failed;
        private int errored;

        private void add(EvalCounts counts) {
            passed = Math.addExact(passed, counts.passed());
            failed = Math.addExact(failed, counts.failed());
            errored = Math.addExact(errored, counts.errored());
        }

        private EvalCounts toCounts() {
            return new EvalCounts(passed, failed, errored);
        }
    }
}
