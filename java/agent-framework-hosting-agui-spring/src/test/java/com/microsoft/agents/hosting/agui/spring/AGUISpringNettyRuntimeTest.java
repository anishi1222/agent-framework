// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.hosting.agui.AGUIHostingRegistry;
import com.microsoft.agents.protocols.agui.AGUIClient;
import com.microsoft.agents.protocols.agui.AGUIClientOptions;
import com.microsoft.agents.protocols.agui.AGUIEvent;
import com.microsoft.agents.protocols.agui.AGUIEventType;
import com.microsoft.agents.protocols.agui.AGUIMessages;
import com.microsoft.agents.protocols.agui.RunAgentInput;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.server.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

class AGUISpringNettyRuntimeTest {
    @Test
    void randomPortNetty_shouldServeProductionHandlerWithoutTomcat() throws Exception {
        // Arrange
        SpringApplication application = new SpringApplication(TestApplication.class);
        application.setWebApplicationType(WebApplicationType.REACTIVE);
        application.setDefaultProperties(Map.of(
                "server.port",
                "0",
                "agent-framework.hosting.enabled",
                "true",
                "agent-framework.hosting.agui.enabled",
                "true"));

        try (ConfigurableApplicationContext context = application.run()) {
            int port = ((ReactiveWebServerApplicationContext) context)
                    .getWebServer()
                    .getPort();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/ag-ui");
            try (AGUIClient client = new AGUIClient(
                    AGUIClientOptions.builder(endpoint).allowInsecureLoopback().build())) {
                // Act
                List<AGUIEvent> events =
                        client.runAsync(input()).toCompletableFuture().get(5, TimeUnit.SECONDS);

                // Assert
                assertThat(events)
                        .extracting(AGUIEvent::type)
                        .startsWith(AGUIEventType.RUN_STARTED)
                        .contains(AGUIEventType.TEXT_MESSAGE_CONTENT)
                        .endsWith(AGUIEventType.RUN_FINISHED);
                assertThatThrownBy(() -> Class.forName("org.apache.catalina.startup.Tomcat"))
                        .isInstanceOf(ClassNotFoundException.class);
            }
        }
    }

    private static RunAgentInput input() {
        return new RunAgentInput(
                "spring-thread",
                "spring-run",
                StateValue.object(Map.of()),
                List.of(new AGUIMessages.User("user", new AGUIMessages.TextUserContent("hello"), null, null)),
                List.of(),
                List.of(),
                StateValue.object(Map.of()));
    }

    @SpringBootApplication
    @EnableAutoConfiguration
    static class TestApplication {
        @Bean(destroyMethod = "close")
        ChatAgent springTestAgent() {
            return new ChatAgent(
                    new OneShotChatClient(),
                    new AgentMetadata("spring-agent", "Spring agent", null),
                    ChatOptions.empty(),
                    List.of());
        }

        @Bean
        SmartInitializingSingleton registerAGUIRoute(AGUIHostingRegistry registry, ChatAgent springTestAgent) {
            return () -> registry.registerAgent(AGUIHostingRegistry.DEFAULT_PATH, springTestAgent);
        }
    }

    private static final class OneShotChatClient implements ChatClient {
        @Override
        public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
            return CompletableFuture.completedFuture(ChatResponse.builder()
                    .messages(List.of(Message.text(Role.ASSISTANT, "spring")))
                    .finishReason(FinishReason.STOP)
                    .build());
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                ChatClientRequest request, RunCancellation cancellation) {
            ChatResponseUpdate update = ChatResponseUpdate.builder()
                    .sequence(0)
                    .messageId("assistant")
                    .role(Role.ASSISTANT)
                    .contents(List.of(new TextContent("spring")))
                    .finishReason(FinishReason.STOP)
                    .build();
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean done;

                @Override
                public void request(long count) {
                    if (!done && count > 0) {
                        done = true;
                        subscriber.onNext(update);
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    done = true;
                }
            });
        }
    }
}
