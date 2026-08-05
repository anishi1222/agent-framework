// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RunControlTest {
    @Test
    void defaultCancellation_shouldBeThreadSafeIdempotent_andNotifyCompletion() {
        // Arrange
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        AtomicInteger notifications = new AtomicInteger();
        cancellation.cancelledAsync().thenRun(notifications::incrementAndGet);

        // Act
        boolean first = cancellation.cancel();
        boolean second = cancellation.cancel();

        // Assert
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(cancellation.isCancellationRequested()).isTrue();
        assertThat(cancellation.cancelledAsync().toCompletableFuture()).isCompleted();
        assertThat(notifications).hasValue(1);
    }

    @Test
    void runHandleSource_shouldMakeCancellationTerminal_andSuppressLaterSuccess() {
        // Arrange
        RunHandleSource<String> source = new RunHandleSource<>();
        AtomicInteger terminalSignals = new AtomicInteger();
        source.handle().resultAsync().whenComplete((ignored, failure) -> terminalSignals.incrementAndGet());

        // Act
        boolean cancelled = source.handle().cancel();
        boolean completed = source.tryComplete("late success");

        // Assert
        assertThat(cancelled).isTrue();
        assertThat(completed).isFalse();
        assertThat(source.handle().cancel()).isFalse();
        assertThat(terminalSignals).hasValue(1);
        assertThatThrownBy(() ->
                        source.handle().resultAsync().toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RunCancelledException.class);
    }

    @Test
    void runHandleSource_shouldAllowExactlyOneTerminalWinnerUnderRaces() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int iteration = 0; iteration < 100; iteration++) {
                RunHandleSource<String> source = new RunHandleSource<>();
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> cancellation = executor.submit(() -> {
                    start.await();
                    return source.handle().cancel();
                });
                Future<Boolean> success = executor.submit(() -> {
                    start.await();
                    return source.tryComplete("done");
                });

                start.countDown();
                boolean cancellationWon = cancellation.get(5, TimeUnit.SECONDS);
                boolean successWon = success.get(5, TimeUnit.SECONDS);

                assertThat(cancellationWon).isNotEqualTo(successWon);
                assertThat(source.isTerminal()).isTrue();
                if (successWon) {
                    assertThat(source.handle()
                                    .resultAsync()
                                    .toCompletableFuture()
                                    .join())
                            .isEqualTo("done");
                    assertThat(source.handle().cancel()).isFalse();
                } else {
                    assertThatThrownBy(() -> source.handle()
                                    .resultAsync()
                                    .toCompletableFuture()
                                    .join())
                            .isInstanceOf(CompletionException.class)
                            .hasCauseInstanceOf(RunCancelledException.class);
                }
            }
        }
    }

    @Test
    void successfulCompletion_shouldPreventSubsequentCancellation() {
        RunHandleSource<String> source = new RunHandleSource<>();

        assertThat(source.tryComplete("done")).isTrue();
        assertThat(source.handle().cancel()).isFalse();
        assertThat(source.cancellation().isCancellationRequested()).isFalse();
        assertThat(source.handle().resultAsync().toCompletableFuture().join()).isEqualTo("done");
    }
}
