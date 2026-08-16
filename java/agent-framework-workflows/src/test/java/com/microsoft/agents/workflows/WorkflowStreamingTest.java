// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkflowStreamingTest {
    @Test
    void streaming_shouldFailBoundedBufferWhenSubscriberHasNoDemand() {
        // Arrange
        Workflow<Integer, Integer> workflow = loopingWorkflow("overflow");
        TerminalSubscriber subscriber = new TerminalSubscriber(0);

        // Act
        workflow.runStreaming(
                        0,
                        WorkflowRunOptions.builder()
                                .maxBufferedEvents(2)
                                .maxSupersteps(10)
                                .build())
                .subscribe(subscriber);

        // Assert
        assertThatThrownBy(
                        () -> subscriber.result.orTimeout(5, TimeUnit.SECONDS).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(WorkflowStreamingBufferOverflowException.class);
        assertThat(subscriber.terminalSignals).hasValue(1);
        workflow.close();
    }

    @Test
    void streaming_shouldHonorIncrementalDemandAndSequenceEveryEvent() {
        // Arrange
        WorkflowBuilder<String, String> builder = WorkflowBuilder.create("demand", String.class, String.class);
        WorkflowNode<String, String> node =
                builder.addNode("node", FunctionExecutor.sync(String.class, String.class, (input, context) -> input));
        Workflow<String, String> workflow = builder.entry(node).output(node).build();
        TerminalSubscriber subscriber = new TerminalSubscriber(1);
        subscriber.requestAfterEach = true;

        // Act
        workflow.runStreaming("done").subscribe(subscriber);
        List<WorkflowEvent> events =
                subscriber.result.orTimeout(5, TimeUnit.SECONDS).join();

        // Assert
        assertThat(events).isNotEmpty();
        assertThat(events)
                .extracting(WorkflowEvent::sequence)
                .containsExactlyElementsOf(java.util.stream.LongStream.range(0, events.size())
                        .boxed()
                        .toList());
        assertThat(events.getLast().type()).isEqualTo(WorkflowEventType.RUN_COMPLETED);
        assertThat(subscriber.terminalSignals).hasValue(1);
        workflow.close();
    }

    @Test
    void finiteRun_shouldDiscardEventsAtSourceAndIgnoreStreamingBufferBound() {
        // Arrange
        Workflow<Integer, Integer> workflow = loopingWorkflow("finite-discard");

        // Act
        WorkflowRunResult<Integer> result = workflow.run(
                0,
                WorkflowRunOptions.builder()
                        .maxBufferedEvents(1)
                        .maxSupersteps(10)
                        .build());

        // Assert
        assertThat(result.output()).isEqualTo(5);
        workflow.close();
    }

    @Test
    void streaming_shouldPropagateExternalCancellationWithOneTerminalSignal() {
        // Arrange
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        CompletableFuture<Void> started = new CompletableFuture<>();
        WorkflowBuilder<Integer, Integer> builder = WorkflowBuilder.create("cancel", Integer.class, Integer.class);
        WorkflowNode<Integer, Integer> node = builder.addNode(
                "node",
                FunctionExecutor.async(
                        Integer.class,
                        Integer.class,
                        (input, context) -> CompletableFuture.supplyAsync(() -> {
                            started.complete(null);
                            while (!context.cancellation().isCancellationRequested()) {
                                Thread.onSpinWait();
                            }
                            throw new RunCancelledException();
                        })));
        Workflow<Integer, Integer> workflow = builder.entry(node).output(node).build();
        TerminalSubscriber subscriber = new TerminalSubscriber(Long.MAX_VALUE);

        // Act
        workflow.runStreaming(
                        0, WorkflowRunOptions.builder().runId("cancel-run").build(), cancellation)
                .subscribe(subscriber);
        started.orTimeout(5, TimeUnit.SECONDS).join();
        assertThat(cancellation.cancel()).isTrue();

        // Assert
        assertThatThrownBy(
                        () -> subscriber.result.orTimeout(5, TimeUnit.SECONDS).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RunCancelledException.class);
        assertThat(subscriber.events)
                .filteredOn(event -> event.type() == WorkflowEventType.RUN_CANCELLED)
                .hasSize(1);
        assertThat(subscriber.terminalSignals).hasValue(1);
        workflow.close();
    }

    @Test
    void streaming_shouldRejectSecondSubscriber() {
        // Arrange
        WorkflowBuilder<String, String> builder =
                WorkflowBuilder.create("single-subscriber", String.class, String.class);
        WorkflowNode<String, String> node =
                builder.addNode("node", FunctionExecutor.sync(String.class, String.class, (input, context) -> input));
        Workflow<String, String> workflow = builder.entry(node).output(node).build();
        Flow.Publisher<WorkflowEvent> publisher = workflow.runStreaming("value");
        TerminalSubscriber first = new TerminalSubscriber(Long.MAX_VALUE);
        TerminalSubscriber second = new TerminalSubscriber(Long.MAX_VALUE);

        // Act
        publisher.subscribe(first);
        publisher.subscribe(second);

        // Assert
        assertThat(first.result.orTimeout(5, TimeUnit.SECONDS).join()).isNotEmpty();
        assertThatThrownBy(() -> second.result.orTimeout(5, TimeUnit.SECONDS).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        workflow.close();
    }

    @Test
    void close_shouldCancelRunsAndNeverCloseCallerExecutor() {
        // Arrange
        ExecutorService callerExecutor = Executors.newFixedThreadPool(2);
        WorkflowBuilder<String, String> builder = WorkflowBuilder.create("ownership", String.class, String.class);
        WorkflowNode<String, String> node =
                builder.addNode("node", FunctionExecutor.sync(String.class, String.class, (input, context) -> input));
        Workflow<String, String> workflow =
                builder.entry(node).output(node).executorService(callerExecutor).build();

        // Act
        assertThat(workflow.run("value").output()).isEqualTo("value");
        workflow.close();

        // Assert
        assertThat(workflow.ownsExecutorService()).isFalse();
        assertThat(callerExecutor.isShutdown()).isFalse();
        assertThatThrownBy(() -> workflow.run("again"))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("closed");
        callerExecutor.close();
    }

    private static Workflow<Integer, Integer> loopingWorkflow(String id) {
        WorkflowBuilder<Integer, Integer> builder = WorkflowBuilder.create(id, Integer.class, Integer.class);
        WorkflowNode<Integer, Integer> node = builder.addNode(
                "node", FunctionExecutor.sync(Integer.class, Integer.class, (input, context) -> input + 1));
        return builder.entry(node)
                .output(node)
                .connectWhen(node, node, value -> value < 5)
                .allowCycles()
                .build();
    }

    private static final class TerminalSubscriber implements Flow.Subscriber<WorkflowEvent> {
        private final long initialDemand;

        private final ArrayList<WorkflowEvent> events = new ArrayList<>();

        private final CompletableFuture<List<WorkflowEvent>> result = new CompletableFuture<>();

        private final AtomicInteger terminalSignals = new AtomicInteger();

        private Flow.Subscription subscription;

        private boolean requestAfterEach;

        private TerminalSubscriber(long initialDemand) {
            this.initialDemand = initialDemand;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (initialDemand > 0) {
                subscription.request(initialDemand);
            }
        }

        @Override
        public void onNext(WorkflowEvent item) {
            events.add(item);
            if (requestAfterEach) {
                subscription.request(1);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            terminalSignals.incrementAndGet();
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            terminalSignals.incrementAndGet();
            result.complete(List.copyOf(events));
        }
    }
}
