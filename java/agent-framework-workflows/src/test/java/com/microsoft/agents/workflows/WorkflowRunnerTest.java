// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateCodec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class WorkflowRunnerTest {
    private static final StateCodec<Integer> INTEGER = WorkflowCodecs.integerCodec();

    @Test
    void runner_shouldExecuteDirectGraphAndDerivedSyncFacade() {
        // Arrange
        WorkflowBuilder<String, String> builder = WorkflowBuilder.create("direct", String.class, String.class);
        WorkflowNode<String, String> start = builder.addNode(
                "start", FunctionExecutor.sync(String.class, String.class, (value, context) -> value + "-processed"));
        WorkflowNode<String, String> finish = builder.addNode(
                "finish", FunctionExecutor.sync(String.class, String.class, (value, context) -> "done"));

        // Act
        try (Workflow<String, String> workflow =
                builder.entry(start).output(finish).connect(start, finish).build()) {
            WorkflowRunResult<String> asynchronous =
                    workflow.runAsync("hello").toCompletableFuture().join();
            WorkflowRunResult<String> synchronous = workflow.run("hello");

            // Assert
            assertThat(asynchronous.output()).isEqualTo("done");
            assertThat(synchronous.output()).isEqualTo("done");
            assertThat(asynchronous.supersteps()).isEqualTo(2);
            assertThat(synchronous.runId()).isNotEqualTo(asynchronous.runId());
        }
    }

    @Test
    void runner_shouldKeepFanOutFanInEventOrderStableAcrossRandomDelays() {
        // Arrange
        List<List<String>> traces = new ArrayList<>();

        // Act
        for (int seed = 0; seed < 12; seed++) {
            Random random = new Random(seed);
            WorkflowBuilder<Integer, Integer> builder =
                    WorkflowBuilder.create("deterministic-" + seed, Integer.class, Integer.class);
            WorkflowNode<Integer, Integer> source = builder.addNode(
                    "source",
                    FunctionExecutor.sync(Integer.class, Integer.class, INTEGER, INTEGER, (value, context) -> value));
            WorkflowNode<Integer, Integer> left =
                    builder.addNode("left", delayed(random.nextInt(20), value -> value + 1));
            WorkflowNode<Integer, Integer> right =
                    builder.addNode("right", delayed(random.nextInt(20), value -> value + 2));
            WorkflowNode<FanInInput, Integer> join = builder.addNode(
                    "join",
                    FunctionExecutor.sync(
                            FanInInput.class,
                            Integer.class,
                            (input, context) -> input.values(Integer.class).stream()
                                    .mapToInt(Integer::intValue)
                                    .sum()));
            try (Workflow<Integer, Integer> workflow = builder.entry(source)
                    .output(join)
                    .fanOut(source, List.of(left, right))
                    .fanIn(List.of(left, right), join)
                    .build()) {
                List<WorkflowEvent> events = collect(workflow.runStreaming(
                        1, WorkflowRunOptions.builder().runId("run").build()));
                traces.add(events.stream()
                        .filter(event -> event.type() == WorkflowEventType.NODE_STARTED
                                || event.type() == WorkflowEventType.NODE_COMPLETED
                                || event.type() == WorkflowEventType.FAN_IN_BUFFERED)
                        .map(event -> event.type() + ":" + event.nodeId())
                        .toList());
                assertThat(events)
                        .filteredOn(event -> event.type() == WorkflowEventType.OUTPUT)
                        .singleElement()
                        .extracting(event -> event.data().toString())
                        .isEqualTo("NumberValue[value=5]");
            }
        }

        // Assert
        assertThat(traces).allMatch(trace -> trace.equals(traces.getFirst()));
    }

    @Test
    void runner_shouldReleaseFanInOnlyAfterEachSourceArrivesForEveryEpoch() {
        // Arrange
        WorkflowBuilder<Integer, Integer> builder = WorkflowBuilder.create("epochs", Integer.class, Integer.class);
        WorkflowNode<Integer, Integer> source = builder.addNode(
                "source", FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> value));
        WorkflowNode<Integer, Integer> left =
                builder.addNode("left", FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> value));
        WorkflowNode<Integer, Integer> right = builder.addNode(
                "right", FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> value));
        WorkflowNode<FanInInput, Integer> join = builder.addNode(
                "join",
                FunctionExecutor.sync(
                        FanInInput.class,
                        Integer.class,
                        (input, context) -> input.values(Integer.class).stream()
                                .mapToInt(Integer::intValue)
                                .sum()));

        // Act
        try (Workflow<Integer, Integer> workflow = builder.entry(source)
                .output(join)
                .fanOut(source, List.of(left, right))
                .fanIn(List.of(left, right), join)
                .build()) {
            List<WorkflowEvent> events = collect(workflow.runStreaming(3));

            // Assert
            assertThat(events)
                    .filteredOn(event -> event.type() == WorkflowEventType.FAN_IN_BUFFERED)
                    .hasSize(2);
            assertThat(events)
                    .filteredOn(event -> event.type() == WorkflowEventType.FAN_IN_RELEASED)
                    .hasSize(1);
            assertThat(events)
                    .filteredOn(event -> event.type() == WorkflowEventType.FAN_IN_RELEASED)
                    .singleElement()
                    .satisfies(event -> assertThat(event.data().toString()).contains("epoch=NumberValue[value=0]"));
        }
    }

    @Test
    void runner_shouldKeepFanInEpochsSeparateAcrossLoopIterations() {
        // Arrange
        WorkflowBuilder<Integer, Integer> builder =
                WorkflowBuilder.create("multiple-epochs", Integer.class, Integer.class);
        WorkflowNode<Integer, Integer> source = builder.addNode(
                "source", FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> value));
        WorkflowNode<Integer, Integer> left =
                builder.addNode("left", FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> value));
        WorkflowNode<Integer, Integer> right = builder.addNode(
                "right", FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> value));
        WorkflowNode<FanInInput, Integer> join = builder.addNode(
                "join",
                FunctionExecutor.sync(
                        FanInInput.class,
                        Integer.class,
                        (input, context) -> input.values(Integer.class).stream()
                                .mapToInt(Integer::intValue)
                                .sum()));

        // Act
        try (Workflow<Integer, Integer> workflow = builder.entry(source)
                .output(join)
                .fanOut(source, List.of(left, right))
                .fanIn(List.of(left, right), join)
                .connectWhen(join, source, value -> value < 4)
                .allowCycles()
                .build()) {
            List<WorkflowEvent> events = collect(workflow.runStreaming(1));

            // Assert
            assertThat(events)
                    .filteredOn(event -> event.type() == WorkflowEventType.FAN_IN_RELEASED)
                    .extracting(event -> ((com.microsoft.agents.core.StateValue.NumberValue)
                                    ((com.microsoft.agents.core.StateValue.ObjectValue) event.data()).require("epoch"))
                            .value()
                            .intValueExact())
                    .containsExactly(0, 1);
            assertThat(events)
                    .filteredOn(event -> event.type() == WorkflowEventType.OUTPUT)
                    .extracting(WorkflowEvent::data)
                    .containsExactly(
                            com.microsoft.agents.core.StateValue.integer(2),
                            com.microsoft.agents.core.StateValue.integer(4));
        }
    }

    @Test
    void runner_shouldUseSnapshotStateAndDeterministicReducer() {
        // Arrange
        StateKey<Integer> total = StateKey.reducing("total", Integer.class, INTEGER, Integer::sum);
        WorkflowBuilder<Integer, Integer> builder =
                WorkflowBuilder.create("state-reducer", Integer.class, Integer.class);
        WorkflowNode<Integer, Integer> source = builder.addNode(
                "source", FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> value));
        WorkflowNode<Integer, Integer> left =
                builder.addNode("left", FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> {
                    assertThat(context.getState(total)).contains(10);
                    context.setState(total, 1);
                    return value;
                }));
        WorkflowNode<Integer, Integer> right =
                builder.addNode("right", FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> {
                    assertThat(context.getState(total)).contains(10);
                    context.setState(total, 2);
                    return value;
                }));
        WorkflowNode<FanInInput, Integer> join =
                builder.addNode("join", FunctionExecutor.sync(FanInInput.class, Integer.class, (value, context) -> 1));

        // Act
        try (Workflow<Integer, Integer> workflow = builder.entry(source)
                .output(join)
                .fanOut(source, List.of(left, right))
                .fanIn(List.of(left, right), join)
                .build()) {
            WorkflowRunResult<Integer> result = workflow.run(
                    0,
                    WorkflowRunOptions.builder()
                            .initialState(WorkflowState.builder().put(total, 10).build())
                            .build());

            // Assert
            assertThat(result.state().get(total)).contains(3);
        }
    }

    @Test
    void runner_shouldFailConflictingWritesWithoutPartialCommit() {
        // Arrange
        StateKey<Integer> value = StateKey.of("value", Integer.class, INTEGER);
        WorkflowState initial = WorkflowState.builder().put(value, 9).build();
        WorkflowBuilder<Integer, Integer> builder =
                WorkflowBuilder.create("state-conflict", Integer.class, Integer.class);
        WorkflowNode<Integer, Integer> source = builder.addNode(
                "source", FunctionExecutor.sync(Integer.class, Integer.class, (input, context) -> input));
        WorkflowNode<Integer, Integer> left =
                builder.addNode("left", FunctionExecutor.sync(Integer.class, Integer.class, (input, context) -> {
                    context.setState(value, 1);
                    return input;
                }));
        WorkflowNode<Integer, Integer> right =
                builder.addNode("right", FunctionExecutor.sync(Integer.class, Integer.class, (input, context) -> {
                    context.setState(value, 2);
                    return input;
                }));
        WorkflowNode<FanInInput, Integer> join =
                builder.addNode("join", FunctionExecutor.sync(FanInInput.class, Integer.class, (input, context) -> 1));

        // Act / Assert
        try (Workflow<Integer, Integer> workflow = builder.entry(source)
                .output(join)
                .fanOut(source, List.of(left, right))
                .fanIn(List.of(left, right), join)
                .build()) {
            assertThatThrownBy(() -> workflow.runAsync(
                                    0,
                                    WorkflowRunOptions.builder()
                                            .initialState(initial)
                                            .build())
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(StateConflictException.class);
            assertThat(initial.get(value)).contains(9);
        }
    }

    @Test
    void runner_shouldRollbackFailedBranchAndCancelSibling() throws Exception {
        // Arrange
        StateKey<Integer> value = StateKey.of("value", Integer.class, INTEGER);
        CountDownLatch slowStarted = new CountDownLatch(1);
        AtomicBoolean slowCancelled = new AtomicBoolean();
        WorkflowBuilder<Integer, Integer> builder = WorkflowBuilder.create("failure", Integer.class, Integer.class);
        WorkflowNode<Integer, Integer> source = builder.addNode(
                "source", FunctionExecutor.sync(Integer.class, Integer.class, (input, context) -> input));
        WorkflowNode<Integer, Integer> failing =
                builder.addNode("failing", FunctionExecutor.sync(Integer.class, Integer.class, (input, context) -> {
                    context.setState(value, 1);
                    throw new IllegalStateException("boom");
                }));
        WorkflowNode<Integer, Integer> slow = builder.addNode(
                "slow",
                FunctionExecutor.async(
                        Integer.class,
                        Integer.class,
                        (input, context) -> CompletableFuture.supplyAsync(() -> {
                            slowStarted.countDown();
                            while (!context.cancellation().isCancellationRequested()) {
                                Thread.onSpinWait();
                            }
                            slowCancelled.set(true);
                            throw new RunCancelledException();
                        })));
        WorkflowNode<FanInInput, Integer> join =
                builder.addNode("join", FunctionExecutor.sync(FanInInput.class, Integer.class, (input, context) -> 1));

        // Act / Assert
        try (Workflow<Integer, Integer> workflow = builder.entry(source)
                .output(join)
                .fanOut(source, List.of(failing, slow))
                .fanIn(List.of(failing, slow), join)
                .build()) {
            assertThatThrownBy(() -> workflow.runAsync(
                                    0,
                                    WorkflowRunOptions.builder()
                                            .initialState(WorkflowState.builder()
                                                    .put(value, 9)
                                                    .build())
                                            .build())
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(WorkflowExecutionException.class);
            assertThat(slowStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(slowCancelled).isTrue();
        }
    }

    @Test
    void runner_shouldSupportExplicitLoopsAndEnforceMaximumSupersteps() {
        // Arrange
        WorkflowBuilder<Integer, Integer> finiteBuilder =
                WorkflowBuilder.create("finite-loop", Integer.class, Integer.class);
        WorkflowNode<Integer, Integer> finite = finiteBuilder.addNode(
                "loop", FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> value + 1));
        WorkflowBuilder<Integer, Integer> infiniteBuilder =
                WorkflowBuilder.create("infinite-loop", Integer.class, Integer.class);
        WorkflowNode<Integer, Integer> infinite = infiniteBuilder.addNode(
                "loop", FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> value + 1));

        // Act / Assert
        try (Workflow<Integer, Integer> workflow = finiteBuilder
                .entry(finite)
                .output(finite)
                .connectWhen(finite, finite, value -> value < 3)
                .allowCycles()
                .build()) {
            assertThat(workflow.run(0).output()).isEqualTo(3);
        }
        try (Workflow<Integer, Integer> workflow = infiniteBuilder
                .entry(infinite)
                .output(infinite)
                .connectWhen(infinite, infinite, value -> true)
                .allowCycles()
                .build()) {
            assertThatThrownBy(() -> workflow.runAsync(
                                    0,
                                    WorkflowRunOptions.builder()
                                            .maxSupersteps(3)
                                            .build())
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(WorkflowConvergenceException.class);
        }
    }

    @Test
    void runner_shouldIsolateConcurrentRuns() {
        // Arrange
        StateKey<Integer> seen = StateKey.of("seen", Integer.class, INTEGER);
        WorkflowBuilder<Integer, Integer> builder = WorkflowBuilder.create("isolation", Integer.class, Integer.class);
        WorkflowNode<Integer, Integer> node =
                builder.addNode("node", FunctionExecutor.sync(Integer.class, Integer.class, (input, context) -> {
                    context.setState(seen, input);
                    return input;
                }));

        // Act
        try (Workflow<Integer, Integer> workflow =
                builder.entry(node).output(node).build()) {
            CompletableFuture<WorkflowRunResult<Integer>> first =
                    workflow.runAsync(1).toCompletableFuture();
            CompletableFuture<WorkflowRunResult<Integer>> second =
                    workflow.runAsync(2).toCompletableFuture();
            WorkflowRunResult<Integer> firstResult = first.join();
            WorkflowRunResult<Integer> secondResult = second.join();

            // Assert
            assertThat(firstResult.state().get(seen)).contains(1);
            assertThat(secondResult.state().get(seen)).contains(2);
            assertThat(firstResult.runId()).isNotEqualTo(secondResult.runId());
        }
    }

    private static FunctionExecutor<Integer, Integer> delayed(
            int millis, java.util.function.IntUnaryOperator operation) {
        return FunctionExecutor.async(
                Integer.class,
                Integer.class,
                INTEGER,
                INTEGER,
                (value, context) -> CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(Duration.ofMillis(millis));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new RunCancelledException();
                    }
                    return operation.applyAsInt(value);
                }));
    }

    private static List<WorkflowEvent> collect(Flow.Publisher<WorkflowEvent> publisher) {
        CollectingSubscriber subscriber = new CollectingSubscriber();
        publisher.subscribe(subscriber);
        return subscriber.result.orTimeout(5, TimeUnit.SECONDS).join();
    }

    private static final class CollectingSubscriber implements Flow.Subscriber<WorkflowEvent> {
        private final ArrayList<WorkflowEvent> events = new ArrayList<>();

        private final CompletableFuture<List<WorkflowEvent>> result = new CompletableFuture<>();

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
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(List.copyOf(events));
        }
    }
}
