// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.SynchronousExecutionException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class OrchestrationExecutionTest {
    @Test
    void streaming_shouldHonorDemandSequenceAndSingleSubscriberContract() {
        // Arrange
        TestAgent agent = TestAgent.responding("agent", "done");
        SequentialOrchestration orchestration = SequentialOrchestration.builder(
                        List.of(OrchestrationParticipant.of(agent)))
                .build();
        TestEventSubscriber first = new TestEventSubscriber(1);
        first.requestAfterEach();
        TestEventSubscriber second = new TestEventSubscriber(Long.MAX_VALUE);
        java.util.concurrent.Flow.Publisher<OrchestrationEvent> publisher = orchestration.runStreaming("request");

        // Act
        publisher.subscribe(first);
        publisher.subscribe(second);
        List<OrchestrationEvent> events =
                first.result().orTimeout(5, TimeUnit.SECONDS).join();

        // Assert
        assertThat(events).isNotEmpty();
        assertThat(events)
                .extracting(OrchestrationEvent::sequence)
                .containsExactlyElementsOf(java.util.stream.LongStream.range(0, events.size())
                        .boxed()
                        .toList());
        assertThat(events.getLast().type()).isEqualTo(OrchestrationEventType.RUN_COMPLETED);
        assertThat(first.terminalSignals()).hasValue(1);
        assertThatThrownBy(() -> second.result().orTimeout(5, TimeUnit.SECONDS).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        orchestration.close();
        agent.close();
    }

    @Test
    void streaming_shouldFailOverflowAndInvalidDemand() {
        // Arrange
        TestAgent agent = TestAgent.responding("agent", "done");
        SequentialOrchestration orchestration = SequentialOrchestration.builder(
                        List.of(OrchestrationParticipant.of(agent)))
                .build();
        TestEventSubscriber overflow = new TestEventSubscriber(0);

        // Act
        orchestration
                .runStreaming(
                        List.of(com.microsoft.agents.core.Message.text(com.microsoft.agents.core.Role.USER, "request")),
                        OrchestrationRunOptions.builder().maxBufferedEvents(1).build())
                .subscribe(overflow);

        // Assert
        assertThatThrownBy(
                        () -> overflow.result().orTimeout(5, TimeUnit.SECONDS).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(OrchestrationStreamingBufferOverflowException.class);
        assertThat(overflow.terminalSignals()).hasValue(1);

        TestEventSubscriber invalidDemand = new TestEventSubscriber(0);
        orchestration.runStreaming("another").subscribe(invalidDemand);
        invalidDemand.request(0);
        assertThatThrownBy(() ->
                        invalidDemand.result().orTimeout(5, TimeUnit.SECONDS).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);

        orchestration.close();
        agent.close();
    }

    @Test
    void cancellation_shouldPropagateThroughRunHandleAndStreamingPublisher() throws Exception {
        // Arrange
        TestAgent finiteAgent = new TestAgent(
                "finite", 10_000, (messages, options, invocation) -> TestAgent.response("finite", "late"));
        SequentialOrchestration finite = SequentialOrchestration.builder(
                        List.of(OrchestrationParticipant.of(finiteAgent)))
                .build();
        com.microsoft.agents.core.RunHandle<OrchestrationResult<AgentResponse<?>>> handle =
                finite.startRun("request", OrchestrationRunOptions.defaults());
        finiteAgent.firstInvocation().orTimeout(5, TimeUnit.SECONDS).join();

        // Act
        assertThat(handle.cancel()).isTrue();

        // Assert
        assertThatThrownBy(() -> handle.resultAsync().toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RunCancelledException.class);

        TestAgent streamingAgent = new TestAgent(
                "streaming", 10_000, (messages, options, invocation) -> TestAgent.response("streaming", "late"));
        SequentialOrchestration streaming = SequentialOrchestration.builder(
                        List.of(OrchestrationParticipant.of(streamingAgent)))
                .build();
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        TestEventSubscriber subscriber = new TestEventSubscriber(Long.MAX_VALUE);
        java.util.concurrent.CopyOnWriteArrayList<OrchestrationEvent> cancellationEvents =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        streaming
                .runStreaming(
                        List.of(com.microsoft.agents.core.Message.text(com.microsoft.agents.core.Role.USER, "request")),
                        OrchestrationRunOptions.builder()
                                .eventListener(cancellationEvents::add)
                                .build(),
                        cancellation)
                .subscribe(subscriber);
        streamingAgent.firstInvocation().orTimeout(5, TimeUnit.SECONDS).join();
        assertThat(cancellation.cancel()).isTrue();
        long cancellationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (cancellationEvents.stream().noneMatch(event -> event.type() == OrchestrationEventType.RUN_CANCELLED)
                && System.nanoTime() < cancellationDeadline) {
            Thread.sleep(5);
        }
        assertThat(cancellationEvents)
                .extracting(OrchestrationEvent::type)
                .contains(OrchestrationEventType.RUN_CANCELLED);
        assertThatThrownBy(
                        () -> subscriber.result().orTimeout(5, TimeUnit.SECONDS).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RunCancelledException.class);

        finite.close();
        streaming.close();
        finiteAgent.close();
        streamingAgent.close();
    }

    @Test
    void synchronousInterruption_shouldCancelRestoreFlagAndWrapCause() throws Exception {
        // Arrange
        TestAgent agent =
                new TestAgent("agent", 10_000, (messages, options, invocation) -> TestAgent.response("agent", "late"));
        SequentialOrchestration orchestration = SequentialOrchestration.builder(
                        List.of(OrchestrationParticipant.of(agent)))
                .build();
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                orchestration.run("request");
                failure.complete(new AssertionError("Expected interruption."));
            } catch (Throwable thrown) {
                interrupted.set(Thread.currentThread().isInterrupted());
                failure.complete(thrown);
            }
        });
        agent.firstInvocation().orTimeout(5, TimeUnit.SECONDS).join();

        // Act
        caller.interrupt();
        caller.join(TimeUnit.SECONDS.toMillis(5));

        // Assert
        assertThat(failure.join())
                .isInstanceOf(SynchronousExecutionException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(interrupted).isTrue();

        orchestration.close();
        agent.close();
    }

    @Test
    void runMetadata_shouldPropagateOrchestrationAndEventCorrelationToAgentContext() {
        // Arrange
        TestAgent agent = TestAgent.responding("agent", "done");
        SequentialOrchestration orchestration = SequentialOrchestration.builder(
                        List.of(OrchestrationParticipant.of(agent)))
                .id("metadata-orchestration")
                .build();
        java.util.concurrent.CopyOnWriteArrayList<OrchestrationEvent> observed =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        OrchestrationRunOptions options = OrchestrationRunOptions.builder()
                .runId("metadata-run")
                .metadata(java.util.Map.of("tenant", StateValue.string("contoso")))
                .eventListener(observed::add)
                .build();

        // Act
        OrchestrationResult<AgentResponse<?>> result = orchestration.run("request", options);

        // Assert
        java.util.Map<String, StateValue> metadata =
                agent.runOptions().getFirst().metadata();
        assertThat(metadata.get("tenant")).isEqualTo(StateValue.string("contoso"));
        assertThat(metadata.get("orchestration.id")).isEqualTo(StateValue.string("metadata-orchestration"));
        assertThat(metadata.get("orchestration.run.id")).isEqualTo(StateValue.string("metadata-run"));
        String eventId = ((StateValue.StringValue) metadata.get("orchestration.event.id")).value();
        assertThat(result.events()).anyMatch(event -> event.eventId().equals(eventId));
        assertThat(observed).containsExactlyElementsOf(result.events());

        orchestration.close();
        agent.close();
    }

    @Test
    void chatAgents_shouldIntegrateWithOpenAiAndAzureCompatibleFakeTransports() {
        // Arrange
        CompatibleFakeChatClient openAiTransport = new CompatibleFakeChatClient("openai-compatible");
        CompatibleFakeChatClient azureTransport = new CompatibleFakeChatClient("azure-openai-compatible");
        ChatAgent openAiAgent = new ChatAgent(
                openAiTransport,
                new AgentMetadata("openai", "OpenAI", "Fake OpenAI-compatible transport"),
                ChatOptions.empty(),
                List.of());
        ChatAgent azureAgent = new ChatAgent(
                azureTransport,
                new AgentMetadata("azure", "Azure OpenAI", "Fake Azure-compatible transport"),
                ChatOptions.empty(),
                List.of());
        SequentialOrchestration orchestration = SequentialOrchestration.builder(
                        List.of(OrchestrationParticipant.of(openAiAgent), OrchestrationParticipant.of(azureAgent)))
                .historyPolicy(SequentialHistoryPolicy.PREVIOUS_RESPONSE)
                .build();

        // Act
        OrchestrationResult<AgentResponse<?>> result = orchestration.run(
                "hello",
                OrchestrationRunOptions.builder()
                        .sessionPolicy(OrchestrationSessionPolicy.STATELESS)
                        .build());

        // Assert
        assertThat(result.output().text()).contains("azure-openai-compatible");
        assertThat(openAiTransport.requests()).hasSize(1);
        assertThat(azureTransport.requests()).hasSize(1);
        assertThat(openAiTransport.requests().getFirst().runContext()).isNotNull();
        assertThat(azureTransport.requests().getFirst().runContext()).isNotNull();
        assertThat(openAiTransport.requests().getFirst().runContext().metadata())
                .containsKeys("orchestration.id", "orchestration.run.id", "orchestration.event.id");

        orchestration.close();
        openAiAgent.close();
        azureAgent.close();
    }

    @Test
    void chatAgentSessions_shouldHonorSharedAndIsolatedPolicies() {
        // Arrange
        CompatibleFakeChatClient firstTransport = new CompatibleFakeChatClient("first");
        CompatibleFakeChatClient secondTransport = new CompatibleFakeChatClient("second");
        ChatAgent firstAgent = new ChatAgent(
                firstTransport,
                new AgentMetadata("first", "First", "First session-aware agent"),
                ChatOptions.empty(),
                List.of());
        ChatAgent secondAgent = new ChatAgent(
                secondTransport,
                new AgentMetadata("second", "Second", "Second session-aware agent"),
                ChatOptions.empty(),
                List.of());
        SequentialOrchestration orchestration = SequentialOrchestration.builder(
                        List.of(OrchestrationParticipant.of(firstAgent), OrchestrationParticipant.of(secondAgent)))
                .build();

        // Act
        OrchestrationResult<AgentResponse<?>> shared = orchestration.run(
                "shared",
                OrchestrationRunOptions.builder()
                        .runId("shared-session-run")
                        .sessionPolicy(OrchestrationSessionPolicy.SHARED)
                        .build());
        String firstSharedSession =
                firstTransport.requests().getFirst().runContext().session().sessionId();
        String secondSharedSession =
                secondTransport.requests().getFirst().runContext().session().sessionId();

        OrchestrationResult<AgentResponse<?>> isolated = orchestration.run(
                "isolated",
                OrchestrationRunOptions.builder()
                        .runId("isolated-session-run")
                        .sessionPolicy(OrchestrationSessionPolicy.ISOLATED)
                        .build());
        String firstIsolatedSession =
                firstTransport.requests().getLast().runContext().session().sessionId();
        String secondIsolatedSession =
                secondTransport.requests().getLast().runContext().session().sessionId();

        // Assert
        assertThat(shared.outcome()).isEqualTo(OrchestrationOutcome.COMPLETED);
        assertThat(isolated.outcome()).isEqualTo(OrchestrationOutcome.COMPLETED);
        assertThat(firstSharedSession).isEqualTo(secondSharedSession);
        assertThat(firstIsolatedSession).isNotEqualTo(secondIsolatedSession);

        orchestration.close();
        firstAgent.close();
        secondAgent.close();
    }
}
