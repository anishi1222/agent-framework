// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class AgentExecutorTest {
    @Test
    void adapter_shouldIntegrateAgentWithoutCouplingGenericEngine() {
        // Arrange
        Agent<?> agent = new Agent<>() {
            @Override
            public AgentMetadata metadata() {
                return new AgentMetadata("agent", null, null);
            }

            @Override
            public RunHandle<AgentResponse<Object>> startRun(
                    List<Message> messages, RunOptions options, RunCancellation cancellation) {
                RunHandleSource<AgentResponse<Object>> source = new RunHandleSource<>(cancellation);
                source.tryComplete(AgentResponse.builder()
                        .messages(List.of(Message.text(
                                Role.ASSISTANT, "agent:" + messages.getLast().text())))
                        .build());
                return source.handle();
            }

            @Override
            public Flow.Publisher<AgentResponseUpdate> runStreaming(
                    List<Message> messages, RunOptions options, RunCancellation cancellation) {
                return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long count) {
                        subscriber.onComplete();
                    }

                    @Override
                    public void cancel() {}
                });
            }
        };
        WorkflowBuilder<Message, Message> builder =
                WorkflowBuilder.create("agent-adapter", Message.class, Message.class);
        WorkflowNode<Message, Message> node = builder.addNode("agent", new AgentExecutor(agent));

        // Act
        try (Workflow<Message, Message> workflow =
                builder.entry(node).output(node).build()) {
            WorkflowRunResult<Message> result = workflow.run(
                    Message.text(Role.USER, "hello"),
                    WorkflowRunOptions.builder()
                            .valueEncoder(value -> {
                                if (value instanceof Message message) {
                                    return StateValue.object(Map.of(
                                            "role",
                                            StateValue.string(message.role().value()),
                                            "text",
                                            StateValue.string(message.text())));
                                }
                                return WorkflowValueEncoder.defaultEncoder().encode(value);
                            })
                            .build());

            // Assert
            assertThat(result.output().text()).isEqualTo("agent:hello");
        }
    }
}
