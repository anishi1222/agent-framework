// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.hosting.HostingRegistry;
import com.microsoft.agents.hosting.HostingRouteKind;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class OpenAIResponsesHostingRegistryTest {
    @Test
    void duplicatePath_shouldNotLeaveAnUnreachableGenericRegistration() {
        // Arrange
        HostingRegistry generic = new HostingRegistry();
        OpenAIResponsesHostingRegistry responses = new OpenAIResponsesHostingRegistry(generic);
        responses.registerAgent("/v1/responses", new NoopAgent("first"));

        // Act
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> responses.registerAgent("/v1/responses", new NoopAgent("orphan")));

        // Assert
        assertThat(failure).hasMessageContaining("already registered");
        assertThat(generic.find(HostingRouteKind.AGENT, "orphan")).isEmpty();
    }

    private record NoopAgent(String id) implements Agent<Void> {
        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata(id, id, "Registry test agent");
        }

        @Override
        public RunHandle<AgentResponse<Void>> startRun(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            return new RunHandleSource<AgentResponse<Void>>(cancellation).handle();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {}

                @Override
                public void cancel() {}
            });
        }
    }
}
