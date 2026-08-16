// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.StateCodec;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.VersionedSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FunctionalWorkflowTest {
    private static final StateCodec<String> STRING = WorkflowCodecs.stringCodec();

    private static final StateKey<String> REVIEW_STATE = StateKey.of("reviewStatus", String.class, STRING);

    @Test
    void resume_shouldReplayCompletedStepsAndRestoreCheckpointAcrossWorkflowInstances() {
        // Arrange
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        CheckpointKey key = new CheckpointKey("functional-hitl");
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        FunctionalStep<String, String> first =
                FunctionalStep.sync("first-review", String.class, String.class, STRING, STRING, (input, context) -> {
                    firstCalls.incrementAndGet();
                    String response = context.requestInfo(StateValue.string("Approve " + input), String.class, STRING);
                    context.setState(REVIEW_STATE, "reviewed");
                    return input + "-" + response;
                });
        FunctionalStep<String, String> second =
                FunctionalStep.sync("second-review", String.class, String.class, STRING, STRING, (input, context) -> {
                    secondCalls.incrementAndGet();
                    assertThat(context.getState(REVIEW_STATE)).contains("reviewed");
                    String response = context.requestInfo(StateValue.string("Publish " + input), String.class, STRING);
                    return input + "-" + response;
                });
        FunctionalWorkflowRunOptions initialOptions = FunctionalWorkflowRunOptions.builder()
                .runId("functional-run")
                .checkpoint(storage, key, CheckpointStorage.CREATE_ONLY)
                .build();

        FunctionalWorkflowRunResult<String> firstResult;
        FunctionalWorkflowRunResult<String> secondResult;
        try (FunctionalWorkflow<String, String> workflow = workflow(first, second, "1")) {
            // Act
            firstResult = workflow.run("draft", initialOptions);
            secondResult = workflow.resume(FunctionalWorkflowResponses.of("auto::0", String.class, STRING, "approved"));
        }

        // Assert
        assertThat(firstResult.status()).isEqualTo(FunctionalWorkflowRunStatus.INPUT_REQUIRED);
        assertThat(firstResult.pendingRequests())
                .extracting(FunctionalInputRequest::requestId)
                .containsExactly("auto::0");
        assertThat(firstResult.events()).noneMatch(event -> event.type() == WorkflowEventType.NODE_FAILED);
        assertThat(secondResult.status()).isEqualTo(FunctionalWorkflowRunStatus.INPUT_REQUIRED);
        assertThat(secondResult.pendingRequests())
                .extracting(FunctionalInputRequest::requestId)
                .containsExactly("auto::1");
        assertThat(firstCalls).hasValue(2);
        assertThat(secondCalls).hasValue(1);
        assertThat(secondResult.checkpointRevision()).isGreaterThan(firstResult.checkpointRevision());

        // Arrange a new process-local workflow instance over the persisted checkpoint.
        try (FunctionalWorkflow<String, String> restored = workflow(first, second, "1")) {
            // Act
            FunctionalWorkflowRunResult<String> completed = restored.resume(
                    storage,
                    key,
                    FunctionalWorkflowResponses.of("auto::1", String.class, STRING, "published"),
                    FunctionalWorkflowRunOptions.defaults());

            // Assert
            assertThat(completed.status()).isEqualTo(FunctionalWorkflowRunStatus.COMPLETED);
            assertThat(completed.output()).contains("draft-approved-published");
            assertThat(completed.pendingRequests()).isEmpty();
            assertThat(completed.runId()).isEqualTo("functional-run");
            assertThat(completed.checkpointRevision()).isGreaterThan(secondResult.checkpointRevision());
            assertThat(completed.events())
                    .extracting(WorkflowEvent::type)
                    .containsSubsequence(
                            WorkflowEventType.CHECKPOINT_LOADED,
                            WorkflowEventType.WORKFLOW_RESUMED,
                            WorkflowEventType.NODE_BYPASSED,
                            WorkflowEventType.NODE_STARTED,
                            WorkflowEventType.NODE_COMPLETED,
                            WorkflowEventType.OUTPUT,
                            WorkflowEventType.RUN_COMPLETED);
            assertThat(firstCalls).hasValue(2);
            assertThat(secondCalls).hasValue(2);
        }
    }

    @Test
    void runStreaming_shouldBeColdAndDeliverLiveFunctionalEvents() {
        // Arrange
        AtomicInteger calls = new AtomicInteger();
        FunctionalStep<String, String> upper =
                FunctionalStep.sync("upper", String.class, String.class, STRING, STRING, (input, context) -> {
                    calls.incrementAndGet();
                    context.addEvent("progress", StateValue.string("halfway"));
                    return input.toUpperCase();
                });
        FunctionalWorkflow<String, String> workflow = FunctionalWorkflow.builder(
                        "streaming", String.class, String.class, STRING, STRING)
                .body((input, context) -> context.runStepAsync(upper, input))
                .build();
        Flow.Publisher<WorkflowEvent> publisher = workflow.runStreaming("hello");

        // Act
        assertThat(calls).hasValue(0);
        List<WorkflowEvent> events = collect(publisher);

        // Assert
        assertThat(calls).hasValue(1);
        assertThat(events)
                .extracting(WorkflowEvent::type)
                .containsExactly(
                        WorkflowEventType.RUN_STARTED,
                        WorkflowEventType.NODE_STARTED,
                        WorkflowEventType.CUSTOM,
                        WorkflowEventType.NODE_COMPLETED,
                        WorkflowEventType.OUTPUT,
                        WorkflowEventType.RUN_COMPLETED);
        assertThat(events).extracting(WorkflowEvent::sequence).containsExactly(0L, 1L, 2L, 3L, 4L, 5L);
        workflow.close();
    }

    @Test
    void startRun_shouldRejectConcurrentExecutionAndReleaseAfterCancellation() throws Exception {
        // Arrange
        CompletableFuture<String> gate = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        try (FunctionalWorkflow<String, String> workflow = FunctionalWorkflow.builder(
                        "exclusive", String.class, String.class, STRING, STRING)
                .body((input, context) -> {
                    started.countDown();
                    return gate;
                })
                .build()) {
            RunHandle<FunctionalWorkflowRunResult<String>> first =
                    workflow.startRun("first", FunctionalWorkflowRunOptions.defaults(), new DefaultRunCancellation());
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            // Act and assert
            assertThatThrownBy(() -> workflow.runAsync("second"))
                    .isInstanceOf(WorkflowException.class)
                    .hasMessageContaining("concurrent executions are not allowed");
            assertThat(first.cancel()).isTrue();
            assertThatThrownBy(() -> first.resultAsync().toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RunCancelledException.class);
            gate.complete("released");
            assertThat(waitUntil(() -> canStart(workflow), Duration.ofSeconds(5)))
                    .isTrue();
        }
    }

    @Test
    void checkpointResume_shouldRejectChangedFunctionalSignature() {
        // Arrange
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        CheckpointKey key = new CheckpointKey("signature-mismatch");
        FunctionalStep<String, String> review = FunctionalStep.sync(
                "review",
                String.class,
                String.class,
                STRING,
                STRING,
                (input, context) -> context.requestInfo(StateValue.string(input), String.class, STRING));
        try (FunctionalWorkflow<String, String> original = FunctionalWorkflow.builder(
                        "signature", String.class, String.class, STRING, STRING)
                .signatureVersion("1")
                .body((input, context) -> context.runStepAsync(review, input))
                .build()) {
            original.run(
                    "draft",
                    FunctionalWorkflowRunOptions.builder()
                            .checkpoint(storage, key, CheckpointStorage.CREATE_ONLY)
                            .build());
        }
        try (FunctionalWorkflow<String, String> changed = FunctionalWorkflow.builder(
                        "signature", String.class, String.class, STRING, STRING)
                .signatureVersion("2")
                .body((input, context) -> context.runStepAsync(review, input))
                .build()) {
            // Act and assert
            assertThatThrownBy(() -> changed.resumeAsync(
                                    storage,
                                    key,
                                    FunctionalWorkflowResponses.of("auto::0", String.class, STRING, "approved"),
                                    FunctionalWorkflowRunOptions.defaults())
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(WorkflowCheckpointException.class)
                    .hasRootCauseMessage("Checkpoint fingerprint does not match functional workflow 'signature'.");
        }
    }

    @Test
    void checkpointResume_shouldRetainEarlierResponsesUntilMultiRequestStepCompletes() {
        // Arrange
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        CheckpointKey key = new CheckpointKey("multi-request");
        AtomicInteger calls = new AtomicInteger();
        FunctionalStep<String, String> review =
                FunctionalStep.sync("multi-review", String.class, String.class, STRING, STRING, (input, context) -> {
                    calls.incrementAndGet();
                    String approver = context.requestInfo(StateValue.string("approver"), String.class, STRING);
                    String comment = context.requestInfo(StateValue.string("comment"), String.class, STRING);
                    return input + "-" + approver + "-" + comment;
                });
        FunctionalWorkflowRunOptions options = FunctionalWorkflowRunOptions.builder()
                .runId("multi-request-run")
                .checkpoint(storage, key, CheckpointStorage.CREATE_ONLY)
                .build();

        FunctionalWorkflowRunResult<String> secondRound;
        try (FunctionalWorkflow<String, String> workflow = singleStepWorkflow("multi-request", review)) {
            // Act
            FunctionalWorkflowRunResult<String> firstRound = workflow.run("draft", options);
            secondRound = workflow.resume(FunctionalWorkflowResponses.of("auto::0", String.class, STRING, "alex"));

            // Assert
            assertThat(firstRound.pendingRequests())
                    .extracting(FunctionalInputRequest::requestId)
                    .containsExactly("auto::0");
            assertThat(secondRound.pendingRequests())
                    .extracting(FunctionalInputRequest::requestId)
                    .containsExactly("auto::1");
        }

        // Act: restore on another instance and supply only the newly pending response.
        try (FunctionalWorkflow<String, String> restored = singleStepWorkflow("multi-request", review)) {
            FunctionalWorkflowRunResult<String> completed = restored.resume(
                    storage,
                    key,
                    FunctionalWorkflowResponses.of("auto::1", String.class, STRING, "ship-it"),
                    FunctionalWorkflowRunOptions.defaults());

            // Assert
            assertThat(completed.status()).isEqualTo(FunctionalWorkflowRunStatus.COMPLETED);
            assertThat(completed.output()).contains("draft-alex-ship-it");
            assertThat(completed.pendingRequests()).isEmpty();
            assertThat(completed.checkpointRevision()).isGreaterThan(secondRound.checkpointRevision());
            assertThat(calls).hasValue(3);
        }
    }

    @Test
    void cancellation_shouldReleaseWorkflowWhileStepCheckpointSaveIsBlocked() throws Exception {
        // Arrange
        BlockingCheckpointStorage storage = new BlockingCheckpointStorage();
        CheckpointKey key = new CheckpointKey("blocked-save");
        FunctionalStep<String, String> step = FunctionalStep.sync(
                "complete", String.class, String.class, STRING, STRING, (input, context) -> input + "-done");
        AtomicReference<FunctionalWorkflowRunResult<String>> secondResult = new AtomicReference<>();
        try (FunctionalWorkflow<String, String> workflow = singleStepWorkflow("blocked-save", step)) {
            RunHandle<FunctionalWorkflowRunResult<String>> first = workflow.startRun(
                    "first",
                    FunctionalWorkflowRunOptions.builder()
                            .runId("blocked-save-run")
                            .checkpoint(storage, key, CheckpointStorage.CREATE_ONLY)
                            .build(),
                    new DefaultRunCancellation());
            assertThat(storage.saveStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // Act
            assertThat(first.cancel()).isTrue();
            assertThatThrownBy(() -> first.resultAsync().toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RunCancelledException.class);
            boolean released = waitUntil(
                    () -> {
                        try {
                            secondResult.set(workflow.run("second"));
                            return true;
                        } catch (WorkflowException exception) {
                            return false;
                        }
                    },
                    Duration.ofSeconds(5));

            // Assert
            assertThat(released).isTrue();
            assertThat(secondResult.get().output()).contains("second-done");
            assertThat(storage.loadAsync(key).toCompletableFuture().join()).isEmpty();

            storage.release();
            VersionedSnapshot<WorkflowCheckpoint> persisted =
                    storage.loadAsync(key).toCompletableFuture().join().orElseThrow();
            assertThat(persisted.snapshot().status()).isEqualTo(WorkflowCheckpointStatus.RUNNING);
        }
    }

    @Test
    void cancellation_shouldReleaseWorkflowWhileInputRequiredCheckpointSaveIsBlocked() throws Exception {
        // Arrange
        BlockingCheckpointStorage storage = new BlockingCheckpointStorage();
        CheckpointKey key = new CheckpointKey("blocked-input-save");
        FunctionalStep<String, String> step = FunctionalStep.sync(
                "request",
                String.class,
                String.class,
                STRING,
                STRING,
                (input, context) -> context.requestInfo(StateValue.string("review " + input), String.class, STRING));
        try (FunctionalWorkflow<String, String> workflow = singleStepWorkflow("blocked-input-save", step)) {
            RunHandle<FunctionalWorkflowRunResult<String>> first = workflow.startRun(
                    "first",
                    FunctionalWorkflowRunOptions.builder()
                            .runId("blocked-input-save-run")
                            .checkpoint(storage, key, CheckpointStorage.CREATE_ONLY)
                            .build(),
                    new DefaultRunCancellation());
            assertThat(storage.saveStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // Act
            assertThat(first.cancel()).isTrue();
            assertThatThrownBy(() -> first.resultAsync().toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RunCancelledException.class);
            AtomicReference<FunctionalWorkflowRunResult<String>> secondResult = new AtomicReference<>();
            boolean released = waitUntil(
                    () -> {
                        try {
                            secondResult.set(workflow.run("second"));
                            return true;
                        } catch (WorkflowException exception) {
                            return false;
                        }
                    },
                    Duration.ofSeconds(5));

            // Assert
            assertThat(released).isTrue();
            assertThat(secondResult.get().status()).isEqualTo(FunctionalWorkflowRunStatus.INPUT_REQUIRED);
            storage.release();
            VersionedSnapshot<WorkflowCheckpoint> persisted =
                    storage.loadAsync(key).toCompletableFuture().join().orElseThrow();
            assertThat(persisted.snapshot().status()).isEqualTo(WorkflowCheckpointStatus.INPUT_REQUIRED);
        }
    }

    private static FunctionalWorkflow<String, String> workflow(
            FunctionalStep<String, String> first, FunctionalStep<String, String> second, String signatureVersion) {
        return FunctionalWorkflow.builder("functional-hitl", String.class, String.class, STRING, STRING)
                .signatureVersion(signatureVersion)
                .body((input, context) ->
                        context.runStepAsync(first, input).thenCompose(value -> context.runStepAsync(second, value)))
                .build();
    }

    private static FunctionalWorkflow<String, String> singleStepWorkflow(
            String id, FunctionalStep<String, String> step) {
        return FunctionalWorkflow.builder(id, String.class, String.class, STRING, STRING)
                .body((input, context) -> context.runStepAsync(step, input))
                .build();
    }

    private static List<WorkflowEvent> collect(Flow.Publisher<WorkflowEvent> publisher) {
        CompletableFuture<List<WorkflowEvent>> completion = new CompletableFuture<>();
        ArrayList<WorkflowEvent> events = new ArrayList<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(WorkflowEvent item) {
                events.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                completion.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                completion.complete(List.copyOf(events));
            }
        });
        return completion.orTimeout(5, TimeUnit.SECONDS).join();
    }

    private static boolean canStart(FunctionalWorkflow<String, String> workflow) {
        try {
            workflow.runAsync("probe");
            return true;
        } catch (WorkflowException exception) {
            return false;
        }
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static final class BlockingCheckpointStorage implements CheckpointStorage {
        private final InMemoryCheckpointStorage delegate = new InMemoryCheckpointStorage();

        private final CountDownLatch saveStarted = new CountDownLatch(1);

        private final AtomicReference<PendingSave> pending = new AtomicReference<>();

        @Override
        public Set<StorageCapability> capabilities() {
            return delegate.capabilities();
        }

        @Override
        public CompletionStage<Optional<VersionedSnapshot<WorkflowCheckpoint>>> loadAsync(CheckpointKey key) {
            return delegate.loadAsync(key);
        }

        @Override
        public CompletionStage<VersionedSnapshot<WorkflowCheckpoint>> saveAsync(
                CheckpointKey key, WorkflowCheckpoint checkpoint, long expectedRevision) {
            CompletableFuture<VersionedSnapshot<WorkflowCheckpoint>> result = new CompletableFuture<>();
            if (!pending.compareAndSet(null, new PendingSave(key, checkpoint, expectedRevision, result))) {
                return CompletableFuture.failedFuture(new IllegalStateException("Only one blocked save is supported."));
            }
            saveStarted.countDown();
            return result.minimalCompletionStage();
        }

        @Override
        public CompletionStage<Void> deleteAsync(CheckpointKey key, long expectedRevision) {
            return delegate.deleteAsync(key, expectedRevision);
        }

        @Override
        public CheckpointStorageDurability durability() {
            return delegate.durability();
        }

        private void release() {
            PendingSave save = pending.getAndSet(null);
            if (save == null) {
                throw new IllegalStateException("No checkpoint save is blocked.");
            }
            delegate.saveAsync(save.key(), save.checkpoint(), save.expectedRevision())
                    .whenComplete((stored, failure) -> {
                        if (failure == null) {
                            save.result().complete(stored);
                        } else {
                            save.result().completeExceptionally(failure);
                        }
                    });
        }
    }

    private record PendingSave(
            CheckpointKey key,
            WorkflowCheckpoint checkpoint,
            long expectedRevision,
            CompletableFuture<VersionedSnapshot<WorkflowCheckpoint>> result) {}
}
