// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.protocols.a2a.A2AClient;
import com.microsoft.agents.protocols.a2a.A2AClientOptions;
import com.microsoft.agents.protocols.a2a.A2ACursorPage;
import com.microsoft.agents.protocols.a2a.A2AJsonCodec;
import com.microsoft.agents.protocols.a2a.A2ALimits;
import com.microsoft.agents.protocols.a2a.A2ARequests;
import com.microsoft.agents.protocols.a2a.A2AStreamEvent;
import com.microsoft.agents.protocols.a2a.AgentCapabilities;
import com.microsoft.agents.protocols.a2a.AgentCard;
import com.microsoft.agents.protocols.a2a.AgentInterface;
import com.microsoft.agents.protocols.a2a.AgentSkill;
import com.microsoft.agents.protocols.a2a.Artifact;
import com.microsoft.agents.protocols.a2a.Message;
import com.microsoft.agents.protocols.a2a.PushNotificationConfig;
import com.microsoft.agents.protocols.a2a.Role;
import com.microsoft.agents.protocols.a2a.SendMessageConfiguration;
import com.microsoft.agents.protocols.a2a.SendMessageRequest;
import com.microsoft.agents.protocols.a2a.Task;
import com.microsoft.agents.protocols.a2a.TaskArtifactUpdateEvent;
import com.microsoft.agents.protocols.a2a.TaskState;
import com.microsoft.agents.protocols.a2a.TaskStatusUpdateEvent;
import com.microsoft.agents.protocols.a2a.TextPart;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class A2AHttpIntegrationTest {
    private static final String AUTHORIZATION = "Bearer integration-test";

    @Test
    void clientAndHost_shouldInteroperateAcrossCardsFiniteTasksPaginationAndPushCrud() {
        // Arrange
        try (Fixture fixture = fixture(A2ALimits.defaults());
                A2AClient publicClient = client(fixture.server().endpoint(), false);
                A2AClient client = client(fixture.server().endpoint(), true)) {
            SendMessageRequest request = request("hello", "message-1");

            // Act
            AgentCard publicCard =
                    publicClient.fetchAgentCardAsync().toCompletableFuture().join();
            AgentCard extended = client.fetchExtendedAgentCardAsync(new A2ARequests.GetExtendedAgentCard())
                    .toCompletableFuture()
                    .join();
            Task task = (Task)
                    client.sendMessageAsync(request).toCompletableFuture().join();
            Task fetched = client.getTaskAsync(new A2ARequests.GetTask(task.id()))
                    .toCompletableFuture()
                    .join();
            A2ACursorPage<Task> listed = client.listTasksAsync(new A2ARequests.ListTasks())
                    .toCompletableFuture()
                    .join();
            PushNotificationConfig config = new PushNotificationConfig(
                    "push-1", task.id(), URI.create("https://callback.test/events"), "redacted", null, null);
            client.createPushNotificationConfigAsync(config)
                    .toCompletableFuture()
                    .join();

            // Assert
            assertThat(publicCard.supportedInterfaces().getFirst().url())
                    .isEqualTo(fixture.server().endpoint());
            assertThat(extended.name()).isEqualTo("extended");
            assertThat(task.status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
            assertThat(fetched.id()).isEqualTo(task.id());
            assertThat(fetched.status()).isEqualTo(task.status());
            assertThat(fetched.history()).hasSize(1);
            assertThat(listed.items()).extracting(Task::id).contains(task.id());
            assertThat(client.getPushNotificationConfigAsync(
                                    new A2ARequests.GetPushConfig(task.id(), config.id(), null))
                            .toCompletableFuture()
                            .join())
                    .isEqualTo(config);
            assertThat(client.listAllPushNotificationConfigsAsync(new A2ARequests.ListPushConfigs(task.id()))
                            .toCompletableFuture()
                            .join())
                    .containsExactly(config);
            assertThat(client.deletePushNotificationConfigAsync(
                                    new A2ARequests.DeletePushConfig(task.id(), config.id(), null))
                            .toCompletableFuture()
                            .join())
                    .isTrue();
        }
    }

    @Test
    void realSse_shouldDeliverTaskFirstArtifactChunksAndTerminalStatus() throws Exception {
        // Arrange
        try (Fixture fixture = fixture(A2ALimits.defaults());
                A2AClient client = client(fixture.server().endpoint(), true)) {
            RecordingSubscriber subscriber = new RecordingSubscriber();

            // Act
            client.sendMessageStreaming(request("stream", "message-stream")).subscribe(subscriber);
            subscriber.completed().get(10, TimeUnit.SECONDS);

            // Assert
            assertThat(subscriber.events()).isNotEmpty();
            assertThat(subscriber.events().getFirst()).isInstanceOf(Task.class);
            assertThat(subscriber.events())
                    .anyMatch(TaskArtifactUpdateEvent.class::isInstance)
                    .anyMatch(event -> event instanceof TaskStatusUpdateEvent update
                            && update.status().state() == TaskState.TASK_STATE_COMPLETED);
        }
    }

    @Test
    void authenticationMediaOriginAndMalformedPayloadAttacks_shouldFailClosed() throws Exception {
        // Arrange
        try (Fixture fixture = fixture(A2ALimits.defaults())) {
            HttpClient raw = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            String valid = rpc(
                    "SendMessage",
                    "1",
                    new A2AJsonCodec(A2ALimits.defaults()).sendMessageRequestToValue(request("hello", "message-1")));

            // Act
            HttpResponse<String> unauthenticated = raw.send(
                    rawRequest(fixture.server().endpoint(), valid, "application/json", null, null),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> badMedia = raw.send(
                    rawRequest(fixture.server().endpoint(), valid, "application/a2a+json", AUTHORIZATION, null),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> badOrigin = raw.send(
                    rawRequest(
                            fixture.server().endpoint(), valid, "application/json", AUTHORIZATION, "https://evil.test"),
                    HttpResponse.BodyHandlers.ofString());
            String duplicate =
                    "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"GetTask\",\"params\":{\"id\":\"a\",\"id\":\"b\"}}";
            HttpResponse<String> malformed = raw.send(
                    rawRequest(fixture.server().endpoint(), duplicate, "application/json", AUTHORIZATION, null),
                    HttpResponse.BodyHandlers.ofString());

            // Assert
            assertThat(unauthenticated.statusCode()).isEqualTo(401);
            assertThat(badMedia.statusCode()).isEqualTo(415);
            assertThat(badOrigin.statusCode()).isEqualTo(403);
            assertThat(malformed.statusCode()).isEqualTo(200);
            assertThat(malformed.body()).contains("\"code\":-32700");
            raw.close();
        }
    }

    @Test
    void oversizedRequestAndWrongVersion_shouldReturnTypedErrors() throws Exception {
        // Arrange
        A2ALimits limits = new A2ALimits(256, 4096, 32, 1024, 100, 1024, 8, 8);
        try (Fixture fixture = fixture(limits)) {
            HttpClient raw = HttpClient.newHttpClient();
            String oversized = "x".repeat(300);
            HttpRequest tooLarge =
                    rawRequest(fixture.server().endpoint(), oversized, "application/json", AUTHORIZATION, null);
            String request = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"GetTask\",\"params\":{\"id\":\"task\"}}";
            HttpRequest wrongVersion = HttpRequest.newBuilder(fixture.server().endpoint())
                    .header("Content-Type", "application/json")
                    .header("Authorization", AUTHORIZATION)
                    .header("A2A-Version", "0.3")
                    .POST(HttpRequest.BodyPublishers.ofString(request))
                    .build();

            // Act
            HttpResponse<String> large = raw.send(tooLarge, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> version = raw.send(wrongVersion, HttpResponse.BodyHandlers.ofString());

            // Assert
            assertThat(large.body()).contains("\"code\":-32600");
            assertThat(version.body()).contains("\"code\":-32009");
            raw.close();
        }
    }

    @Test
    void unauthenticatedClient_shouldNotReceiveProtocolErrorsAsSuccess() {
        // Arrange
        try (Fixture fixture = fixture(A2ALimits.defaults());
                A2AClient client = client(fixture.server().endpoint(), false)) {

            // Act / Assert
            assertThatThrownBy(() -> client.sendMessageAsync(request("hello", "message-1"))
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(com.microsoft.agents.protocols.a2a.A2ATransportException.class);
        }
    }

    @Test
    void clientCancelAndResubscribe_shouldUseCoreOperationsAndCurrentTaskFirst() throws Exception {
        // Arrange
        WaitingExecutor executor = new WaitingExecutor();
        try (Fixture fixture = fixture(A2ALimits.defaults(), executor);
                A2AClient client = client(fixture.server().endpoint(), true)) {
            SendMessageRequest background = new SendMessageRequest(
                    Message.builder(Role.ROLE_USER)
                            .messageId("message-cancel")
                            .parts(List.of(new TextPart("wait")))
                            .build(),
                    new SendMessageConfiguration(List.of("text/plain"), 0, null, true),
                    Map.of(),
                    null);
            Task task = (Task)
                    client.sendMessageAsync(background).toCompletableFuture().join();
            executor.started().orTimeout(5, TimeUnit.SECONDS).join();

            RecordingSubscriber subscriber = new RecordingSubscriber();
            client.subscribeToTaskStreaming(new A2ARequests.SubscribeToTask(task.id()))
                    .subscribe(subscriber);
            // Act
            Task canceled = client.cancelTaskAsync(new A2ARequests.CancelTask(task.id()))
                    .toCompletableFuture()
                    .join();
            subscriber.completed().get(5, TimeUnit.SECONDS);

            // Assert
            assertThat(canceled.status().state()).isEqualTo(TaskState.TASK_STATE_CANCELED);
            assertThat(subscriber.events().getFirst()).isInstanceOf(Task.class);
            assertThat(subscriber.events())
                    .anyMatch(event -> event instanceof TaskStatusUpdateEvent update
                            && update.status().state() == TaskState.TASK_STATE_CANCELED);
            assertThat(executor.canceled().orTimeout(5, TimeUnit.SECONDS).join())
                    .isTrue();
        }
    }

    private static Fixture fixture(A2ALimits limits) {
        return fixture(limits, new ChunkingEchoExecutor());
    }

    private static Fixture fixture(A2ALimits limits, A2AExecutor executor) {
        AgentCard publicCard = card("public", true, true);
        AgentCard extendedCard = card("extended", true, false);
        A2AService service = A2AService.builder(publicCard, executor)
                .extendedCard(extendedCard)
                .taskStore(new InMemoryA2ATaskStore(100))
                .pushStore(new InMemoryA2APushNotificationConfigStore(100))
                .build();
        A2AHttpServerOptions options = A2AHttpServerOptions.builder()
                .limits(limits)
                .authenticator(request -> {
                    List<String> values = request.headers().get("Authorization");
                    if (values == null || !values.contains(AUTHORIZATION)) {
                        return CompletableFuture.failedFuture(
                                new A2AAuthenticationException(401, "Authentication required."));
                    }
                    return CompletableFuture.completedFuture(new A2APrincipal("integration", "tenant"));
                })
                .build();
        A2AHttpServer server = A2AHttpServer.start(service, options);
        return new Fixture(service, server);
    }

    private static AgentCard card(String name, boolean push, boolean extended) {
        return AgentCard.builder(name, name + " agent", "1.0.0")
                .capabilities(AgentCapabilities.builder()
                        .streaming(true)
                        .pushNotifications(push)
                        .extendedAgentCard(extended)
                        .build())
                .skills(List.of(AgentSkill.builder("echo", "Echo", "Echoes")
                        .tags(List.of("test"))
                        .build()))
                .supportedInterfaces(List.of(AgentInterface.jsonRpc(URI.create("http://127.0.0.1:1/a2a"))))
                .build();
    }

    private static A2AClient client(URI endpoint, boolean authenticated) {
        A2AClientOptions.Builder options = A2AClientOptions.builder(endpoint).allowInsecureLoopbackHttp(true);
        if (authenticated) {
            options.headerProvider(ignored -> Map.of("Authorization", AUTHORIZATION));
        }
        return A2AClient.create(options.build());
    }

    private static SendMessageRequest request(String text, String id) {
        return new SendMessageRequest(
                Message.builder(Role.ROLE_USER)
                        .messageId(id)
                        .parts(List.of(new TextPart(text)))
                        .build(),
                new SendMessageConfiguration(List.of("text/plain"), 0, null, false),
                Map.of(),
                null);
    }

    private static String rpc(String method, String id, StateValue params) {
        A2AJsonCodec codec = new A2AJsonCodec(A2ALimits.defaults());
        return codec.writeString(StateValue.object(Map.of(
                "jsonrpc",
                StateValue.string("2.0"),
                "id",
                StateValue.string(id),
                "method",
                StateValue.string(method),
                "params",
                params)));
    }

    private static HttpRequest rawRequest(
            URI endpoint, String body, String contentType, String authorization, String origin) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", contentType)
                .header("A2A-Version", "1.0")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        if (origin != null) {
            builder.header("Origin", origin);
        }
        return builder.build();
    }

    private static final class ChunkingEchoExecutor implements A2AExecutor {
        @Override
        public CompletionStage<Void> executeAsync(
                A2AExecutionContext context, A2AEventSink sink, RunCancellation cancellation) {
            String text = ((TextPart) context.request().message().parts().getFirst()).text();
            String artifactId = context.task().id() + "-result";
            return sink.updateStatusAsync(TaskState.TASK_STATE_WORKING, null)
                    .thenCompose(ignored -> sink.addArtifactAsync(
                            Artifact.builder(artifactId)
                                    .parts(List.of(new TextPart("echo:")))
                                    .build(),
                            false,
                            false,
                            Map.of()))
                    .thenCompose(ignored -> sink.addArtifactAsync(
                            Artifact.builder(artifactId)
                                    .parts(List.of(new TextPart(text)))
                                    .build(),
                            true,
                            true,
                            Map.of()))
                    .thenCompose(ignored -> sink.updateStatusAsync(TaskState.TASK_STATE_COMPLETED, null))
                    .thenApply(ignored -> null);
        }
    }

    private static final class WaitingExecutor implements A2AExecutor {
        private final CompletableFuture<Void> started = new CompletableFuture<>();

        private final CompletableFuture<Boolean> canceled = new CompletableFuture<>();

        @Override
        public CompletionStage<Void> executeAsync(
                A2AExecutionContext context, A2AEventSink sink, RunCancellation cancellation) {
            CompletableFuture<Void> pending = new CompletableFuture<>();
            return sink.updateStatusAsync(TaskState.TASK_STATE_WORKING, null).thenCompose(ignored -> {
                started.complete(null);
                RunCancellations.register(cancellation, () -> {
                    canceled.complete(true);
                    pending.completeExceptionally(new com.microsoft.agents.core.RunCancelledException());
                });
                return pending;
            });
        }

        private CompletableFuture<Void> started() {
            return started;
        }

        private CompletableFuture<Boolean> canceled() {
            return canceled;
        }
    }

    private record Fixture(A2AService service, A2AHttpServer server) implements AutoCloseable {
        @Override
        public void close() {
            server.close();
            service.close();
        }
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<A2AStreamEvent> {
        private final List<A2AStreamEvent> events = new CopyOnWriteArrayList<>();

        private final CompletableFuture<Void> completed = new CompletableFuture<>();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(A2AStreamEvent item) {
            events.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            completed.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            completed.complete(null);
        }

        private List<A2AStreamEvent> events() {
            return List.copyOf(events);
        }

        private CompletableFuture<Void> completed() {
            return completed;
        }
    }
}
