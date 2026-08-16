// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class ValkeyAsyncSupport {
    private static final ScheduledThreadPoolExecutor TIMER = timer();

    private ValkeyAsyncSupport() {}

    static <T> CompletionStage<T> race(CompletionStage<T> upstream, Duration timeout, RunCancellation cancellation) {
        return race(upstream, timeout, cancellation, ignored -> {});
    }

    static <T> CompletionStage<T> race(
            CompletionStage<T> upstream, Duration timeout, RunCancellation cancellation, Consumer<T> lateSuccess) {
        ValkeyValidation.requireNonNull(upstream, "upstream");
        ValkeyValidation.requireNonNull(timeout, "timeout");
        ValkeyValidation.requireNonNull(cancellation, "cancellation");
        ValkeyValidation.requireNonNull(lateSuccess, "lateSuccess");
        CompletableFuture<T> result = new CompletableFuture<>();
        upstream.whenComplete((value, failure) -> {
            if (failure != null) {
                result.completeExceptionally(failure);
            } else if (!result.complete(value)) {
                lateSuccess.accept(value);
            }
        });
        if (cancellation.isCancellationRequested()) {
            result.completeExceptionally(new RunCancelledException());
            return result.minimalCompletionStage();
        }

        RunCancellationRegistration registration = RunCancellations.register(
                cancellation, () -> result.completeExceptionally(new RunCancelledException()));
        var timeoutTask = TIMER.schedule(
                () -> result.completeExceptionally(new ValkeyStorageException(
                        "The Valkey operation exceeded its configured deadline.",
                        null,
                        ValkeyStorageException.Kind.TIMEOUT)),
                timeout.toMillis(),
                TimeUnit.MILLISECONDS);
        result.whenComplete((ignored, failure) -> {
            timeoutTask.cancel(false);
            registration.close();
        });
        return result.minimalCompletionStage();
    }

    private static ScheduledThreadPoolExecutor timer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "agent-framework-valkey-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return timer;
    }
}
