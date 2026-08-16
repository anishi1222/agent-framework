// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StructuredOutputDecoder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DelegatingAgentTest {
    @Test
    void delegatingAgent_shouldForwardMetadataFiniteStreamingAndOwnership() {
        // Arrange
        StubAgent inner = new StubAgent();
        DelegatingAgent<StateValue> nonOwning = new DelegatingAgent<>(inner) {};
        DelegatingAgent<StateValue> owning = new DelegatingAgent<>(inner, true) {};
        List<Message> messages = List.of(Message.text(Role.USER, "hello"));
        RunOptions options = RunOptions.empty();
        RunCancellation cancellation = new DefaultRunCancellation();

        // Act
        RunHandle<AgentResponse<StateValue>> handle = nonOwning.startRun(messages, options, cancellation);
        Flow.Publisher<AgentResponseUpdate> publisher = nonOwning.runStreaming(messages, options, cancellation);
        nonOwning.close();

        // Assert
        assertThat(nonOwning.metadata()).isSameAs(inner.metadata());
        assertThat(handle).isSameAs(inner.handle);
        assertThat(publisher).isSameAs(inner.publisher);
        assertThat(inner.closed).isFalse();

        // Act
        owning.close();

        // Assert
        assertThat(inner.closed).isTrue();
    }

    @Test
    void structuredOutputAgent_shouldDecodeFiniteValueAndForwardStreaming() {
        // Arrange
        StubAgent inner = new StubAgent();
        StructuredOutputAgent<StateValue> agent =
                new StructuredOutputAgent<>(inner, StructuredOutputDecoder.stateValue());

        // Act
        AgentResponse<StateValue> response = agent.run("hello");
        Flow.Publisher<AgentResponseUpdate> publisher = agent.runStreaming("hello");

        // Assert
        assertThat(response.value()).isEqualTo(StateValue.object(Map.of("answer", StateValue.integer(42))));
        assertThat(response.responseId()).isEqualTo("response-1");
        assertThat(publisher).isSameAs(inner.publisher);
    }

    private static final class StubAgent implements Agent<StateValue> {
        private final AgentMetadata metadata = new AgentMetadata("delegate-1", "delegate", "test");

        private final AtomicBoolean closed = new AtomicBoolean();

        private final Flow.Publisher<AgentResponseUpdate> publisher =
                subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long count) {
                        subscriber.onComplete();
                    }

                    @Override
                    public void cancel() {}
                });

        private final RunHandle<AgentResponse<StateValue>> handle = new RunHandle<>() {
            private final RunCancellation cancellation = new DefaultRunCancellation();

            @Override
            public CompletableFuture<AgentResponse<StateValue>> resultAsync() {
                return CompletableFuture.completedFuture(AgentResponse.<StateValue>builder()
                        .messages(List.of(Message.text(Role.ASSISTANT, "{\"answer\":42}")))
                        .responseId("response-1")
                        .finishReason(FinishReason.STOP)
                        .build());
            }

            @Override
            public RunCancellation cancellation() {
                return cancellation;
            }
        };

        @Override
        public AgentMetadata metadata() {
            return metadata;
        }

        @Override
        public RunHandle<AgentResponse<StateValue>> startRun(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            return handle;
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            return publisher;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
