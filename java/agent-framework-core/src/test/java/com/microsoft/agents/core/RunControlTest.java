// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
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
    void defaultCancellation_shouldReleaseClosedListenersAndNotifyActiveListenersOnce() {
        // Arrange
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        AtomicInteger removedNotifications = new AtomicInteger();
        AtomicInteger activeNotifications = new AtomicInteger();
        RunCancellationRegistration removed = cancellation.register(removedNotifications::incrementAndGet);
        cancellation.register(activeNotifications::incrementAndGet);
        assertThat(cancellation.registeredListenerCount()).isEqualTo(2);

        // Act
        removed.close();
        boolean cancelled = cancellation.cancel();
        cancellation.cancel();

        // Assert
        assertThat(cancelled).isTrue();
        assertThat(removedNotifications).hasValue(0);
        assertThat(activeNotifications).hasValue(1);
        assertThat(cancellation.registeredListenerCount()).isZero();
        AtomicInteger lateNotifications = new AtomicInteger();
        cancellation.register(lateNotifications::incrementAndGet);
        assertThat(lateNotifications).hasValue(1);
        assertThat(cancellation.registeredListenerCount()).isZero();
    }

    @Test
    void fallbackCancellationRegistration_shouldReleaseCallbackCaptureWhenClosed() {
        // Arrange
        CompletableFuture<Void> cancelled = new CompletableFuture<>();
        AtomicInteger notifications = new AtomicInteger();
        RunCancellation cancellation = new RunCancellation() {
            @Override
            public boolean cancel() {
                return cancelled.complete(null);
            }

            @Override
            public boolean isCancellationRequested() {
                return cancelled.isDone();
            }

            @Override
            public CompletionStage<Void> cancelledAsync() {
                return cancelled.minimalCompletionStage();
            }
        };
        RunCancellationRegistration registration =
                RunCancellations.register(cancellation, notifications::incrementAndGet);
        FallbackRunCancellationRegistration fallback = (FallbackRunCancellationRegistration) registration;
        assertThat(fallback.hasListener()).isTrue();

        // Act
        registration.close();
        cancellation.cancel();

        // Assert
        assertThat(fallback.hasListener()).isFalse();
        assertThat(notifications).hasValue(0);
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

    @Test
    void runHandleSource_shouldReleaseCancellationListenersOnNonCancellationTerminalState() {
        // Arrange
        RunHandleSource<String> source = new RunHandleSource<>();
        ObservableRunCancellation cancellation = (ObservableRunCancellation) source.cancellation();
        AtomicInteger notifications = new AtomicInteger();
        cancellation.register(notifications::incrementAndGet);

        // Act
        boolean completed = source.tryComplete("done");

        // Assert
        assertThat(completed).isTrue();
        assertThat(source.handle().cancel()).isFalse();
        assertThat(notifications).hasValue(0);
        AtomicInteger lateNotifications = new AtomicInteger();
        cancellation.register(lateNotifications::incrementAndGet);
        assertThat(lateNotifications).hasValue(0);
    }

    @Test
    void runHandleSource_shouldLinkCallerOwnedCancellationWithoutCancellingItAfterSuccess() {
        // Arrange
        DefaultRunCancellation callerCancellation = new DefaultRunCancellation();
        RunHandleSource<String> cancelledSource = new RunHandleSource<>(callerCancellation);

        // Act
        boolean cancelled = callerCancellation.cancel();

        // Assert
        assertThat(cancelled).isTrue();
        assertThatThrownBy(() -> cancelledSource
                        .handle()
                        .resultAsync()
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RunCancelledException.class);

        DefaultRunCancellation successfulCallerCancellation = new DefaultRunCancellation();
        RunHandleSource<String> successfulSource = new RunHandleSource<>(successfulCallerCancellation);
        assertThat(successfulSource.tryComplete("done")).isTrue();
        assertThat(successfulSource.handle().cancel()).isFalse();
        assertThat(successfulCallerCancellation.isCancellationRequested()).isFalse();
    }

    @Test
    void runHandleSource_shouldNotifyCancellationListenersBeforeTerminalCleanupCallbacks() {
        // Arrange
        RunHandleSource<String> source = new RunHandleSource<>();
        ObservableRunCancellation cancellation = (ObservableRunCancellation) source.cancellation();
        AtomicInteger notifications = new AtomicInteger();
        RunCancellationRegistration registration = cancellation.register(notifications::incrementAndGet);
        source.handle().resultAsync().whenComplete((ignored, failure) -> registration.close());

        // Act
        source.handle().cancel();

        // Assert
        assertThat(notifications).hasValue(1);
    }

    @Test
    void cancellationListenerFailure_shouldNotBlockOtherListenersOrRunTerminalState() {
        // Arrange
        RunHandleSource<String> source = new RunHandleSource<>();
        ObservableRunCancellation cancellation = (ObservableRunCancellation) source.cancellation();
        AtomicInteger notifications = new AtomicInteger();
        cancellation.register(() -> {
            throw new IllegalStateException("listener failure");
        });
        cancellation.register(notifications::incrementAndGet);

        // Act
        boolean cancelled = source.handle().cancel();

        // Assert
        assertThat(cancelled).isTrue();
        assertThat(notifications).hasValue(1);
        assertThatThrownBy(() ->
                        source.handle().resultAsync().toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RunCancelledException.class);
    }
}
