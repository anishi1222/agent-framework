// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.protocols.a2a.A2ALimits;
import com.microsoft.agents.protocols.a2a.AgentCapabilities;
import com.microsoft.agents.protocols.a2a.AgentCard;
import com.microsoft.agents.protocols.a2a.AgentInterface;
import com.microsoft.agents.protocols.a2a.AgentSkill;
import com.microsoft.agents.protocols.a2a.Role;
import com.microsoft.agents.protocols.a2a.SendMessageRequest;
import com.microsoft.agents.protocols.a2a.Task;
import com.microsoft.agents.protocols.a2a.TaskState;
import com.microsoft.agents.protocols.a2a.TextPart;
import com.microsoft.agents.workflows.FunctionExecutor;
import com.microsoft.agents.workflows.Workflow;
import com.microsoft.agents.workflows.WorkflowBuilder;
import com.microsoft.agents.workflows.WorkflowNode;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class A2AAdaptersTest {
    @Test
    void agentExecutor_shouldMapFrameworkResponseWithoutReplayingUserHistory() {
        // Arrange
        AgentResponse<Void> response = AgentResponse.<Void>builder()
                .messages(List.of(
                        Message.text(com.microsoft.agents.core.Role.USER, "not-output"),
                        Message.text(com.microsoft.agents.core.Role.ASSISTANT, "answer")))
                .build();
        A2AAgentExecutor executor =
                new A2AAgentExecutor(new StubAgent(response, null), List.of("text/plain"), A2ALimits.defaults());

        // Act
        Task task = run(executor, "question");

        // Assert
        assertThat(task.status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(task.artifacts())
                .singleElement()
                .satisfies(artifact -> assertThat(artifact.parts())
                        .singleElement()
                        .satisfies(part -> assertThat(((TextPart) part).text()).isEqualTo("answer")));
    }

    @Test
    void agentExecutor_shouldMapAuthenticationBoundaryHonestly() {
        // Arrange
        A2AAgentExecutor executor = new A2AAgentExecutor(
                new StubAgent(
                        null,
                        new A2AAuthRequiredException(
                                "Sign in to continue.", Map.of("scheme", StateValue.string("oauth2")))),
                List.of("text/plain"),
                A2ALimits.defaults());

        // Act
        Task task = run(executor, "question");

        // Assert
        assertThat(task.status().state()).isEqualTo(TaskState.TASK_STATE_AUTH_REQUIRED);
        assertThat(((TextPart) task.status().message().parts().getFirst()).text())
                .isEqualTo("Sign in to continue.");
    }

    @Test
    void workflowExecutor_shouldMapTypedStringInputAndOutput() {
        // Arrange
        WorkflowBuilder<String, String> builder = WorkflowBuilder.create("uppercase", String.class, String.class);
        WorkflowNode<String, String> node = builder.addNode(
                "uppercase",
                FunctionExecutor.sync(
                        String.class, String.class, (value, context) -> value.toUpperCase(java.util.Locale.ROOT)));
        try (Workflow<String, String> workflow =
                        builder.entry(node).output(node).build();
                A2AService service = A2AService.builder(
                                card(),
                                new A2AWorkflowExecutor<>(workflow, List.of("text/plain"), A2ALimits.defaults()))
                        .build()) {

            // Act
            Task task = (Task) service.sendMessageAsync(A2APrincipal.loopbackAnonymous(), request("hello"))
                    .toCompletableFuture()
                    .join();

            // Assert
            assertThat(task.status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
            assertThat(((TextPart) task.artifacts().getFirst().parts().getFirst()).text())
                    .isEqualTo("HELLO");
        }
    }

    private static Task run(A2AExecutor executor, String input) {
        try (A2AService service = A2AService.builder(card(), executor).build()) {
            return (Task) service.sendMessageAsync(A2APrincipal.loopbackAnonymous(), request(input))
                    .toCompletableFuture()
                    .join();
        }
    }

    private static SendMessageRequest request(String text) {
        return new SendMessageRequest(com.microsoft.agents.protocols.a2a.Message.builder(Role.ROLE_USER)
                .messageId("message-" + text)
                .parts(List.of(new TextPart(text)))
                .build());
    }

    private static AgentCard card() {
        return AgentCard.builder("adapter", "adapter", "1.0")
                .capabilities(AgentCapabilities.builder().streaming(true).build())
                .skills(List.of(AgentSkill.builder("adapter", "Adapter", "Adapter")
                        .tags(List.of("test"))
                        .build()))
                .supportedInterfaces(List.of(AgentInterface.jsonRpc(URI.create("http://127.0.0.1:1/a2a"))))
                .build();
    }

    private static final class StubAgent implements Agent<Void> {
        private final AgentResponse<Void> response;

        private final RuntimeException failure;

        private StubAgent(AgentResponse<Void> response, RuntimeException failure) {
            this.response = response;
            this.failure = failure;
        }

        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata("stub", "Stub", "Stub agent");
        }

        @Override
        public RunHandle<AgentResponse<Void>> startRun(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            RunHandleSource<AgentResponse<Void>> source = new RunHandleSource<>(cancellation);
            if (failure == null) {
                source.tryComplete(response);
            } else {
                source.tryFail(failure);
            }
            return source.handle();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            return subscriber -> {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long count) {
                        subscriber.onComplete();
                    }

                    @Override
                    public void cancel() {}
                });
            };
        }
    }
}
