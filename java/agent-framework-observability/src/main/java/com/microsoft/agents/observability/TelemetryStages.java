// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

@SuppressWarnings("try")
final class TelemetryStages {
    private TelemetryStages() {}

    static <T> CompletionStage<T> observe(
            CompletionStage<T> source, TelemetryOperation operation, Consumer<T> successObserver) {
        if (source == null) {
            operation.failure(new IllegalStateException("Instrumented operation returned a null stage."));
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Instrumented operation returned a null stage."));
        }
        try {
            source.whenComplete((value, failure) -> {
                if (failure == null) {
                    operation.observeInstrumentation(() -> successObserver.accept(value));
                    operation.success();
                } else {
                    operation.failure(failure);
                }
            });
        } catch (Throwable instrumentationFailure) {
            operation.instrumentationFailure(instrumentationFailure);
            operation.abandoned();
        }
        return source;
    }
}
