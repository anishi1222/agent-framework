// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.spring;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.hosting.HostingAuthentication;
import com.microsoft.agents.hosting.HostingAuthenticator;
import com.microsoft.agents.hosting.HostingAuthorizationAction;
import com.microsoft.agents.hosting.HostingAuthorizationDecision;
import com.microsoft.agents.hosting.HostingAuthorizer;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingPrincipal;
import com.microsoft.agents.hosting.HostingRegistry;
import com.microsoft.agents.hosting.HostingRouteDescriptor;
import com.microsoft.agents.hosting.HostingWorkflowCodecs;
import com.microsoft.agents.workflows.FunctionExecutor;
import com.microsoft.agents.workflows.Workflow;
import com.microsoft.agents.workflows.WorkflowBuilder;
import com.microsoft.agents.workflows.WorkflowNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.reactive.ReactiveWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

@SpringBootTest(
        classes = AgentFrameworkHostingRuntimeTest.TestApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "agent-framework.hosting.enabled=true",
            "server.address=127.0.0.1",
            "spring.webflux.base-path=/api",
            "spring.main.web-application-type=reactive"
        })
class AgentFrameworkHostingRuntimeTest {
    @LocalServerPort
    private int port;

    @Autowired
    private RuntimeChatClient runtimeChatClient;

    @Autowired
    private RecordingAuthorizer recordingAuthorizer;

    @Autowired
    private ReactiveWebServerFactory reactiveWebServerFactory;

    @Test
    void runtime_shouldUseNettyReactiveWebServerFactory() {
        assertThat(reactiveWebServerFactory).isInstanceOf(NettyReactiveWebServerFactory.class);
    }

    @Test
    void runtime_shouldServeAgentWorkflowAndGradualSseOverRandomPort() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> agent = client.send(
                    jsonRequest("/v1/agents/spring-agent/runs", agentBody(), "application/json"),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> workflow = client.send(
                    jsonRequest("/v1/workflows/spring-workflow/runs", workflowBody(), "application/json"),
                    HttpResponse.BodyHandlers.ofString());
            long startedAt = System.nanoTime();
            HttpResponse<java.io.InputStream> stream = client.send(
                    jsonRequest("/v1/agents/spring-agent/runs/stream", agentBody(), "text/event-stream"),
                    HttpResponse.BodyHandlers.ofInputStream());
            List<String> events = new ArrayList<>();
            List<Long> elapsedMillis = new ArrayList<>();
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        events.add(line.substring("event:".length()).trim());
                        elapsedMillis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                    }
                }
            }

            assertThat(agent.statusCode()).isEqualTo(200);
            assertThat(agent.body()).contains("\"status\":\"completed\"", "spring: hello");
            assertThat(workflow.statusCode()).isEqualTo(200);
            assertThat(workflow.body()).contains("\"status\":\"completed\"", "hello-processed");
            assertThat(stream.statusCode()).isEqualTo(200);
            assertThat(events).containsExactly("run-started", "agent-update", "agent-update", "terminal");
            assertThat(elapsedMillis.get(2) - elapsedMillis.get(1)).isGreaterThanOrEqualTo(25L);
            assertThat(recordingAuthorizer.principals()).contains("spring-owner");
        }
    }

    @Test
    void runtime_shouldExposeJsonUpgradeErrorInsteadOfFalseSpringWebSocket() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(uri("/v1/ws"))
                            .header("Accept", "application/json")
                            .header("Authorization", "Bearer spring-owner")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(426);
            assertThat(response.headers().firstValue("upgrade")).contains("websocket");
            assertThat(response.body()).contains("\"code\":\"upgrade_required\"");
        }
    }

    @Test
    void runtime_shouldUseApplicationPrincipalResolverAndRejectMissingCredentials() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> missing = client.send(
                    HttpRequest.newBuilder(uri("/v1/agents"))
                            .header("Accept", "application/json")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> authenticated = client.send(
                    HttpRequest.newBuilder(uri("/v1/agents"))
                            .header("Accept", "application/json")
                            .header("Authorization", "Bearer alternate-owner")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(missing.statusCode()).isEqualTo(401);
            assertThat(missing.body()).contains("\"code\":\"unauthenticated\"");
            assertThat(authenticated.statusCode()).isEqualTo(200);
            assertThat(recordingAuthorizer.principals()).contains("alternate-owner");
        }
    }

    @Test
    void runtime_shouldPropagateSpringSseDisconnectCancellation() throws Exception {
        List<String> first = WebClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .defaultHeader("Authorization", "Bearer spring-owner")
                .clientConnector(new ReactorClientHttpConnector(
                        reactor.netty.http.client.HttpClient.create().keepAlive(false)))
                .build()
                .post()
                .uri("/api/v1/agents/spring-agent/runs/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(agentBody("pending"))
                .retrieve()
                .bodyToFlux(String.class)
                .take(2)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(first)
                .hasSize(2)
                .anySatisfy(frame -> assertThat(frame).contains("\"event\":\"run-started\""))
                .anySatisfy(frame -> assertThat(frame).contains("pending-start"));
        assertThat(runtimeChatClient.cancelled.orTimeout(5, TimeUnit.SECONDS).join())
                .isTrue();
    }

    private HttpRequest jsonRequest(String path, String body, String accept) {
        return HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .header("Authorization", "Bearer spring-owner")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + "/api" + path);
    }

    private static String agentBody() {
        return agentBody("hello");
    }

    private static String agentBody(String text) {
        return """
                {
                  "version":"java-hosting-2026-08-01",
                  "messages":[
                    {"role":"user","contents":[{"kind":"text","text":"%s"}]}
                  ]
                }
                """.formatted(text);
    }

    private static String workflowBody() {
        return """
                {
                  "version":"java-hosting-2026-08-01",
                  "input":"hello"
                }
                """;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
        @Bean
        RuntimeChatClient runtimeChatClient() {
            return new RuntimeChatClient();
        }

        @Bean
        HostingAuthenticator hostingAuthenticator() {
            return request -> {
                String authorization = request.firstHeader("authorization");
                if (authorization == null || !authorization.startsWith("Bearer ")) {
                    return CompletableFuture.completedFuture(HostingAuthentication.unauthenticated());
                }
                return CompletableFuture.completedFuture(HostingAuthentication.authenticated(
                        new HostingPrincipal(authorization.substring("Bearer ".length()), "spring-tenant")));
            };
        }

        @Bean
        RecordingAuthorizer recordingAuthorizer() {
            return new RecordingAuthorizer();
        }

        @Bean
        HostingLimits hostingLimits() {
            return HostingLimits.builder().idleTimeout(Duration.ofSeconds(10)).build();
        }

        @Bean(destroyMethod = "close")
        ChatAgent springAgent(RuntimeChatClient client) {
            return new ChatAgent(
                    client,
                    new AgentMetadata("spring-agent", "Spring agent", "Random-port Spring hosting test agent"),
                    ChatOptions.empty(),
                    List.of());
        }

        @Bean(destroyMethod = "close")
        Workflow<String, String> springWorkflow() {
            WorkflowBuilder<String, String> builder =
                    WorkflowBuilder.create("spring-workflow", String.class, String.class);
            WorkflowNode<String, String> node = builder.addNode(
                    "process",
                    FunctionExecutor.sync(String.class, String.class, (value, context) -> value + "-processed"));
            return builder.entry(node).output(node).build();
        }

        @Bean
        HostingRegistry hostingRegistry(ChatAgent springAgent, Workflow<String, String> springWorkflow) {
            HostingRegistry registry = new HostingRegistry();
            registry.registerAgent(springAgent);
            registry.registerWorkflow(springWorkflow, HostingWorkflowCodecs.text());
            return registry;
        }
    }

    static final class RuntimeChatClient implements ChatClient {
        private final CompletableFuture<Boolean> cancelled = new CompletableFuture<>();

        @Override
        public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
            return CompletableFuture.completedFuture(ChatResponse.builder()
                    .messages(List.of(Message.text(
                            Role.ASSISTANT,
                            "spring: " + request.messages().getLast().text())))
                    .finishReason(FinishReason.STOP)
                    .build());
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                ChatClientRequest request, RunCancellation cancellation) {
            if ("pending".equals(request.messages().getLast().text())) {
                return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    private final AtomicBoolean done = new AtomicBoolean();

                    private final AtomicBoolean emitted = new AtomicBoolean();

                    @Override
                    public void request(long count) {
                        if (count > 0 && emitted.compareAndSet(false, true)) {
                            subscriber.onNext(update(0, "pending-start", null));
                        }
                    }

                    @Override
                    public void cancel() {
                        if (done.compareAndSet(false, true)) {
                            cancelled.complete(true);
                        }
                    }
                });
            }
            List<ChatResponseUpdate> updates =
                    List.of(update(0, "first", null), update(1, "second", FinishReason.STOP));
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicInteger index = new AtomicInteger();

                private final AtomicBoolean done = new AtomicBoolean();

                @Override
                public synchronized void request(long count) {
                    long remaining = count;
                    while (remaining-- > 0 && !done.get()) {
                        int next = index.getAndIncrement();
                        if (next >= updates.size()) {
                            if (done.compareAndSet(false, true)) {
                                subscriber.onComplete();
                            }
                            return;
                        }
                        if (next == 1) {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        subscriber.onNext(updates.get(next));
                        if (next == updates.size() - 1 && done.compareAndSet(false, true)) {
                            subscriber.onComplete();
                        }
                    }
                }

                @Override
                public void cancel() {
                    done.set(true);
                }
            });
        }

        private static ChatResponseUpdate update(long sequence, String text, FinishReason finishReason) {
            ChatResponseUpdate.Builder builder = ChatResponseUpdate.builder()
                    .sequence(sequence)
                    .role(Role.ASSISTANT)
                    .contents(List.of(new TextContent(text)));
            if (finishReason != null) {
                builder.finishReason(finishReason);
            }
            return builder.build();
        }
    }

    static final class RecordingAuthorizer implements HostingAuthorizer {
        private final ConcurrentLinkedQueue<String> principals = new ConcurrentLinkedQueue<>();

        @Override
        public CompletionStage<HostingAuthorizationDecision> authorizeAsync(
                com.microsoft.agents.hosting.HostingRequestContext context,
                HostingRouteDescriptor descriptor,
                HostingAuthorizationAction action) {
            principals.add(context.principalId());
            return CompletableFuture.completedFuture(HostingAuthorizationDecision.allow());
        }

        private List<String> principals() {
            return List.copyOf(principals);
        }
    }
}
