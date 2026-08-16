// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandles;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class CancellationSupport {
    private CancellationSupport() {}

    static <T> CompletionStage<T> linked(CompletionStage<T> stage, RunCancellation cancellation) {
        CompletionStage<T> checkedStage = Objects.requireNonNull(stage, "stage");
        RunCancellation checkedCancellation = Objects.requireNonNull(cancellation, "cancellation");
        if (checkedCancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        RunCancellationRegistration registration = RunCancellations.register(checkedCancellation, () -> {
            result.completeExceptionally(new RunCancelledException());
            try {
                checkedStage.toCompletableFuture().cancel(true);
            } catch (RuntimeException ignored) {
                // Cancellation is already represented by the framework result stage.
            }
        });
        checkedStage.whenComplete((value, failure) -> {
            if (failure == null) {
                result.complete(value);
            } else {
                Throwable cause = RunHandles.unwrap(failure);
                if (checkedCancellation.isCancellationRequested()) {
                    result.completeExceptionally(new RunCancelledException());
                } else {
                    result.completeExceptionally(cause);
                }
            }
        });
        result.whenComplete((ignored, failure) -> registration.close());
        return result.minimalCompletionStage();
    }
}
