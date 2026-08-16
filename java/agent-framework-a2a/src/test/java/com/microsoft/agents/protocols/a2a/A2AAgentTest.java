// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunOptions;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class A2AAgentTest {
    @Test
    void completedTask_shouldMapArtifactsAndKeepRefinementContinuation() {
        // Arrange
        A2AClient client = mock(A2AClient.class);
        Task task = task(
                TaskState.TASK_STATE_COMPLETED,
                List.of(Artifact.builder("artifact")
                        .parts(List.of(new TextPart("done")))
                        .build()));
        when(client.startSendMessage(org.mockito.ArgumentMatchers.any())).thenReturn(completed(task));
        A2AAgent agent = agent(client);

        // Act
        A2AAgentResult result = agent.runA2AAsync(
                        List.of(Message.text(com.microsoft.agents.core.Role.USER, "hello")),
                        RunOptions.empty(),
                        new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(result.outcome()).isEqualTo(A2AAgentOutcome.COMPLETED);
        assertThat(result.response().text()).isEqualTo("done");
        assertThat(result.continuation())
                .isEqualTo(new A2AContinuation("task-1", "context-1", TaskState.TASK_STATE_COMPLETED));
        assertThat(result.response().continuationToken())
                .isEqualTo(result.continuation().toStateValue());
    }

    @Test
    void interruptedTask_shouldSurfaceExplicitInputAndAuthOutcomes() {
        // Arrange
        A2AClient inputClient = mock(A2AClient.class);
        when(inputClient.startSendMessage(org.mockito.ArgumentMatchers.any()))
                .thenReturn(completed(task(TaskState.TASK_STATE_INPUT_REQUIRED, List.of())));
        A2AClient authClient = mock(A2AClient.class);
        when(authClient.startSendMessage(org.mockito.ArgumentMatchers.any()))
                .thenReturn(completed(task(TaskState.TASK_STATE_AUTH_REQUIRED, List.of())));

        // Act
        A2AAgentResult input = run(agent(inputClient));
        A2AAgentResult auth = run(agent(authClient));

        // Assert
        assertThat(input.outcome()).isEqualTo(A2AAgentOutcome.INPUT_REQUIRED);
        assertThat(auth.outcome()).isEqualTo(A2AAgentOutcome.AUTH_REQUIRED);
        assertThat(input.continuation()).isNotNull();
        assertThat(auth.continuation()).isNotNull();
    }

    @Test
    void failedTask_shouldNeverBecomeSuccessfulAgentResponse() {
        // Arrange
        A2AClient client = mock(A2AClient.class);
        when(client.startSendMessage(org.mockito.ArgumentMatchers.any()))
                .thenReturn(completed(task(TaskState.TASK_STATE_FAILED, List.of())));

        // Act / Assert
        assertThatThrownBy(() -> run(agent(client))).hasRootCauseInstanceOf(A2ARemoteTaskException.class);
    }

    @Test
    void completedContinuation_shouldCreateReferenceTaskRefinement() {
        // Arrange
        A2AClient client = mock(A2AClient.class);
        when(client.startSendMessage(org.mockito.ArgumentMatchers.any()))
                .thenReturn(completed(task(TaskState.TASK_STATE_COMPLETED, List.of())));
        A2AContinuation continuation = new A2AContinuation("prior-task", "context-1", TaskState.TASK_STATE_COMPLETED);
        RunOptions options = A2AAgentOptions.withContinuation(RunOptions.empty(), continuation);

        // Act
        agent(client)
                .runA2AAsync(
                        List.of(Message.text(com.microsoft.agents.core.Role.USER, "refine")),
                        options,
                        new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(client).startSendMessage(captor.capture());
        assertThat(captor.getValue().message().taskId()).isNull();
        assertThat(captor.getValue().message().contextId()).isEqualTo("context-1");
        assertThat(captor.getValue().message().referenceTaskIds()).containsExactly("prior-task");
    }

    @Test
    void workingContinuation_shouldPollWithoutAcceptingDuplicateInput() {
        // Arrange
        A2AClient client = mock(A2AClient.class);
        A2AContinuation continuation = new A2AContinuation("task-1", "context-1", TaskState.TASK_STATE_WORKING);
        RunOptions options = A2AAgentOptions.withContinuation(RunOptions.empty(), continuation);
        when(client.startGetTask(new A2ARequests.GetTask("task-1")))
                .thenReturn(completed(task(TaskState.TASK_STATE_WORKING, List.of())));

        // Act
        A2AAgentResult result = agent(client)
                .runA2AAsync(List.of(), options, new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(result.outcome()).isEqualTo(A2AAgentOutcome.WORKING);
        assertThatThrownBy(() -> agent(client)
                        .runA2AAsync(
                                List.of(Message.text(com.microsoft.agents.core.Role.USER, "duplicate")),
                                options,
                                new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .hasRootCauseInstanceOf(A2AConversionException.class);
    }

    private static A2AAgentResult run(A2AAgent agent) {
        return agent.runA2AAsync(
                        List.of(Message.text(com.microsoft.agents.core.Role.USER, "hello")),
                        RunOptions.empty(),
                        new DefaultRunCancellation())
                .toCompletableFuture()
                .join();
    }

    private static A2AAgent agent(A2AClient client) {
        return new A2AAgent(
                client,
                A2AAgentOptions.builder(new AgentMetadata("remote", "Remote", "Remote agent"))
                        .build());
    }

    private static Task task(TaskState state, List<Artifact> artifacts) {
        return Task.builder("task-1", "context-1", new TaskStatus(state, Instant.parse("2026-08-08T00:00:00Z")))
                .artifacts(artifacts)
                .build();
    }

    private static <T> RunHandle<T> completed(T value) {
        RunHandleSource<T> source = new RunHandleSource<>();
        source.tryComplete(value);
        return source.handle();
    }
}
